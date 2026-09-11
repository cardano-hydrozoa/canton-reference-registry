package treasury.registry

import cats.Applicative
import cats.syntax.all.*

import treasury.registry.RegistryBackend.{EnrichedFactoryChoice, Error}

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch

/** Pure stub registry (Phase 1): echoes each request into a factory-choice bundle with an opaque
  * factory cid and no disclosures. It carries no ledger knowledge — the in-memory
  * [[treasury.ledger.InMemoryLedger]] interprets the returned `arg`. Being pure, it is polymorphic
  * in `F` (any `Applicative`), so it runs in the ledger's `State` effect with no runtime; mirrors
  * CardanoBackendMock's role as a network-free stand-in.
  */
final class RegistryBackendStub[F[_]: Applicative](
    protected val tracer: Tracer[F, RegistryBackendEvent]
) extends RegistryBackend[F]:

    private def bundle[A](kind: String, arg: A): F[Either[Error, EnrichedFactoryChoice[A]]] =
        tracer
            .trace(RegistryBackendEvent.StubReturnedCannedChoice(kind))
            .as(Right(EnrichedFactoryChoice(s"stub-factory/$kind", arg, Nil)))

    def getAllocationFactory(arg: AllocationFactory_Allocate) = bundle("allocation", arg)
    def getSettlementFactory(arg: SettlementFactory_SettleBatch) = bundle("settlement", arg)
