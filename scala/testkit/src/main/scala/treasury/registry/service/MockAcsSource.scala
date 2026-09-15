package treasury.registry.service

import cats.Applicative
import cats.syntax.all.*

/** In-memory [[AcsSource]] for service tests, generic over any `Applicative[F]` (never errors — the
  * only registry error, a duplicate account config, arises in [[Assemble]] and is raised by
  * [[RegistryService]]). Used with `Either[Throwable, *]` for the pure wiring test and `IO` for the
  * http4s test.
  *
  * The by-cid maps (`locked`, `holdings`, `allocations`, `allocationInstructions`,
  * `transferInstructions`) stand in for the ledger reads; a lookup miss is a test-setup bug, so it
  * throws directly (the mock has no error channel in `F`).
  */
final class MockAcsSource[F[_]: Applicative](
    rules: Contract[TokenRulesPayload],
    configs: List[Contract[AccountConfigPayload]],
    locked: Map[Cid, List[Disclosure]] = Map.empty[Cid, List[Disclosure]],
    holdings: Map[Cid, Disclosure] = Map.empty[Cid, Disclosure],
    allocations: Map[Cid, AllocationDetails] = Map.empty[Cid, AllocationDetails],
    allocationInstructions: Map[Cid, AllocationInstructionDetails] =
        Map.empty[Cid, AllocationInstructionDetails],
    transferInstructions: Map[Cid, TransferDetails] = Map.empty[Cid, TransferDetails],
) extends AcsSource[F]:

    def tokenRules: F[Contract[TokenRulesPayload]] = rules.pure[F]

    def accountConfigs: F[List[Contract[AccountConfigPayload]]] = configs.pure[F]

    def lockedHoldingDisclosures(allocationCids: List[Cid]): F[List[Disclosure]] =
        allocationCids.flatMap(cid => locked.getOrElse(cid, Nil)).pure[F]

    def holdingDisclosures(holdingCids: List[Cid]): F[List[Disclosure]] =
        holdingCids.map(cid => holdings.getOrElse(cid, sys.error(s"mock: no holding $cid"))).pure[F]

    def allocation(cid: Cid): F[AllocationDetails] =
        allocations.getOrElse(cid, sys.error(s"mock: no allocation $cid")).pure[F]

    def allocationInstruction(cid: Cid): F[AllocationInstructionDetails] =
        allocationInstructions
            .getOrElse(cid, sys.error(s"mock: no allocation instruction $cid"))
            .pure[F]

    def transferInstruction(cid: Cid): F[TransferDetails] =
        transferInstructions
            .getOrElse(cid, sys.error(s"mock: no transfer instruction $cid"))
            .pure[F]
