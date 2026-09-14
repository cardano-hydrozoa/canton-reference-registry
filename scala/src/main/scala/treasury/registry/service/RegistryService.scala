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
