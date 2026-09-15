package tokenstandard.ledger

import cats.data.EitherT
import cats.effect.IO
import com.daml.ledger.javaapi.data.ActiveContract
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
import com.daml.ledger.javaapi.data.UpdateSubmission
import com.daml.ledger.javaapi.data.codegen.Exercised
import com.daml.ledger.javaapi.data.codegen.HasCommands
import com.daml.ledger.javaapi.data.codegen.Update
import com.daml.ledger.rxjava.DamlLedgerClient
import com.google.protobuf.ByteString
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import io.reactivex.Single
import tokenstandard.PartyId
import tokenstandard.registry.RegistryApi.Error

import java.util.Base64
import java.util.Optional
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Canton effect: IO with the domain error in an Either base, so the flow's `MonadError[F, Error]`
  * is satisfied (plain IO only has `MonadError[IO, Throwable]`).
  */
type CantonM[A] = EitherT[IO, Error, A]

/** [[LedgerClient]] against a live Canton participant. Wraps the rxjava `DamlLedgerClient`; each
  * call runs the blocking rx call on IO and maps failures to `Error`.
  *
  * [[exercise]] submits via `SubmitAndWaitForTransaction` with a `LEDGER_EFFECTS`-shaped
  * `TransactionFormat` — the shape that carries the root `ExercisedEvent` with the choice result —
  * and feeds that event through the codegen update's own decoder/continuation. The rxjava
  * `submitAndWaitForResult` convenience is unusable here: for exercise updates it ignores its
  * `TransactionFormat` and delegates to `SubmitAndWaitForTransactionTree`, which this Canton build
  * does not implement.
  */
final class LedgerClientCanton private (client: DamlLedgerClient, userId: String)
    extends LedgerClient[CantonM]:

    def exercise[U](
        actAs: PartyId,
        readAs: List[PartyId],
        update: Update[U],
        disclosures: List[DisclosedContract],
    ): CantonM[U] =
        update match
            case eu: Update.ExerciseUpdate[?, ?] =>
                runExercise(
                  actAs,
                  readAs,
                  eu.asInstanceOf[Update.ExerciseUpdate[?, U]],
                  disclosures
                )
            case other =>
                EitherT.leftT(
                  Error.NotImplemented(s"LedgerClientCanton update: ${other.getClass.getName}")
                )

    private def runExercise[R, U](
        actAs: PartyId,
        readAs: List[PartyId],
        eu: Update.ExerciseUpdate[R, U],
        disclosures: List[DisclosedContract],
    ): CantonM[U] =
        val base = UpdateSubmission
            .create(userId, UUID.randomUUID().toString, eu)
            .withActAs(actAs.value)
        val submission =
            if readAs.isEmpty then base else base.withReadAs(readAs.map(_.value).asJava)
        val format = new TransactionFormat(
          wildcardEventFormat(actAs :: readAs),
          TransactionShape.LEDGER_EFFECTS,
        )
        // Disclosures must be re-attached AFTER toCommandsSubmission: the bindings'
        // UpdateSubmission.toCommandsSubmission drops them (passes emptyList() at the
        // disclosedContracts position).
        val cmds = submission.toCommandsSubmission.withDisclosedContracts(disclosures.asJava)
        for
            tx <- single(client.getCommandClient.submitAndWaitForTransaction(cmds, format))
            event <- EitherT.fromEither[IO](rootExercisedEvent(tx))
            result <- blocking(eu.k.apply(Exercised.fromEvent(eu.returnTypeDecoder, event)))
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

    /** The root `ExercisedEvent` of a LEDGER_EFFECTS transaction — the exercised command node. */
    private def rootExercisedEvent(tx: Transaction): Either[Error, ExercisedEvent] =
        tx.getRootNodeIds.asScala
            .flatMap(id => Option(tx.getEventsById.get(id)))
            .collectFirst { case e: ExercisedEvent => e }
            .toRight(
              Error.Unexpected(
                s"no root ExercisedEvent in transaction ${tx.getUpdateId}: ${tx.getEvents}"
              )
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

    /** Typed ACS query for a template/interface, as seen by `readAs`. */
    def activeContractsOf[Ct](filter: ContractFilter[Ct], readAs: PartyId): CantonM[List[Ct]] =
        for
            end <- single(client.getStateClient.getLedgerEnd)
            batches <- blocking(
              client.getStateClient
                  .getActiveContracts(filter, Set(readAs.value).asJava, false, end)
                  .blockingIterable()
                  .asScala
                  .toList
            )
        yield batches.flatMap(_.activeContracts.asScala.toList)

    /** ACS query that also returns each contract's disclosure metadata: the decoded contract plus
      * the `createdEventBlob` (base64), fully-qualified template id, contract id and synchronizer
      * id — i.e. everything needed to build a wire `DisclosedContract`. Uses the raw `EventFormat`
      * overload with `includeCreatedEventBlob`, which the typed overload does not expose.
      */
    def activeWithDisclosure[Ct](
        filter: ContractFilter[Ct],
        readAs: PartyId,
    ): CantonM[List[LedgerClientCanton.Disclosed[Ct]]] =
        val fmt =
            filter
                .withIncludeCreatedEventBlob(true)
                .eventFormat(Optional.of(Set(readAs.value).asJava))
        for
            end <- single(client.getStateClient.getLedgerEnd)
            responses <- blocking(
              client.getStateClient.getActiveContracts(fmt, end).blockingIterable().asScala.toList
            )
        yield responses.flatMap { r =>
            r.getContractEntry.toScala.collect { case ac: ActiveContract =>
                val ce: CreatedEvent = ac.getCreatedEvent
                LedgerClientCanton.Disclosed(
                  contract = filter.toContract(ce),
                  contractId = ce.getContractId,
                  templateId = LedgerClientCanton.identifierString(ce.getTemplateId),
                  createdEventBlobBase64 =
                      Base64.getEncoder.encodeToString(ce.getCreatedEventBlob.toByteArray),
                  synchronizerId = ac.getSynchronizerId,
                )
            }
        }

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
        single(client.getCommandClient.submitAndWait(submission)).map(_ => ())

    private def single[A](s: => Single[A]): CantonM[A] = blocking(s.blockingGet())

    private def blocking[A](a: => A): CantonM[A] =
        EitherT(
          IO.blocking(a)
              .attempt
              .map(_.left.map(t => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))))
        )

    def close(): Unit = client.close()

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
        val client = DamlLedgerClient.newBuilder(host, port).build()
        client.connect()
        new LedgerClientCanton(client, userId)
