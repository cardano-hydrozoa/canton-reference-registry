package treasury.it

import cats.effect.IO
import cats.effect.Resource
import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import daml.splice.api.token.holdingv2.Account
import org.scalatest.funsuite.AsyncFunSuite
import treasury.registry.service.*
import treasury.registry.service.CtxValue.CtxContractId
import treasury.registry.service.CtxValue.CtxList

import scala.jdk.OptionConverters.*

/** Tier-2 (real-ledger) acceptance for the registry service's fetch+assemble path: boot Canton,
  * create the registry's `TokenRules`, then have [[RegistryService]] over [[AcsSourceCanton]]
  * assemble an allocation-factory context and assert it references the REAL on-ledger contract (its
  * actual cid + a non-empty created-event blob read via `includeCreatedEventBlob`). This validates
  * the Canton `AcsSource` and [[Assemble]] against a live ACS — where synthetic Tier-1 tests
  * cannot.
  *
  * Runs in `IO` via `AsyncIOSpec` (the test returns `IO[Assertion]`), with the container and ledger
  * client held as `Resource`s so bracketing tears them down — no `unsafeRunSync`, no `finally`.
  * Gated on `CANTON_IT=1` (see [[CantonSmokeSpec]] for the sandbox run recipe). This first slice
  * uses no `AccountConfig` (the authorizer's config is absent → dropped, `accountConfigs` = empty
  * list); account-config coverage and the full exercise-acceptance are the next slice.
  */
class CantonRegistryAcceptanceSpec extends AsyncFunSuite, AsyncIOSpec:

    /** The Canton container: started on acquire, stopped on release. */
    private val cantonContainer: Resource[IO, GenericContainer] =
        Resource.make(IO.blocking { val c = CantonContainer(); c.start(); c })(c =>
            IO.blocking(c.stop())
        )

    /** A connected Ledger-API client, closed on release. */
    private def ledgerClient(host: String, port: Int): Resource[IO, LedgerClientCanton] =
        Resource.make(IO.blocking(LedgerClientCanton.connect(host, port)))(l =>
            IO.blocking(l.close())
        )

    test("registry assembles an allocation-factory context from the real Canton ACS"):
        assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests"
        )

        cantonContainer
            .use { container =>
                IO.blocking(container.mappedPort(CantonContainer.LedgerApiPort)).flatMap { port =>
                    ledgerClient("localhost", port).use { ledger =>
                        for
                            admin <- IO.blocking(
                              CantonParties.allocate("localhost", port, List("adminTT2"))(
                                "adminTT2"
                              )
                            )
                            rulesCid <- ledger
                                .createTokenRules(admin)
                                .value
                                .flatMap(e => IO.fromEither(e))
                            bundle <- RegistryService(AcsSourceCanton(ledger, admin))
                                .getAllocationFactory(
                                  new Account(
                                    Some(admin.value).toJava,
                                    Option.empty[String].toJava,
                                    "acc-none"
                                  )
                                )
                        yield (Cid(rulesCid.contractId), bundle)
                    }
                }
            }
            .asserting { case (expected, bundle) =>
                assert(
                  bundle.factoryId == expected,
                  "factory contract must be the real TokenRules cid"
                )
                assert(
                  bundle.values.keySet == Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)
                )
                assert(bundle.values(ContextKeys.tokenRules) == CtxContractId(expected))
                assert(bundle.values(ContextKeys.accountConfigs) == CtxList(Nil))
                assert(bundle.disclosures.map(_.contractId) == List(expected))
                val d = bundle.disclosures.head
                assert(
                  d.templateId.value.contains("TokenRules"),
                  s"unexpected templateId: ${d.templateId.value}"
                )
                assert(d.createdEventBlob.value.nonEmpty, "created-event blob must be populated")
                assert(d.synchronizerId.value.nonEmpty, "synchronizer id must be populated")
            }
