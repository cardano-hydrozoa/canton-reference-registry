package treasury.registry.service

import daml.splice.api.token.holdingv2.Account
import org.scalatest.funsuite.AnyFunSuite
import treasury.registry.service.CtxValue.CtxContractId
import treasury.registry.service.CtxValue.CtxList

import scala.jdk.OptionConverters.*

/** Wiring test for [[RegistryService]] over the in-memory [[MockAcsSource]]: the F-level glue
  * fetches the ACS, delegates to the (separately conformance-tested) [[Assemble]] core, resolves
  * locked holdings per allocation, and raises [[AssembleError]] into `F`.
  * `F = Either[Throwable, *]`.
  */
class RegistryServiceSpec extends AnyFunSuite:

    private type ErrOr[A] =
        Either[Throwable, A] // has MonadThrow; keeps the wiring test synchronous

    private def acct(id: String, owner: String): Account =
        new Account(Some(owner).toJava, None.toJava, id)
    private def cfg(cidTag: String, account: Account): Contract[AccountConfigPayload] =
        Contract(
          Cid(cidTag),
          TemplateId("TestTokenV2:AccountConfig"),
          AccountConfigPayload(account),
          Blob(s"blob-$cidTag"),
          SynchronizerId("sync-1")
        )
    private def disc(cidTag: String): Disclosure =
        Disclosure(
          TemplateId("TestTokenV2:Holding"),
          Cid(cidTag),
          Blob(s"blob-$cidTag"),
          SynchronizerId("sync-1")
        )

    private val rules =
        Contract(
          Cid("rules"),
          TemplateId("TestTokenV2:TokenRules"),
          (),
          Blob("blob-rules"),
          SynchronizerId("sync-1")
        )
    private val alice = acct("acc-alice", "alice")
    private val bob = acct("acc-bob", "bob")

    test("getAllocationFactory fetches then assembles (matches Assemble.allocationFactory)"):
        val configs = List(cfg("cfgA", alice))
        val svc = RegistryService(MockAcsSource[ErrOr](rules, configs))
        assert(svc.getAllocationFactory(alice) == Assemble.allocationFactory(rules, configs, alice))

    test("getSettlementFactory resolves locked holdings by allocation cid and appends them"):
        val configs = List(cfg("cfgA", alice), cfg("cfgB", bob))
        val locked =
            Map(Cid("alloc-1") -> List(disc("locked-1")), Cid("alloc-2") -> List(disc("locked-2")))
        val svc = RegistryService(MockAcsSource[ErrOr](rules, configs, locked))
        val accounts = List(alice, bob)

        val bundle = svc
            .getSettlementFactory(accounts, List(Cid("alloc-1"), Cid("alloc-2")))
            .fold(err => fail(s"unexpected error: $err"), identity)

        assert(
          bundle.values(ContextKeys.accountConfigs) ==
              CtxList(List(CtxContractId(Cid("cfgA")), CtxContractId(Cid("cfgB"))))
        )
        // rules, config disclosures (account order), then locked disclosures (allocation-cid order).
        assert(
          bundle.disclosures.map(_.contractId) ==
              List(Cid("rules"), Cid("cfgA"), Cid("cfgB"), Cid("locked-1"), Cid("locked-2"))
        )

    test("a pure AssembleError is raised into F (Left)"):
        val configs = List(cfg("cfgA", alice), cfg("cfgA2", alice)) // duplicate for alice
        val svc = RegistryService(MockAcsSource[ErrOr](rules, configs))
        assert(svc.getAllocationFactory(alice) == Left(AssembleError.DuplicateAccountConfig(alice)))
