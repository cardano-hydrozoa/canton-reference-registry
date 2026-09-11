package treasury.registry

import cats.effect.IO
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import treasury.registry.RegistryBackend.EnrichedFactoryChoice
import treasury.registry.RegistryBackend.Error

/** Pure stub registry (Phase 1): echoes each request into a factory-choice bundle with an opaque
  * factory cid and no disclosures. It carries no ledger knowledge — the in-memory
  * [[treasury.ledger.InMemoryLedger]] interprets the returned `arg`. Mirrors CardanoBackendMock's
  * role: pure, test-only, stands in for the real backend without a network.
  */
final class RegistryBackendStub(
    protected val tracer: Tracer[IO, RegistryBackendEvent] = Tracer.noop[IO, RegistryBackendEvent]
) extends RegistryBackend[IO]:

    private def bundle[A](kind: String, arg: A): IO[Either[Error, EnrichedFactoryChoice[A]]] =
        tracer
            .trace(RegistryBackendEvent.StubReturnedCannedChoice(kind))
            .as(Right(EnrichedFactoryChoice(s"stub-factory/$kind", arg, Nil)))

    def getAllocationFactory(arg: AllocationFactory_Allocate) = bundle("allocation", arg)
    def getSettlementFactory(arg: SettlementFactory_SettleBatch) = bundle("settlement", arg)
