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

    /** Submit a [[Submission]] — one or more commands committed as ONE atomic transaction — and
      * return its decoded result. Atomic composition happens inside the `Submission` applicative
      * (`Submission.exercise(a) *> Submission.exercise(b)`); sequencing `submit` calls in `F` is
      * the non-atomic composition (separate transactions). `actAs` are the parties jointly
      * authorizing the submission (multi-party choices, e.g. an operator co-signing with a registry
      * admin, need more than one). `disclosures` are the registry-owned contracts the submitter
      * must attach (from `EnrichedFactoryChoice.disclosures`); `readAs` grants read delegation
      * beyond `actAs` for contracts disclosure doesn't cover.
      *
      * Implementations may support only exercise commands — the standard's flows are entirely
      * factory-mediated — and reject other updates (creates) by raising
      * `RegistryApi.Error.NotImplemented`.
      */
    def submit[A](
        actAs: List[PartyId],
        readAs: List[PartyId],
        submission: Submission[A],
        disclosures: List[DisclosedContract],
    ): F[A]

    /** Single-exercise sugar for [[submit]]: submit one codegen [[Update]] — the command plus its
      * typed-result continuation, as produced by the generated `exercise*` methods (e.g.
      * `new AllocationFactory.ContractId(cid).exerciseAllocationFactory_Allocate(arg)`) — and
      * return the decoded choice result.
      */
    def exercise[U](
        actAs: List[PartyId],
        readAs: List[PartyId],
        update: Update[U],
        disclosures: List[DisclosedContract],
    ): F[U] =
        submit(actAs, readAs, Submission.exercise(update), disclosures)

    // --- ACS reads for balance checkpoints (port of WalletClientV2) ------------
    // `as` is the party the ledger is read as (contract visibility is per-party on a real ledger);
    // `owner` is whose holdings are counted. Flows usually pass the same party for both.
    // These reads are TOTAL: absence is a value, never a miss — the balance of an unheld
    // instrument is 0, a listing of nothing is Nil. (The opposite convention from the registry
    // side's by-cid reads, which raise ContractNotFound.)

    def unlockedBalance(as: PartyId, owner: PartyId, instrument: InstrumentId): F[BigDecimal]
    def lockedBalance(as: PartyId, owner: PartyId, instrument: InstrumentId): F[BigDecimal]
    def listHoldingCids(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId,
    ): F[List[Holding.ContractId]]
    def activeAllocations(as: PartyId, owner: PartyId): F[List[Allocation.ContractId]]
