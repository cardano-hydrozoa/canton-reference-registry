package treasury.registry

import cats.Applicative
import cats.syntax.all.*
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import treasury.registry.RegistryApi.EnrichedFactoryChoice

/** Pure stub registry (Phase 1): echoes each request into a factory-choice bundle with an opaque
  * factory cid and no disclosures. It carries no ledger knowledge — the in-memory
  * [[treasury.ledger.InMemoryLedger]] interprets the returned `arg`. Being pure, it is polymorphic
  * in `F` (any `Applicative`), so it runs in the ledger's `StateT` effect with no runtime; mirrors
  * CardanoBackendMock's role as a network-free stand-in.
  */
final class RegistryApiStub[F[_]: Applicative](
    protected val tracer: Tracer[F, RegistryApiEvent]
) extends RegistryApi[F]:

    // The stub is a pure `Applicative` (no error channel), so an unsupported endpoint throws directly;
    // only the two factory choices below are exercised by the flow, so this is never reached.
    protected def notImplemented[A](endpoint: String): F[A] =
        throw RegistryApi.Error.NotImplemented(endpoint)

    private def bundle[A](kind: String, arg: A): F[EnrichedFactoryChoice[A]] =
        tracer
            .trace(RegistryApiEvent.StubReturnedCannedChoice(kind))
            .as(EnrichedFactoryChoice(s"stub-factory/$kind", arg, Nil))

    override def getAllocationFactory(arg: AllocationFactory_Allocate) = bundle("allocation", arg)
    override def getSettlementFactory(arg: SettlementFactory_SettleBatch) =
        bundle("settlement", arg)
