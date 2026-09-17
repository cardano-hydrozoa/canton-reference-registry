package tokenstandard.ledger

import cats.data.EitherT
import cats.effect.IO
import com.daml.ledger.api.v2.CommandServiceGrpc
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitForTransactionRequest
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v2.StateServiceGrpc
import com.daml.ledger.api.v2.StateServiceOuterClass.ActiveContract as ProtoActiveContract
import com.daml.ledger.api.v2.StateServiceOuterClass.GetActiveContractsRequest
import com.daml.ledger.api.v2.StateServiceOuterClass.GetActiveContractsResponse.ContractEntryCase
import com.daml.ledger.api.v2.StateServiceOuterClass.GetLedgerEndRequest
import com.daml.ledger.javaapi.data.CommandsSubmission
import com.daml.ledger.javaapi.data.ContractFilter
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.CumulativeFilter
import com.daml.ledger.javaapi.data.DisclosedContract
import com.daml.ledger.javaapi.data.EventFormat
import com.daml.ledger.javaapi.data.ExercisedEvent
import com.daml.ledger.javaapi.data.Filter
import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.Transaction
import com.daml.ledger.javaapi.data.TransactionFormat
import com.daml.ledger.javaapi.data.TransactionShape
import com.daml.ledger.javaapi.data.codegen.HasCommands
import com.daml.ledger.javaapi.data.codegen.Update
import com.google.protobuf.ByteString
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import tokenstandard.PartyId
import tokenstandard.registry.RegistryApi.Error

import java.util.Base64
import java.util.Optional
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** Canton effect: IO with the domain error in an Either base, so the flow's `MonadError[F, Error]`
  * is satisfied (plain IO only has `MonadError[IO, Throwable]`).
  */
type CantonM[A] = EitherT[IO, Error, A]

/** [[LedgerClient]] against a live Canton participant, driven over the raw gRPC Ledger API v2
  * (`CommandService` + `StateService` blocking stubs on a Netty channel) rather than the
  * `bindings-rxjava` `DamlLedgerClient`. We build the request/response values with the
  * `javaapi.data` types we already use and cross the gRPC boundary with their own `.toProto()` /
  * `.fromProto()` — so the value logic is identical; only the transport changed. Each blocking call
  * runs on IO and maps failures to `Error`.
  *
  * [[submit]] sends the whole `Submission` (one or many commands) as one `CommandsSubmission` via
  * `SubmitAndWaitForTransaction` with a `LEDGER_EFFECTS`-shaped `TransactionFormat` — the shape
  * that carries the root `ExercisedEvent`s with the choice results — and feeds each root event
  * through its codegen update's own decoder/continuation. (`SubmitAndWaitForTransactionTree` — what
  * the rxjava `submitAndWaitForResult` convenience delegated to for exercises — is not implemented
  * on this Canton build; `LEDGER_EFFECTS` on the plain transaction endpoint is the way in.)
  */
final class LedgerClientCanton private (channel: ManagedChannel, userId: String)
    extends LedgerClient[CantonM]:

    private val commandStub = CommandServiceGrpc.newBlockingStub(channel)
    private val stateStub = StateServiceGrpc.newBlockingStub(channel)

    def submit[A](
        actAs: List[PartyId],
        readAs: List[PartyId],
        submission: Submission[A],
        disclosures: List[DisclosedContract],
    ): CantonM[A] =
        submission.commands.find(u => !u.isInstanceOf[Update.ExerciseUpdate[?, ?]]) match
            case Some(other) =>
                EitherT.leftT(
                  Error.NotImplemented(s"LedgerClientCanton update: ${other.getClass.getName}")
                )
            case None =>
                val format = new TransactionFormat(
                  wildcardEventFormat(actAs ++ readAs),
                  TransactionShape.LEDGER_EFFECTS,
                )
                // All commands ride ONE CommandsSubmission → one atomic transaction. Built
                // directly, NOT via UpdateSubmission.toCommandsSubmission, which silently drops
                // disclosed contracts (passes emptyList() at that ctor position).
                val base = CommandsSubmission
                    .create(
                      userId,
                      UUID.randomUUID().toString,
                      Optional.empty(),
                      submission.commands.map(u => u: HasCommands).asJava,
                    )
                    .withActAs(actAs.map(_.value).asJava)
                    .withDisclosedContracts(disclosures.asJava)
                val cmds =
                    if readAs.isEmpty then base else base.withReadAs(readAs.map(_.value).asJava)
                val request = SubmitAndWaitForTransactionRequest
                    .newBuilder()
                    .setCommands(cmds.toProto)
                    .setTransactionFormat(format.toProto)
                    .build()
                for
                    resp <- blocking(commandStub.submitAndWaitForTransaction(request))
                    tx = Transaction.fromProto(resp.getTransaction)
                    events <- EitherT.fromEither[IO](
                      rootExercisedEvents(tx, submission.commands.size)
                    )
                    result <- blocking(
                      submission.decode(
                        submission.commands.zip(events).map(Submission.decodeExercised)
                      )
                    )
                yield result

    /** Per-party wildcard filter for the submission's transaction: the acting/reading parties see
      * every event they are informees of — in particular the root exercise carrying the result.
      */
    private def wildcardEventFormat(parties: List[PartyId]): EventFormat =
        val wildcard: Filter = new CumulativeFilter(
          java.util.Map.of(),
          java.util.Map.of(),
          Optional.of(Filter.Wildcard.HIDE_CREATED_EVENT_BLOB),
        )
        new EventFormat(
          parties.map(p => p.value -> wildcard).toMap.asJava,
          Optional.empty(),
          false,
        )

    /** The root `ExercisedEvent`s of a LEDGER_EFFECTS transaction, in command order.
      *
      * ASSUMPTION, not a stated Ledger-API guarantee: Canton assigns root node ids in command
      * order, so sorting them recovers submission order for the positional decode. The count check
      * catches a gross mismatch but NOT a reordering — two commands returning the same type could
      * decode swapped. Held live for multi-command submissions (CantonSwapSpec); re-verify on
      * Canton upgrades.
      */
    private def rootExercisedEvents(
        tx: Transaction,
        expected: Int,
    ): Either[Error, List[ExercisedEvent]] =
        val events = tx.getRootNodeIds.asScala.toList.sorted
            .flatMap(id => Option(tx.getEventsById.get(id)))
            .collect { case e: ExercisedEvent => e }
        Either.cond(
          events.size == expected,
          events,
          Error.Unexpected(
            s"expected $expected root ExercisedEvents in transaction ${tx.getUpdateId}, " +
                s"got ${events.size}: ${tx.getEvents}"
          ),
        )

    // --- ACS reads for balance checkpoints -------------------------------------

    def unlockedBalance(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId
    ): CantonM[BigDecimal] =
        holdingsOf(as, owner, instrument).map(
          _.filter(_.data.lock.isEmpty).map(c => BigDecimal(c.data.amount)).sum
        )

    def lockedBalance(as: PartyId, owner: PartyId, instrument: InstrumentId): CantonM[BigDecimal] =
        holdingsOf(as, owner, instrument).map(
          _.filter(_.data.lock.isPresent).map(c => BigDecimal(c.data.amount)).sum
        )

    def listHoldingCids(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId,
    ): CantonM[List[Holding.ContractId]] =
        holdingsOf(as, owner, instrument).map(_.filter(_.data.lock.isEmpty).map(_.id))

    def activeAllocations(as: PartyId, owner: PartyId): CantonM[List[Allocation.ContractId]] =
        activeContractsOf(Allocation.contractFilter(), as).map(
          _.filter(_.data.allocation.authorizer.owner == Optional.of(owner.value)).map(_.id)
        )

    /** `owner`'s active holdings of `instrument`, locked or not, as seen by `as`. */
    private def holdingsOf(as: PartyId, owner: PartyId, instrument: InstrumentId) =
        activeContractsOf(Holding.contractFilter(), as).map(
          _.filter(c =>
              c.data.account.owner == Optional.of(owner.value) && c.data.instrumentId == instrument
          )
        )

    // --- generic primitives -----------------------------------------------------

    /** Typed ACS query for a template/interface, as seen by `readAs`. `ContractFilter.eventFormat`
      * carries the companion's template/interface selection; each active `CreatedEvent` decodes
      * back through `filter.toContract`.
      */
    def activeContractsOf[Ct](filter: ContractFilter[Ct], readAs: PartyId): CantonM[List[Ct]] =
        activeContracts(filter.eventFormat(Optional.of(Set(readAs.value).asJava)))
            .map(_.map(ac => filter.toContract(CreatedEvent.fromProto(ac.getCreatedEvent))))

    /** ACS query that also returns each contract's disclosure metadata: the decoded contract plus
      * the `createdEventBlob` (base64), fully-qualified template id, contract id and synchronizer
      * id — i.e. everything needed to build a wire `DisclosedContract`. Sets
      * `includeCreatedEventBlob` on the filter (an interface-filtered read carries no usable blob).
      */
    def activeWithDisclosure[Ct](
        filter: ContractFilter[Ct],
        readAs: PartyId,
    ): CantonM[List[LedgerClientCanton.Disclosed[Ct]]] =
        activeContracts(
          filter
              .withIncludeCreatedEventBlob(true)
              .eventFormat(Optional.of(Set(readAs.value).asJava))
        ).map(_.map { ac =>
            val ce: CreatedEvent = CreatedEvent.fromProto(ac.getCreatedEvent)
            LedgerClientCanton.Disclosed(
              contract = filter.toContract(ce),
              contractId = ce.getContractId,
              templateId = LedgerClientCanton.identifierString(ce.getTemplateId),
              createdEventBlobBase64 =
                  Base64.getEncoder.encodeToString(ce.getCreatedEventBlob.toByteArray),
              synchronizerId = ac.getSynchronizerId,
            )
        })

    /** The active contracts matching `eventFormat` at the current ledger end. Drains the
      * `GetActiveContracts` server stream and keeps the `ACTIVE_CONTRACT` entries (the ACS query
      * can also surface incomplete (un)assigned entries mid-reassignment, which we don't read).
      */
    private def activeContracts(eventFormat: EventFormat): CantonM[List[ProtoActiveContract]] =
        for
            end <- blocking(
              stateStub.getLedgerEnd(GetLedgerEndRequest.getDefaultInstance).getOffset
            )
            request = GetActiveContractsRequest
                .newBuilder()
                .setEventFormat(eventFormat.toProto)
                .setActiveAtOffset(end)
                .build()
            responses <- blocking(stateStub.getActiveContracts(request).asScala.toList)
        yield responses
            .filter(_.getContractEntryCase == ContractEntryCase.ACTIVE_CONTRACT)
            .map(_.getActiveContract)

    /** Submit `cmds` as `actAs` and wait for completion, discarding the result. */
    def submitAndWait(
        actAs: PartyId,
        cmds: List[HasCommands],
        disclosures: List[DisclosedContract] = Nil,
    ): CantonM[Unit] =
        val submission = CommandsSubmission
            .create(userId, UUID.randomUUID().toString, Optional.empty(), cmds.asJava)
            .withActAs(actAs.value)
            .withDisclosedContracts(disclosures.asJava)
        val request = SubmitAndWaitRequest.newBuilder().setCommands(submission.toProto).build()
        blocking(commandStub.submitAndWait(request)).map(_ => ())

    private def blocking[A](a: => A): CantonM[A] =
        EitherT(
          IO.blocking(a)
              .attempt
              .map(_.left.map(t => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))))
        )

    def close(): Unit =
        val _ = channel.shutdownNow()

object LedgerClientCanton:
    /** A contract read from the ACS together with the data needed to disclose it. */
    final case class Disclosed[Ct](
        contract: Ct,
        contractId: String,
        templateId: String,
        createdEventBlobBase64: String,
        synchronizerId: String,
    )

    /** The wire `DisclosedContract` for a contract read via [[activeWithDisclosure]]. */
    def toDisclosedContract(d: Disclosed[?]): DisclosedContract =
        new DisclosedContract(
          parseIdentifier(d.templateId),
          d.contractId,
          ByteString.copyFrom(Base64.getDecoder.decode(d.createdEventBlobBase64)),
          d.synchronizerId,
        )

    /** Fully-qualified template id `<pkgId>:<Module>:<Entity>`, as used in `DisclosedContract`. */
    def identifierString(id: Identifier): String =
        s"${id.getPackageId}:${id.getModuleName}:${id.getEntityName}"

    /** Inverse of [[identifierString]]: parse `<pkgId>:<Module>:<Entity>` back into an
      * `Identifier`.
      */
    def parseIdentifier(s: String): Identifier =
        s.split(":") match
            case Array(pkg, module, entity) => new Identifier(pkg, module, entity)
            case _ => throw new IllegalArgumentException(s"malformed template id: $s")

    def connect(host: String, port: Int, userId: String = "treasury-it"): LedgerClientCanton =
        val channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
        new LedgerClientCanton(channel, userId)
