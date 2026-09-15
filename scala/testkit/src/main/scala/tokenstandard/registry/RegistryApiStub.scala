package tokenstandard.registry

import cats.Applicative
import cats.syntax.all.*
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.metadatav1.Metadata
import daml.splice.api.token.transferinstructionv2.TransferFactory_Transfer
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import tokenstandard.registry.RegistryApi.EnrichedFactoryChoice
import tokenstandard.registry.RegistryApi.OpenApiChoiceContext

/** Pure stub registry (Phase 1): echoes each factory request into a factory-choice bundle with an
  * opaque factory cid and no disclosures. It carries no ledger knowledge — the in-memory
  * [[tokenstandard.ledger.InMemoryLedger]] interprets the returned `arg`. Being pure, it is
  * polymorphic in `F` (any `Applicative`), so it runs in the ledger's `StateT` effect with no
  * runtime; mirrors CardanoBackendMock's role as a network-free stand-in.
  */
final class RegistryApiStub[F[_]: Applicative] extends RegistryApi[F]:

    private def bundle[A](kind: String, arg: A): F[EnrichedFactoryChoice[A]] =
        EnrichedFactoryChoice(s"stub-factory/$kind", arg, Nil).pure[F]

    override def getTransferFactory(arg: TransferFactory_Transfer) = bundle("transfer", arg)
    override def getAllocationFactory(arg: AllocationFactory_Allocate) = bundle("allocation", arg)
    override def getSettlementFactory(arg: SettlementFactory_SettleBatch) =
        bundle("settlement", arg)

    // The lifecycle contexts are never exercised by the flows the stub backs. It is a pure
    // `Applicative` (no error channel), so an unsupported endpoint throws directly.
    override def getAllocationWithdrawContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        throw RegistryApi.Error.NotImplemented("getAllocationWithdrawContext")

    override def getAllocationCancelContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        throw RegistryApi.Error.NotImplemented("getAllocationCancelContext")

    override def getAllocationInstructionWithdrawContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        throw RegistryApi.Error.NotImplemented("getAllocationInstructionWithdrawContext")

    override def getAllocationInstructionAcceptContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        throw RegistryApi.Error.NotImplemented("getAllocationInstructionAcceptContext")

    override def getTransferInstructionAcceptContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        throw RegistryApi.Error.NotImplemented("getTransferInstructionAcceptContext")

    override def getTransferInstructionRejectContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        throw RegistryApi.Error.NotImplemented("getTransferInstructionRejectContext")

    override def getTransferInstructionWithdrawContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        throw RegistryApi.Error.NotImplemented("getTransferInstructionWithdrawContext")
