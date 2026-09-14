package treasury.it

import cats.effect.{Clock, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import org.scalacheck.{Prop, PropertyM, Test, YetAnotherProperties}

import treasury.PartyId
import treasury.TokenStandardHelpers
import treasury.TokenStandardHelpers.basicAccount
import treasury.registry.{RegistryApi, RegistryApiEvent, Tracer}
import treasury.registry.service.{LocalRegistryApi, RegistryService}

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.holdingv2.Holding

/** CIP-0112 P5 (live-ledger acceptance): the reference [[LocalRegistryApi]] over a real Canton ACS
  * assembles an allocation-factory context, and that context + disclosures are exercised on the
  * ledger — the ledger must accept it (an `AllocationInstruction` results). This is the one
  * property that needs a running ledger; P1–P4 run purely against the mock in
  * [[treasury.registry.testkit.Cip0112ConformanceMockTest]].
  *
  * Two allocation shapes are exercised against one booted ledger:
  *   1. Funded single-shot deposit — 100 X is minted to the admin, then a sender-side allocation
  *      locks that real holding (non-empty leg + `inputHoldingCids`). This is the case that
  *      actually validates the assembled `AnyValue`/`ChoiceContext` encoding end-to-end: the
  *      ledger must resolve the context contracts and lock the referenced holding.
  *   2. Fundless iterated pool — empty legs + `nextIterationFunding = Some(...)`, which locks no
  *      holdings. The degenerate context shape, kept as a regression on the empty-`accountConfigs`
  *      path.
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
        ledger: LedgerClientCanton,
        admin: PartyId,
        holder: PartyId,
        requestedAt: java.time.Instant,
    ):
        /** getAllocationFactory (the code under test) → exercise on the ledger as `actAs`; `Unit`
          * iff the ledger accepts.
          */
        def allocate(actAs: PartyId, arg: AllocationFactory_Allocate): IO[Unit] =
            for
                r <- impl.getAllocationFactory(arg)
                _ <- ledger
                    .exerciseAllocationFactory(actAs, r.factoryCid, r.arg, r.disclosures)
                    .value
                    .flatMap(IO.fromEither)
            yield ()

        /** Mint into the `holder` party (a non-admin regular account, as the reference does — the
          * mint's TIA_Accept needs both accounts' parties, which degenerates if receiver == admin).
          */
        def mint(instrument: String, amount: BigDecimal): IO[List[Holding.ContractId]] =
            ledger.mint(admin, holder, instrument, amount, requestedAt).value.flatMap(IO.fromEither)

    /** A funded single-shot deposit: `holder` (owner of the minted `inputs`) locks them behind a
      * sender-side leg paying the admin. Authorizer is the holder's basic account (no AccountConfig);
      * `admin` is the registry/instrument admin recorded in the spec.
      */
    private def fundedArg(
        admin: PartyId,
        holder: PartyId,
        inputs: List[Holding.ContractId],
        requestedAt: java.time.Instant,
    ): AllocationFactory_Allocate =
        val leg = TokenStandardHelpers
            .transferLeg("deposit", holder.basicAccount, admin.basicAccount, BigDecimal(100), "X")
        TokenStandardHelpers.allocationFactoryAllocate(
          TokenStandardHelpers.settlementInfo(List(admin), "settlement-funded"),
          TokenStandardHelpers
              .allocationSpec(admin, holder.basicAccount, List(TokenStandardHelpers.senderSide(leg)), false, None),
          requestedAt,
          inputs,
          List(holder),
        )

    /** A fundless iterated (pool) allocation: no legs, `nextIterationFunding = Some(...)` — locks no
      * holdings (so `inputHoldingCids` is empty; a real ledger rejects synthetic cids).
      */
    private def iteratedArg(admin: PartyId, requestedAt: java.time.Instant): AllocationFactory_Allocate =
        TokenStandardHelpers.allocationFactoryAllocate(
          TokenStandardHelpers.settlementInfo(List(admin), "settlement-iterated"),
          TokenStandardHelpers
              .allocationSpec(admin, admin.basicAccount, Nil, false, Some(Map.empty[String, BigDecimal])),
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
            parties <- Resource.eval(
              IO.blocking(CantonParties.allocate("localhost", port, List("adminTT2", "holderTT2")))
            )
            admin = parties("adminTT2")
            holder = parties("holderTT2")
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
          ledger,
          admin,
          holder,
          requestedAt,
        )

    if sys.env.get("CANTON_IT").contains("1") then
        property("allocation-factory: assembled contexts accepted by a live Canton ledger (P5)") =
            PropertyM.monadicIO {
                PropertyM.useResource(cantonEnv) { env =>
                    for
                        holdings <- PropertyM.run(env.mint("X", BigDecimal(100)))
                        _ <- PropertyM.run(
                          env.allocate(
                            env.holder,
                            fundedArg(env.admin, env.holder, holdings, env.requestedAt),
                          )
                        )
                        _ <- PropertyM.run(env.allocate(env.admin, iteratedArg(env.admin, env.requestedAt)))
                    yield ()
                }
            }
