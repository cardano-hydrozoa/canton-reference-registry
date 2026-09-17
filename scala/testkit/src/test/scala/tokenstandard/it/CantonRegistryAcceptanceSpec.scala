package tokenstandard.it

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import daml.splice.api.token.holdingv2.Account
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.registry.service.*
import tokenstandard.registry.service.CtxValue.CtxContractId
import tokenstandard.registry.service.CtxValue.CtxList

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
  * Gated on `CANTON_IT=1` (see [[CantonSmokeSpec]] for the sandbox run recipe). Uses no
  * `AccountConfig` (the authorizer's config is absent → dropped, `accountConfigs` = empty list);
  * the full exercise-acceptance lives in [[CantonConformanceProperties]] (P5).
  */
class CantonRegistryAcceptanceSpec extends AsyncFunSuite, AsyncIOSpec, CantonItFixture:

    test("registry assembles an allocation-factory context from the real Canton ACS"):
        requireCantonIt()

        cantonContainer
            .use { container =>
                portOf(container).flatMap { port =>
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
