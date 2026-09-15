package treasury.registry

import com.daml.ledger.javaapi.data.DisclosedContract
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.metadatav1.ChoiceContext
import daml.splice.api.token.metadatav1.Metadata
import daml.splice.api.token.transferinstructionv2.TransferFactory_Transfer
import daml.splice.api.token.transferinstructionv2.TransferInstruction

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
  * `MonadError[F, Error]`) — see [[treasury.ledger.LedgerClient]].
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

    enum Error(val message: String) extends RuntimeException(message):
        case FactoryNotFound(what: String) extends Error(s"factory not found: $what")
        case AccountConfigNotFound(account: String) extends Error(s"no account config for $account")
        case Http(status: Int, body: String) extends Error(s"registry HTTP $status: $body")
        case Decode(detail: String) extends Error(s"decode failure: $detail")
        case NotImplemented(endpoint: String) extends Error(s"endpoint not implemented: $endpoint")
        case Unexpected(detail: String) extends Error(detail)
