package treasury.it

import cats.effect.{Clock, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import org.scalacheck.{Prop, PropertyM, Test, YetAnotherProperties}

import treasury.PartyId
import treasury.TokenStandardHelpers
import treasury.TokenStandardHelpers.basicAccount
import treasury.registry.{RegistryApi, RegistryApiEvent, Tracer}
import treasury.registry.RegistryApi.EnrichedFactoryChoice
import treasury.registry.service.{LocalRegistryApi, RegistryService}

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate

/** CIP-0112 P5 (live-ledger acceptance): the reference [[LocalRegistryApi]] over a real Canton ACS
  * assembles an allocation-factory context, and that context + disclosures are exercised on the
  * ledger — the ledger must accept it (an `AllocationInstruction` results). This is the one
  * property that needs a running ledger; P1–P4 run purely against the mock in
  * [[treasury.registry.testkit.Cip0112ConformanceMockTest]].
  *
  * The whole property is a single `IO` — the container is a `Resource` acquired *inside* it via
  * `PropertyM.useResource`, and `PropertyM.monadicIO` runs it once at the ScalaCheck edge (no
  * `unsafeRunSync`, no object-init side effects, no manual teardown). `minSuccessfulTests(1)` keeps
  * it to a single boot. Gated on `CANTON_IT=1` — without it no property is registered and Docker is
  * never touched (see [[CantonSmokeSpec]] for the sandbox run recipe).
  */
object CantonConformanceProperties extends YetAnotherProperties("cip0112-canton-acceptance"):

    override def overrideParameters(p: Test.Parameters): Test.Parameters =
        p.withMinSuccessfulTests(1).withWorkers(1)

    private given unitToProp: (Unit => Prop) = _ => Prop.proved

    private final case class CantonEnv(
        impl: RegistryApi[IO],
        arg: AllocationFactory_Allocate,
        exercise: EnrichedFactoryChoice[AllocationFactory_Allocate] => IO[Unit],
    )

    private def mkArg(admin: PartyId, requestedAt: java.time.Instant): AllocationFactory_Allocate =
        TokenStandardHelpers.allocationFactoryAllocate(
          TokenStandardHelpers.settlementInfo(List(admin), "settlement-1"),
          // authorizer = the admin's special (owner-only) account → needs no AccountConfig. Empty
          // transfer-leg sides + `nextIterationFunding = Some(...)` = an iterated (pool) allocation,
          // which `isValidAllocationSpecificationV2` allows and which locks no holdings (so no minted
          // input holdings, and inputHoldingCids is empty — a real ledger rejects synthetic cids).
          TokenStandardHelpers
              .allocationSpec(
                admin,
                admin.basicAccount,
                Nil,
                false,
                Some(Map.empty[String, BigDecimal])
              ),
          requestedAt,
          Nil,
          List(admin),
        )

    /** Boot Canton, connect, create the registry's TokenRules, and build the reference impl — all
      * as a `Resource` so the container/ledger are released when the property finishes.
      */
    private val cantonEnv: Resource[IO, CantonEnv] =
        for
            container <- Resource.make(IO.blocking {
                // The native-ScalaCheck framework runs properties on a work-stealing pool whose threads
                // (and the cats-effect blocking threads spawned under them) carry a context classloader
                // that can't see testcontainers' Docker-strategy classes → "Could not find a valid
                // Docker environment". Restore the classloader that loaded testcontainers before booting.
                Thread.currentThread.setContextClassLoader(
                  classOf[org.testcontainers.DockerClientFactory].getClassLoader
                )
                val c = CantonContainer(); c.start(); c
            })(c => IO.blocking(c.stop()))
            port <- Resource.eval(IO.blocking(container.mappedPort(CantonContainer.LedgerApiPort)))
            admin <- Resource.eval(
              IO.blocking(CantonParties.allocate("localhost", port, List("adminTT2"))("adminTT2"))
            )
            ledger <- Resource.make(IO.blocking(LedgerClientCanton.connect("localhost", port)))(l =>
                IO.blocking(l.close())
            )
            _ <- Resource.eval(ledger.createTokenRules(admin).value.flatMap(IO.fromEither))
            requestedAt <- Resource.eval(Clock[IO].realTimeInstant)
        yield CantonEnv(
          LocalRegistryApi[IO](
            RegistryService(AcsSourceCanton(ledger, admin)),
            Tracer.noop[IO, RegistryApiEvent],
          ),
          mkArg(admin, requestedAt),
          r =>
              ledger
                  .exerciseAllocationFactory(admin, r.factoryCid, r.arg, r.disclosures)
                  .value
                  .flatMap(IO.fromEither),
        )

    if sys.env.get("CANTON_IT").contains("1") then
        property("allocation-factory: assembled context accepted by a live Canton ledger (P5)") =
            PropertyM.monadicIO {
                PropertyM.useResource(cantonEnv) { env =>
                    for
                        r <- PropertyM.run(env.impl.getAllocationFactory(env.arg))
                        _ <- PropertyM.run(env.exercise(r))
                    yield ()
                }
            }
