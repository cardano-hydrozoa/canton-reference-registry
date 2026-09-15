package tokenstandard.registry.service

import daml.splice.api.token.holdingv2.Account

/** Effectful seam supplying the contracts the pure [[Assemble]] core needs, by reading the registry
  * admin's ACS. Two implementations:
  *   - a mock backed by in-memory maps (fast service tests, Tier-1 harness), and
  *   - a Canton impl issuing Ledger-API ACS queries with `includeCreatedEventBlob` (reuses
  *     `LedgerClientCanton`).
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

    /** Disclose the given `Holding`/`Token` contracts by id — the reusable primitive behind
      * [[lockedHoldingDisclosures]] and the transfer-instruction handlers' `inputHoldingCids`.
      * Ports `queryDisclosure' @Token` over a list of cids.
      */
    def holdingDisclosures(holdingCids: List[Cid]): F[List[Disclosure]]

    /** Read an `Allocation` interface view by cid: its authorizer account and the holdings it
      * locked. Ports `queryInterfaceContractId @Allocation` + `.allocation.authorizer` /
      * `.holdingCids`.
      */
    def allocation(cid: Cid): F[AllocationDetails]

    /** Read an `AllocationInstruction` interface view by cid: its authorizer account. Ports
      * `queryInterfaceContractId @AllocationInstruction` + `.allocation.authorizer`.
      */
    def allocationInstruction(cid: Cid): F[Account]

    /** Read a `TransferInstruction` interface view by cid: its sender/receiver accounts and the
      * `inputHoldingCids` it locked. Ports `queryInterfaceContractId @TransferInstruction` +
      * `.transfer.{sender,receiver,inputHoldingCids}`.
      */
    def transferInstruction(cid: Cid): F[TransferDetails]
