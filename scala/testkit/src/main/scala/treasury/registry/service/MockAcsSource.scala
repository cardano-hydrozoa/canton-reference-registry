package treasury.registry.service

import cats.Applicative
import cats.syntax.all.*

/** In-memory [[AcsSource]] for service tests, generic over any `Applicative[F]` (never errors — the
  * only registry error, a duplicate account config, arises in [[Assemble]] and is raised by
  * [[RegistryService]]). Used with `Either[Throwable, *]` for the pure wiring test and `IO` for the
  * http4s test. `locked` maps an allocation cid to the disclosures of its locked holdings.
  */
final class MockAcsSource[F[_]: Applicative](
    rules: Contract[TokenRulesPayload],
    configs: List[Contract[AccountConfigPayload]],
    locked: Map[Cid, List[Disclosure]] = Map.empty[Cid, List[Disclosure]],
) extends AcsSource[F]:

    def tokenRules: F[Contract[TokenRulesPayload]] = rules.pure[F]

    def accountConfigs: F[List[Contract[AccountConfigPayload]]] = configs.pure[F]

    def lockedHoldingDisclosures(allocationCids: List[Cid]): F[List[Disclosure]] =
        allocationCids.flatMap(cid => locked.getOrElse(cid, Nil)).pure[F]
