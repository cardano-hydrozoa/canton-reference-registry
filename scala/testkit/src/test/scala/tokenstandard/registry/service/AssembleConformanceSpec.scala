package tokenstandard.registry.service

import daml.splice.api.token.holdingv2.Account
import org.scalatest.funsuite.AnyFunSuite
import tokenstandard.registry.service.CtxValue.CtxContractId
import tokenstandard.registry.service.CtxValue.CtxList

import scala.jdk.OptionConverters.*

/** Tier-1 conformance: structural properties of the pure [[Assemble]] core, with the spec derived
  * by reading Splice `TestTokenV2_RegistryV2.getAccountMapAndTokenRulesC` (and the two factory
  * methods). Uses synthetic cids/blobs so it is deterministic and needs no Canton — Tier-2 (a
  * golden diff vs the live Daml-script registry on one participant) is a separate, gated
  * integration spec.
  *
  * Reference (hyperledger-labs/splice, token-standard/.../TestTokenV2_RegistryV2.daml):
  * {{{
  *   getAccountMapAndTokenRulesC registry accounts = do
  *     (tokenRulesCid, _) <- getTokenRules registry
  *     tokenRulesD <- queryDisclosure' tokenRulesCid
  *     accountConfigs <- catOptionals <$> forA accounts (getAccountConfig registry)
  *     let accountConfigsValue = AV_List (map (AV_ContractId . fst) accountConfigs)
  *     let choiceContext = ChoiceContext with values = fromList
  *           [ (accountConfigsContextKey, accountConfigsValue)
  *           , (tokenRulesContextKey, AV_ContractId tokenRulesCid) ]
  *     pure (tokenRulesCid, choiceContext, tokenRulesD <> foldMap snd accountConfigs)
  * }}}
  */
class AssembleConformanceSpec extends AnyFunSuite:

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

    private val rules: Contract[TokenRulesPayload] =
        Contract(
          Cid("rules"),
          TemplateId("TestTokenV2:TokenRules"),
          (),
          Blob("blob-rules"),
          SynchronizerId("sync-1")
        )

    private val alice = acct("acc-alice", "alice")
    private val bob = acct("acc-bob", "bob")
    private val carol = acct("acc-carol", "carol")

    private def right[A](e: Either[AssembleError, A]): A =
        e.fold(err => fail(s"expected Right, got Left($err)"), identity)

    test("factoryId and tokenRules context value are the TokenRules cid"):
        val b = right(Assemble.contextBundle(rules, Nil, Nil))
        assert(b.factoryId == Cid("rules"))
        assert(b.values(ContextKeys.tokenRules) == CtxContractId(Cid("rules")))

    test("both context keys are always present"):
        val b = right(Assemble.contextBundle(rules, Nil, Nil))
        assert(b.values.keySet == Set(ContextKeys.tokenRules, ContextKeys.accountConfigs))

    test("accountConfigs value lists matched config cids in requested-account order"):
        val configs = List(cfg("cfgB", bob), cfg("cfgA", alice)) // ACS order differs from request
        val b = right(Assemble.contextBundle(rules, configs, List(alice, bob)))
        assert(
          b.values(ContextKeys.accountConfigs) ==
              CtxList(List(CtxContractId(Cid("cfgA")), CtxContractId(Cid("cfgB"))))
        )

    test("accounts without a config are dropped (catOptionals), order preserved"):
        // bob has no config; alice and carol do.
        val configs = List(cfg("cfgA", alice), cfg("cfgC", carol))
        val b = right(Assemble.contextBundle(rules, configs, List(alice, bob, carol)))
        assert(
          b.values(ContextKeys.accountConfigs) ==
              CtxList(List(CtxContractId(Cid("cfgA")), CtxContractId(Cid("cfgC"))))
        )

    test("disclosures = rules, then matched configs in account order (by contract id)"):
        val configs = List(cfg("cfgA", alice), cfg("cfgC", carol))
        val b = right(Assemble.contextBundle(rules, configs, List(alice, carol)))
        assert(b.disclosures.map(_.contractId) == List(Cid("rules"), Cid("cfgA"), Cid("cfgC")))
        // the actual blob rides through on each disclosure
        assert(
          b.disclosures.map(_.createdEventBlob) == List(
            Blob("blob-rules"),
            Blob("blob-cfgA"),
            Blob("blob-cfgC")
          )
        )

    test("extraDisclosures (locked holdings) append after the config disclosures"):
        val configs = List(cfg("cfgA", alice))
        val extra = List(disc("locked-1"), disc("locked-2"))
        val b = right(Assemble.contextBundle(rules, configs, List(alice), extra))
        assert(
          b.disclosures.map(_.contractId) == List(
            Cid("rules"),
            Cid("cfgA"),
            Cid("locked-1"),
            Cid("locked-2")
          )
        )

    test("duplicate config for a requested account is an error (getAccountConfig' abort)"):
        val configs = List(cfg("cfgA", alice), cfg("cfgA2", alice))
        val res = Assemble.contextBundle(rules, configs, List(alice))
        assert(res == Left(AssembleError.DuplicateAccountConfig(alice)))

    test("a duplicate config for an UNrequested account is ignored"):
        val configs = List(cfg("cfgB", bob), cfg("cfgB2", bob), cfg("cfgA", alice))
        val b = right(Assemble.contextBundle(rules, configs, List(alice)))
        assert(b.values(ContextKeys.accountConfigs) == CtxList(List(CtxContractId(Cid("cfgA")))))

    test("allocationFactory uses [authorizer] as the account set"):
        val configs = List(cfg("cfgA", alice))
        val viaFactory = right(Assemble.allocationFactory(rules, configs, alice))
        val viaShared = right(Assemble.contextBundle(rules, configs, List(alice)))
        assert(viaFactory == viaShared)

    test("settlementFactory dedups leg parties (first-occurrence order) and appends locked blobs"):
        // legs: alice->bob, bob->carol  =>  [alice, bob, bob, carol] dedup => [alice, bob, carol]
        val accounts = List(alice, bob, bob, carol)
        val configs = List(cfg("cfgA", alice), cfg("cfgB", bob), cfg("cfgC", carol))
        val locked = List(disc("locked-1"))
        val b = right(Assemble.settlementFactory(rules, configs, accounts, locked))
        assert(
          b.values(ContextKeys.accountConfigs) ==
              CtxList(
                List(
                  CtxContractId(Cid("cfgA")),
                  CtxContractId(Cid("cfgB")),
                  CtxContractId(Cid("cfgC"))
                )
              )
        )
        assert(
          b.disclosures.map(_.contractId) ==
              List(Cid("rules"), Cid("cfgA"), Cid("cfgB"), Cid("cfgC"), Cid("locked-1"))
        )
