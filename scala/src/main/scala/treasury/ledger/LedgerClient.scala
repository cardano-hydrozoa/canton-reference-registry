package treasury.ledger

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import treasury.PartyId
import treasury.registry.RegistryBackend.EnrichedFactoryChoice
import treasury.registry.RegistryBackend.Error

/** The ledger-interaction concern the Daml Script fuses into its registry helpers: submit commands,
  * read the ACS, fetch balances. Both the registry backends and the treasury flow use it. In Phase
  * 1 an in-memory fake implements it; Phase 2 swaps in a Canton gRPC client.
  *
  * The exercise methods are purpose-built per factory choice rather than a single generic
  * `exercise[R]`, so Phase 1 need not decode opaque Daml values. Phase 2 generalizes them once the
  * codegen `Update`/result decoders are wired to the Ledger API.
  */
trait LedgerClient[F[_]]:
    import LedgerClient.*

    /** Exercise an allocation factory: the choice returns the created allocation. */
    def exerciseAllocationFactory(
        actAs: PartyId,
        bundle: EnrichedFactoryChoice[AllocationFactory_Allocate],
    ): F[Either[Error, Allocation.ContractId]]

    /** Exercise a settlement factory: settle the batch atomically. */
    def exerciseSettlementFactory(
        actAs: PartyId,
        bundle: EnrichedFactoryChoice[SettlementFactory_SettleBatch],
    ): F[Either[Error, SettleResult]]

    // --- ACS reads for balance checkpoints (port of WalletClientV2) ------------
    def unlockedBalance(owner: PartyId, instrument: InstrumentId): F[Either[Error, BigDecimal]]
    def lockedBalance(owner: PartyId, instrument: InstrumentId): F[Either[Error, BigDecimal]]
    def listHoldingCids(
        owner: PartyId,
        instrument: InstrumentId
    ): F[Either[Error, List[Holding.ContractId]]]
    def activeAllocations(owner: PartyId): F[Either[Error, List[Allocation.ContractId]]]

object LedgerClient:

    /** Result of a settle batch, projected to what the treasury flow needs: for each finalized
      * allocation (in input order) the next-iteration allocation, if it rolled forward. Port of the
      * bit of `SettlementFactory_SettleBatchResult` the flow reads via
      * `extractNextIterationAllocationCid`.
      */
    final case class SettleResult(nextIterationAllocations: List[Option[Allocation.ContractId]])
