package treasury.it

import cats.effect.unsafe.implicits.global

import org.scalatest.funsuite.AnyFunSuite

import treasury.registry.service.*
import treasury.registry.service.CtxValue.{CtxContractId, CtxList}

/** Tier-2 (real-ledger) acceptance for the registry service's fetch+assemble path: boot Canton,
  * create the registry's `TokenRules`, then have [[RegistryService]] over [[AcsSourceCanton]]
  * assemble an allocation-factory context and assert it references the REAL on-ledger contract (its
  * actual cid + a non-empty created-event blob read via `includeCreatedEventBlob`). This validates
  * the Canton `AcsSource` and [[Assemble]] against a live ACS — where synthetic Tier-1 tests
  * cannot.
  *
  * Gated on `CANTON_IT=1` (see [[CantonSmokeSpec]] for the sandbox run recipe). This first slice
  * uses no `AccountConfig` (so the authorizer's config is absent → dropped, `accountConfigs` =
  * empty list); account-config coverage and the full exercise-acceptance (the ledger accepting the
  * choice with the assembled context) are the next slice.
  */
class CantonRegistryAcceptanceSpec extends AnyFunSuite:

    test("registry assembles an allocation-factory context from the real Canton ACS"):
        assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests"
        )

        val container = CantonContainer()
        container.start()
        try
            val port = container.mappedPort(CantonContainer.LedgerApiPort)
            val admin = CantonParties.allocate("localhost", port, List("adminTT2"))("adminTT2")
            val ledger = LedgerClientCanton.connect("localhost", port)
            try
                val rulesCid = ledger
                    .createTokenRules(admin)
                    .value
                    .unsafeRunSync()
                    .fold(e => fail(s"createTokenRules failed: $e"), identity)
                val expected = Cid(rulesCid.contractId)

                val svc = RegistryService(AcsSourceCanton(ledger, admin))
                // No AccountConfig on the ledger → this authorizer's config is absent (dropped).
                val bundle =
                    svc.getAllocationFactory(Account(Some(admin), None, "acc-none")).unsafeRunSync()

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
                  d.templateId.contains("TokenRules"),
                  s"unexpected templateId: ${d.templateId}"
                )
                assert(d.createdEventBlob.value.nonEmpty, "created-event blob must be populated")
                assert(d.synchronizerId.nonEmpty, "synchronizer id must be populated")
            finally ledger.close()
        finally container.stop()
