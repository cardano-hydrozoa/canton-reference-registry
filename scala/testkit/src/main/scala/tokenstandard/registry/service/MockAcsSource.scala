package tokenstandard.registry.service

import cats.Applicative
import cats.syntax.all.*
import daml.splice.api.token.holdingv2.Account
import tokenstandard.registry.RegistryApi

/** In-memory [[AcsSource]] for service tests, generic over any `Applicative[F]` (never errors — the
  * only registry error, a duplicate account config, arises in [[Assemble]] and is raised by
  * [[RegistryService]]). Used with `Either[Throwable, *]` for the pure wiring test and `IO` for the
  * http4s test.
  *
  * The by-cid maps (`holdings`, `allocations`, `allocationInstructions`, `transferInstructions`)
  * stand in for the ledger reads; a lookup miss throws `Error.ContractNotFound` directly (the mock
  * has no error channel in `F`) — the trait's miss contract, and the same error the Canton source
  * raises, so the HTTP layer's 404 mapping is exercised by mock-backed tests too.
  * `lockedHoldingDisclosures` is the trait default, so settlement fixtures populate `allocations`
  * (cid -> holding cids) and `holdings` (cid -> disclosure).
  */
final class MockAcsSource[F[_]: Applicative](
    rules: Contract[TokenRulesPayload],
    configs: List[Contract[AccountConfigPayload]],
    holdings: Map[Cid, Disclosure] = Map.empty[Cid, Disclosure],
    allocations: Map[Cid, AllocationDetails] = Map.empty[Cid, AllocationDetails],
    allocationInstructions: Map[Cid, Account] = Map.empty[Cid, Account],
    transferInstructions: Map[Cid, TransferDetails] = Map.empty[Cid, TransferDetails],
) extends AcsSource[F]:

    private def miss(cid: Cid): Nothing = throw RegistryApi.Error.ContractNotFound(cid.value)

    def tokenRules: F[Contract[TokenRulesPayload]] = rules.pure[F]

    def accountConfigs: F[List[Contract[AccountConfigPayload]]] = configs.pure[F]

    def holdingDisclosures(holdingCids: List[Cid]): F[List[Disclosure]] =
        holdingCids.map(cid => holdings.getOrElse(cid, miss(cid))).pure[F]

    def allocation(cid: Cid): F[AllocationDetails] =
        allocations.getOrElse(cid, miss(cid)).pure[F]

    def allocationInstruction(cid: Cid): F[Account] =
        allocationInstructions
            .getOrElse(cid, miss(cid))
            .pure[F]

    def transferInstruction(cid: Cid): F[TransferDetails] =
        transferInstructions
            .getOrElse(cid, miss(cid))
            .pure[F]
