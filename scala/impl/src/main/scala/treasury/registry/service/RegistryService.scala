package treasury.registry.service

import cats.MonadThrow
import cats.syntax.all.*

/** The F-level registry: fetch contracts via [[AcsSource]], assemble the choice context via
  * [[Assemble]]. Exposes the two off-ledger endpoints the treasury / cross-registry-swap flow needs
  * (`getAllocationFactory`, `getSettlementFactory`); the remaining `RegistryApiV2` surface
  * (transfer factory, allocation/transfer-instruction lifecycle contexts) is added when a flow
  * needs it.
  *
  * `MonadThrow` is required at this use site (not on [[AcsSource]]) so pure [[AssembleError]]s can
  * be raised into `F`, matching how `TreasuryFlow` constrains its `F`.
  */
final class RegistryService[F[_]](src: AcsSource[F])(using F: MonadThrow[F]):

    /** Locate the factory, assemble the context for `AllocationFactory_Allocate`: accounts =
      * [authorizer]. See `registryApi_getAllocationFactoryV2`.
      */
    def getAllocationFactory(authorizer: Account): F[ContextBundle] =
        for
            rules <- src.tokenRules
            configs <- src.accountConfigs
            bundle <- F.fromEither(Assemble.allocationFactory(rules, configs, authorizer))
        yield bundle

    /** Locate the factory, assemble the context for `SettlementFactory_SettleBatch`: accounts =
      * dedup'd leg parties, plus locked-holding disclosures for each allocation. See
      * `registryApi_getSettlementFactoryV2`.
      */
    def getSettlementFactory(legs: List[TransferLeg], allocationCids: List[Cid]): F[ContextBundle] =
        for
            rules <- src.tokenRules
            configs <- src.accountConfigs
            locked <- src.lockedHoldingDisclosures(allocationCids)
            bundle <- F.fromEither(Assemble.settlementFactory(rules, configs, legs, locked))
        yield bundle

    /** The shared core of every factory / lifecycle handler: read the rules + configs, then run the
      * pure [[Assemble.contextBundle]] for `accounts` with any `extraDisclosures` appended. The
      * transfer factory (accounts from the choice arg) and the lifecycle handlers below all build
      * on this.
      */
    def choiceContext(
        accounts: List[Account],
        extraDisclosures: List[Disclosure] = Nil,
    ): F[ContextBundle] =
        for
            rules <- src.tokenRules
            configs <- src.accountConfigs
            bundle <- F.fromEither(
              Assemble.contextBundle(rules, configs, accounts, extraDisclosures)
            )
        yield bundle

    // -- Lifecycle choice contexts (fan-out sites; each reads a view via `src`, then `choiceContext`)

    /** Cluster B — allocation lifecycle. Context for withdraw (`includeLocked = false`) or cancel
      * (`includeLocked = true`) of allocation `cid`: assemble for its authorizer, and for cancel
      * additionally disclose the allocation's locked holdings. Port of `getWithdrawContextV2` /
      * `getCancelContextV2`.
      */
    def allocationContext(cid: Cid, includeLocked: Boolean): F[ContextBundle] =
        for
            details <- src.allocation(cid)
            extra <-
                if includeLocked then src.holdingDisclosures(details.holdingCids)
                else List.empty[Disclosure].pure[F]
            bundle <- choiceContext(List(details.authorizer), extra)
        yield bundle

    /** Cluster C — allocation-instruction lifecycle. Context for withdraw/accept of instruction
      * `cid`: assemble for its authorizer. Port of `getAllocationInstructionContextV2`.
      */
    def allocationInstructionContext(cid: Cid): F[ContextBundle] =
        for
            details <- src.allocationInstruction(cid)
            bundle <- choiceContext(List(details.authorizer))
        yield bundle

    /** Cluster D — transfer-instruction lifecycle. Context for accept/reject/withdraw of
      * instruction `cid`: assemble for its sender + receiver, disclosing the instruction's
      * `inputHoldingCids`. Port of `getTransferOfferContextV2`.
      */
    def transferInstructionContext(cid: Cid): F[ContextBundle] =
        for
            details <- src.transferInstruction(cid)
            disc <- src.holdingDisclosures(details.inputHoldingCids)
            bundle <- choiceContext(List(details.sender, details.receiver), disc)
        yield bundle
