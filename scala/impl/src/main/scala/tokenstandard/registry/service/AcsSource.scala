package tokenstandard.registry.service

import cats.Monad
import cats.syntax.all.*
import daml.splice.api.token.holdingv2.Account

/** Effectful seam supplying the contracts the pure [[Assemble]] core needs, by reading the registry
  * admin's ACS. Two implementations:
  *   - a mock backed by in-memory maps (fast service tests, Tier-1 harness), and
  *   - a Canton impl issuing Ledger-API ACS queries with `includeCreatedEventBlob` (reuses
  *     `LedgerClientCanton`).
  *
  * `F` is left unconstrained here; [[RegistryService]] adds the `MonadThrow` constraint at the use
  * site, mirroring the `RegistryApi` decision (traits stay effect-agnostic).
  *
  * Miss semantics, binding on every implementation: a requested cid that does not resolve to an
  * active contract of the expected template/interface raises
  * `RegistryApi.Error.ContractNotFound(cid)` into `F` — bulk reads never return silent partial
  * results. A cid that resolves but has nothing to disclose (e.g. an allocation locking no
  * holdings) is NOT a miss; it contributes an empty result. This ports the Daml helpers' per-cid
  * `queryInterfaceContractId` / `queryDisclosure'`, which abort on a missing contract.
  *
  * The by-cid view reads return read-projections ([[AllocationDetails]] / [[TransferDetails]] /
  * `Account`) — only the fields the assembly branches on — never the full interface view.
  */
trait AcsSource[F[_]]:

    /** The registry's single `TokenRules` contract (id + blob). Port of Daml `getTokenRules'`:
      * raises into `F` if zero or more than one is present.
      */
    def tokenRules: F[Contract[TokenRulesPayload]]

    /** Every `AccountConfig` contract visible to the admin; [[Assemble]] filters by account. A
      * total read — there is no miss case.
      */
    def accountConfigs: F[List[Contract[AccountConfigPayload]]]

    /** Resolve each allocation to its locked `Holding` contracts and return their disclosures. Port
      * of `getLockedTokensForAllocationsD` (per-allocation `holdingCids` -> `queryDisclosure`).
      *
      * The default derives it from the other two primitives: one [[allocation]] read per cid — so
      * an unknown allocation cid raises `ContractNotFound` — then [[holdingDisclosures]] over the
      * deduplicated union of their `holdingCids`. Overrides may batch the reads, but must commute
      * with this derivation: the same ACS yields the same disclosures, and the same misses raise
      * the same errors.
      */
    def lockedHoldingDisclosures(allocationCids: List[Cid])(using Monad[F]): F[List[Disclosure]] =
        for
            details <- allocationCids.traverse(allocation)
            discs <- holdingDisclosures(details.flatMap(_.holdingCids).distinct)
        yield discs

    /** Disclose the given `Holding`/`Token` contracts by id — the reusable primitive behind
      * [[lockedHoldingDisclosures]] and the transfer-instruction handlers' `inputHoldingCids`.
      * Ports `queryDisclosure' @Token` over a list of cids: every cid must resolve, else
      * `ContractNotFound`.
      */
    def holdingDisclosures(holdingCids: List[Cid]): F[List[Disclosure]]

    /** Read an `Allocation` interface view by cid — `ContractNotFound` on a miss — as a
      * read-projection: its authorizer account and the holdings it locked, nothing else. Ports
      * `queryInterfaceContractId @Allocation` + `.allocation.authorizer` / `.holdingCids`.
      */
    def allocation(cid: Cid): F[AllocationDetails]

    /** Read an `AllocationInstruction` interface view by cid — `ContractNotFound` on a miss — as a
      * read-projection: its authorizer account only. Ports
      * `queryInterfaceContractId @AllocationInstruction` + `.allocation.authorizer`.
      */
    def allocationInstruction(cid: Cid): F[Account]

    /** Read a `TransferInstruction` interface view by cid — `ContractNotFound` on a miss — as a
      * read-projection: its sender/receiver accounts and the `inputHoldingCids` it locked, nothing
      * else. Ports `queryInterfaceContractId @TransferInstruction` +
      * `.transfer.{sender,receiver,inputHoldingCids}`.
      */
    def transferInstruction(cid: Cid): F[TransferDetails]
