package treasury.registry.service

/** Effectful seam supplying the contracts the pure [[Assemble]] core needs, by reading the registry
  * admin's ACS. Two implementations:
  *   - a mock backed by in-memory maps (fast service tests, Tier-1 harness), and
  *   - a Canton impl issuing Ledger-API ACS queries with `includeCreatedEventBlob` (reuses the
  *     Phase-2 `LedgerClientCanton` machinery).
  *
  * `F` is left unconstrained here; [[RegistryService]] adds the `MonadThrow` constraint at the use
  * site, mirroring the `RegistryApi` decision (traits stay effect-agnostic).
  */
trait AcsSource[F[_]]:

    /** The registry's single `TokenRules` contract (id + blob). Port of Daml `getTokenRules'`,
      * which fails if zero or more than one is present.
      */
    def tokenRules: F[Contract[TokenRulesPayload]]

    /** Every `AccountConfig` contract visible to the admin; [[Assemble]] filters by account. */
    def accountConfigs: F[List[Contract[AccountConfigPayload]]]

    /** Resolve each allocation to its locked `Holding` contracts and return their disclosures. Port
      * of `getLockedTokensForAllocationsD` (per-allocation `holdingCids` -> `queryDisclosure`).
      */
    def lockedHoldingDisclosures(allocationCids: List[Cid]): F[List[Disclosure]]
