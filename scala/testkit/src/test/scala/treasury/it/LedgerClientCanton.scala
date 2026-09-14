package treasury.it

import java.util.{Base64, Optional, UUID}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import cats.data.EitherT
import cats.effect.IO

import com.daml.ledger.javaapi.data.{ActiveContract, CommandsSubmission, ContractFilter, CreatedEvent, DisclosedContract, Identifier}
import com.daml.ledger.javaapi.data.codegen.HasCommands
import com.daml.ledger.rxjava.DamlLedgerClient
import io.reactivex.Single

import com.google.protobuf.ByteString

import treasury.PartyId
import treasury.TokenStandardHelpers
import treasury.registry.RegistryApi.Error
import treasury.registry.service.ContextKeys

import daml.splice.api.token.allocationinstructionv2.{AllocationFactory, AllocationFactory_Allocate}
import daml.splice.api.token.holdingv2.{Account as DamlAccount, Holding, InstrumentId}
import daml.splice.api.token.metadatav1.{AnyContract, AnyValue, ChoiceContext, ExtraArgs}
import daml.splice.api.token.metadatav1.anyvalue.{AV_ContractId, AV_List}
import daml.splice.api.token.transferinstructionv2.{TransferInstruction, TransferInstruction_Accept}
import daml.splice.testing.tokens.testtokenv2.{TokenRules, TokenRules_OfferMint}
import daml.splice.testing.tokens.testtokenv2.accountconfig.{AccountConfig, PartyConfig}
import daml.splice.testing.tokens.testtokenv2.transfer.TokenTransferOffer

/** Canton effect: IO with the domain error in an Either base, so the flow's `MonadError[F, Error]`
  * is satisfied (plain IO only has `MonadError[IO, Throwable]`).
  */
type CantonM[A] = EitherT[IO, Error, A]

/** Ledger-API client against a live Canton participant (Phase 2). Wraps the rxjava
  * `DamlLedgerClient`; each call runs the blocking rx call on IO and maps failures to `Error`.
  *
  * Slice 2b-1: the submission + typed-ACS-read foundation (create a contract, read it back). The
  * `LedgerClient` trait methods (factory exercises, balance reads) build on these primitives next.
  */
final class LedgerClientCanton private (client: DamlLedgerClient, userId: String):

    /** Create the registry's TokenRules contract, returning its id. Ledger API v2 here does not
      * implement SubmitAndWaitForTransactionTree, so we submit-and-wait then read the cid back from
      * the ACS.
      */
    def createTokenRules(admin: PartyId): CantonM[TokenRules.ContractId] =
        for
            _ <- submitAndWait(admin, List(TokenRules.create(admin.value)))
            rules <- activeContractsOf(ContractFilter.of(TokenRules.COMPANION), admin)
            cid <- EitherT.fromEither[IO](
              rules.headOption
                  .map(_.id)
                  .toRight(Error.Unexpected("TokenRules not in ACS after create"))
            )
        yield cid

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

    /** Exercise the `AllocationFactory_Allocate` choice on the factory contract (a `TokenRules`
      * cid, coerced to the `AllocationFactory` interface), attaching the assembled disclosures.
      * Succeeds (`Unit`) iff the ledger accepts the submission — the P5 acceptance check.
      */
    def exerciseAllocationFactory(
        actAs: PartyId,
        factoryCid: String,
        arg: AllocationFactory_Allocate,
        disclosures: List[DisclosedContract],
    ): CantonM[Unit] =
        submitAndWait(
          actAs,
          List(new AllocationFactory.ContractId(factoryCid).exerciseAllocationFactory_Allocate(arg)),
          disclosures,
        )

    /** Mint `amount` of instrument `instrumentName` into `owner`'s basic (owner-only) account,
      * returning the resulting unlocked [[Holding]] cids — the funding for a sender-side
      * allocation. Two ledger steps mirroring `TestTokenV2_RegistryV2.mint`: the admin offers the
      * mint (`TokenRules_OfferMint`), then the owner accepts the resulting `TransferInstruction`.
      *
      * A basic-account mint stores no `AccountConfig` (the config is passed inline to the offer)
      * and locks no input holdings, so the accept's choice context is just the two standard keys —
      * `tokenRules` and an empty `accountConfigs` list — plus the TokenRules disclosure; no
      * `getTransferInstruction_AcceptContext` registry round-trip is needed. The offer cid is read
      * back from the ACS (this Ledger API build has no submit-and-wait-for-transaction-tree).
      */
    def mint(
        admin: PartyId,
        owner: PartyId,
        instrumentName: String,
        amount: BigDecimal,
        offeredAt: java.time.Instant,
    ): CantonM[List[Holding.ContractId]] =
        val receiver = new DamlAccount(Optional.of(owner.value), Optional.empty(), "")
        val instrument = new InstrumentId(admin.value, instrumentName)
        for
            rulesD <- activeWithDisclosure(ContractFilter.of(TokenRules.COMPANION), admin).map(_.head)
            _ <- submitAndWait(
              admin,
              List(
                new TokenRules.ContractId(rulesD.contractId).exerciseTokenRules_OfferMint(
                  new TokenRules_OfferMint(
                    receiver,
                    amount.bigDecimal,
                    instrument,
                    offeredAt,
                    new AccountConfig(
                      admin.value,
                      receiver,
                      new PartyConfig(true, true),
                      new PartyConfig(false, false),
                    ),
                  )
                )
              ),
              Nil,
            )
            offers <- activeContractsOf(ContractFilter.of(TokenTransferOffer.COMPANION), admin)
            // The mint is a transfer from a special `cip-112/mint` account (principal = admin) to the
            // receiver: admin pre-authorizes TIA_Accept as the offerer, leaving the receiver's party
            // to accept. So `owner` must be a non-admin party — if receiver == admin, offerer and
            // acceptor collapse, the offer reaches TIS_Accepted at OfferMint time, and there is no
            // TIA_Accept left to exercise.
            offerCid <- EitherT.fromEither[IO](
              offers.headOption
                  .map(_.id)
                  .toRight(Error.Unexpected("no TokenTransferOffer in ACS after OfferMint"))
            )
            ctx = new ChoiceContext(
              Map[String, AnyValue](
                ContextKeys.accountConfigs -> new AV_List(List.empty[AnyValue].asJava),
                ContextKeys.tokenRules -> new AV_ContractId(
                  new AnyContract.ContractId(rulesD.contractId)
                ),
              ).asJava
            )
            _ <- submitAndWait(
              owner,
              List(
                new TransferInstruction.ContractId(offerCid.contractId)
                    .exerciseTransferInstruction_Accept(
                      new TransferInstruction_Accept(
                        List(owner.value).asJava,
                        new ExtraArgs(ctx, TokenStandardHelpers.emptyMetadata),
                      )
                    )
              ),
              List(toDisclosedContract(rulesD)),
            )
            holdings <- activeContractsOf(Holding.contractFilter(), owner)
        yield holdings
            .filter(c => c.data.instrumentId == instrument && c.data.lock.isEmpty)
            .map(_.id)

    private def submitAndWait(
        actAs: PartyId,
        cmds: List[HasCommands],
        disclosures: List[DisclosedContract] = Nil,
    ): CantonM[Unit] =
        val submission = CommandsSubmission
            .create(userId, UUID.randomUUID().toString, Optional.empty(), cmds.asJava)
            .withActAs(actAs.value)
            .withDisclosedContracts(disclosures.asJava)
        single(client.getCommandClient.submitAndWait(submission)).map(_ => ())

    private def toDisclosedContract(d: LedgerClientCanton.Disclosed[?]): DisclosedContract =
        new DisclosedContract(
          LedgerClientCanton.parseIdentifier(d.templateId),
          d.contractId,
          ByteString.copyFrom(Base64.getDecoder.decode(d.createdEventBlobBase64)),
          d.synchronizerId,
        )

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

    /** Fully-qualified template id `<pkgId>:<Module>:<Entity>`, as used in `DisclosedContract`. */
    def identifierString(id: Identifier): String =
        s"${id.getPackageId}:${id.getModuleName}:${id.getEntityName}"

    /** Inverse of [[identifierString]]: parse `<pkgId>:<Module>:<Entity>` back into an `Identifier`. */
    def parseIdentifier(s: String): Identifier =
        s.split(":") match
            case Array(pkg, module, entity) => new Identifier(pkg, module, entity)
            case _ => throw new IllegalArgumentException(s"malformed template id: $s")

    def connect(host: String, port: Int, userId: String = "treasury-it"): LedgerClientCanton =
        val client = DamlLedgerClient.newBuilder(host, port).build()
        client.connect()
        new LedgerClientCanton(client, userId)
