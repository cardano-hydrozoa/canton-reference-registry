package tokenstandard.registry

import cats.arrow.FunctionK
import com.daml.ledger.javaapi.data.DisclosedContract
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.metadatav1.ChoiceContext
import daml.splice.api.token.metadatav1.Metadata
import daml.splice.api.token.transferinstructionv2.TransferFactory_Transfer
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import tokenstandard.registry.openapi.metadata.models as md

/** Off-ledger registry API of a CIP-0112 token-standard registry — the Scala port of Daml's
  * `Splice.Testing.TokenStandard.RegistryApiV2.RegistryApi` typeclass, listing the complete set of
  * endpoints a registry operator serves: the three factories (transfer / allocation / settlement),
  * which return a ready-to-exercise [[RegistryApi.EnrichedFactoryChoice]], and the seven
  * allocation- / transfer-instruction lifecycle handlers, which return a
  * [[RegistryApi.OpenApiChoiceContext]].
  *
  * A pure abstract interface, tagless-final over `F[_]`. An implementation provides every endpoint
  * it serves; a partial implementation stubs the rest in its own code (e.g. raising
  * [[RegistryApi.Error.NotImplemented]]). Errors are folded into `F` (the flow runs in a
  * `MonadError[F, Error]`) — see [[tokenstandard.ledger.LedgerClient]].
  */
trait RegistryApi[F[_]]:
    import RegistryApi.*

    // -- Factories ---------------------------------------------------------------------------------

    def getTransferFactory(
        arg: TransferFactory_Transfer
    ): F[EnrichedFactoryChoice[TransferFactory_Transfer]]

    def getAllocationFactory(
        arg: AllocationFactory_Allocate
    ): F[EnrichedFactoryChoice[AllocationFactory_Allocate]]

    def getSettlementFactory(
        arg: SettlementFactory_SettleBatch
    ): F[EnrichedFactoryChoice[SettlementFactory_SettleBatch]]

    // -- Allocation lifecycle choice contexts ------------------------------------------------------

    def getAllocationWithdrawContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext]

    def getAllocationCancelContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext]

    // -- Allocation-instruction choice contexts ----------------------------------------------------

    def getAllocationInstructionWithdrawContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext]

    def getAllocationInstructionAcceptContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext]

    // -- Transfer-instruction choice contexts ------------------------------------------------------

    def getTransferInstructionAcceptContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext]

    def getTransferInstructionRejectContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext]

    def getTransferInstructionWithdrawContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext]

    // -- Registry metadata (metadata-v1) -----------------------------------------------------------
    // Static catalog data, no choice contexts — these have no Daml counterpart (the OpenAPI schema
    // is the authoritative definition), hence the generated wire DTOs as return types.

    def getRegistryInfo: F[md.GetRegistryInfoResponse]

    /** `pageToken` is the `nextPageToken` from the previous page (the last instrument id). */
    def listInstruments(
        pageSize: Option[Int],
        pageToken: Option[String],
    ): F[md.ListInstrumentsResponse]

    def getInstrument(instrumentId: String): F[md.Instrument]

object RegistryApi:

    /** Daml's `EnrichedFactoryChoice`: the factory contract to exercise on, the choice argument
      * with `extraArgs.context` filled in, and the disclosures to attach (the admin-owned contracts
      * — factory rules, account configs, locked holdings — the submitter must make visible in its
      * transaction). `factoryCid` is an opaque contract-id string.
      */
    final case class EnrichedFactoryChoice[Arg](
        factoryCid: String,
        arg: Arg,
        disclosures: List[DisclosedContract],
    )

    /** Daml's `OpenApiChoiceContext`: the choice context to pass in `extraArgs.context` plus the
      * disclosures to attach. Returned by the allocation / transfer-instruction lifecycle handlers.
      */
    final case class OpenApiChoiceContext(
        choiceContext: ChoiceContext,
        disclosures: List[DisclosedContract],
    )

    /** Interpret an implementation into another effect via `fk` — e.g. lift the IO-based reference
      * registry into a flow's `MonadError[G, Error]` effect (Canton's `EitherT[IO, Error, *]`, the
      * in-memory `StateT`) by attempting/absorbing the error channel inside `fk`.
      */
    def mapK[F[_], G[_]](underlying: RegistryApi[F])(fk: FunctionK[F, G]): RegistryApi[G] =
        new RegistryApi[G]:
            def getTransferFactory(
                arg: TransferFactory_Transfer
            ): G[EnrichedFactoryChoice[TransferFactory_Transfer]] =
                fk(underlying.getTransferFactory(arg))
            def getAllocationFactory(
                arg: AllocationFactory_Allocate
            ): G[EnrichedFactoryChoice[AllocationFactory_Allocate]] =
                fk(underlying.getAllocationFactory(arg))
            def getSettlementFactory(
                arg: SettlementFactory_SettleBatch
            ): G[EnrichedFactoryChoice[SettlementFactory_SettleBatch]] =
                fk(underlying.getSettlementFactory(arg))
            def getAllocationWithdrawContext(
                allocation: Allocation.ContractId,
                meta: Metadata
            ): G[OpenApiChoiceContext] =
                fk(underlying.getAllocationWithdrawContext(allocation, meta))
            def getAllocationCancelContext(
                allocation: Allocation.ContractId,
                meta: Metadata
            ): G[OpenApiChoiceContext] =
                fk(underlying.getAllocationCancelContext(allocation, meta))
            def getAllocationInstructionWithdrawContext(
                instruction: AllocationInstruction.ContractId,
                meta: Metadata,
            ): G[OpenApiChoiceContext] = fk(
              underlying.getAllocationInstructionWithdrawContext(instruction, meta)
            )
            def getAllocationInstructionAcceptContext(
                instruction: AllocationInstruction.ContractId,
                meta: Metadata,
            ): G[OpenApiChoiceContext] = fk(
              underlying.getAllocationInstructionAcceptContext(instruction, meta)
            )
            def getTransferInstructionAcceptContext(
                instruction: TransferInstruction.ContractId,
                meta: Metadata,
            ): G[OpenApiChoiceContext] = fk(
              underlying.getTransferInstructionAcceptContext(instruction, meta)
            )
            def getTransferInstructionRejectContext(
                instruction: TransferInstruction.ContractId,
                meta: Metadata,
            ): G[OpenApiChoiceContext] = fk(
              underlying.getTransferInstructionRejectContext(instruction, meta)
            )
            def getTransferInstructionWithdrawContext(
                instruction: TransferInstruction.ContractId,
                meta: Metadata,
            ): G[OpenApiChoiceContext] = fk(
              underlying.getTransferInstructionWithdrawContext(instruction, meta)
            )
            def getRegistryInfo: G[md.GetRegistryInfoResponse] = fk(underlying.getRegistryInfo)
            def listInstruments(
                pageSize: Option[Int],
                pageToken: Option[String]
            ): G[md.ListInstrumentsResponse] =
                fk(underlying.listInstruments(pageSize, pageToken))
            def getInstrument(instrumentId: String): G[md.Instrument] = fk(
              underlying.getInstrument(instrumentId)
            )

    enum Error(val message: String) extends RuntimeException(message):
        case FactoryNotFound(what: String) extends Error(s"factory not found: $what")
        case AccountConfigNotFound(account: String) extends Error(s"no account config for $account")
        case Http(status: Int, body: String) extends Error(s"registry HTTP $status: $body")
        case Decode(detail: String) extends Error(s"decode failure: $detail")
        case NotImplemented(endpoint: String) extends Error(s"endpoint not implemented: $endpoint")
        case InstrumentNotFound(instrumentId: String)
            extends Error(s"instrument not found: $instrumentId")
        case ContractNotFound(cid: String) extends Error(s"contract not found: $cid")
        case Unexpected(detail: String) extends Error(detail)
