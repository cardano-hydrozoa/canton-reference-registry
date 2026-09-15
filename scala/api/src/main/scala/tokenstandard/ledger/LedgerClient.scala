package tokenstandard.ledger

import com.daml.ledger.javaapi.data.DisclosedContract
import com.daml.ledger.javaapi.data.codegen.Update
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import tokenstandard.PartyId

/** The ledger-interaction seam a token-standard consumer needs next to
  * [[tokenstandard.registry.RegistryApi]]: submit commands and read the ACS/balances. The Daml
  * Script fuses both into its registry helpers; here they are one trait so a flow is parametric in
  * the ledger the same way it is in the registry. Reference implementation: [[InMemoryLedger]]; a
  * Canton Ledger-API client is the live one.
  *
  * Errors live in `F` (e.g. `MonadError[F, RegistryApi.Error]`), not in the return values, so a
  * failed operation aborts the flow.
  */
trait LedgerClient[F[_]]:

    /** Submit a codegen [[Update]] — the command plus its typed-result continuation, as produced by
      * the generated `exercise*` methods (e.g.
      * `new AllocationFactory.ContractId(cid).exerciseAllocationFactory_Allocate(arg)`) — and
      * return the decoded choice result. `disclosures` are the registry-owned contracts the
      * submitter must attach (from `EnrichedFactoryChoice.disclosures`); `readAs` grants read
      * delegation beyond `actAs` for contracts disclosure doesn't cover.
      */
    def exercise[U](
        actAs: PartyId,
        readAs: List[PartyId],
        update: Update[U],
        disclosures: List[DisclosedContract],
    ): F[U]

    // --- ACS reads for balance checkpoints (port of WalletClientV2) ------------
    // `as` is the party the ledger is read as (contract visibility is per-party on a real ledger);
    // `owner` is whose holdings are counted. Flows usually pass the same party for both.

    def unlockedBalance(as: PartyId, owner: PartyId, instrument: InstrumentId): F[BigDecimal]
    def lockedBalance(as: PartyId, owner: PartyId, instrument: InstrumentId): F[BigDecimal]
    def listHoldingCids(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId,
    ): F[List[Holding.ContractId]]
    def activeAllocations(as: PartyId, owner: PartyId): F[List[Allocation.ContractId]]
