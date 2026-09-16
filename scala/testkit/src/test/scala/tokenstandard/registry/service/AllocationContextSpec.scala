package tokenstandard.registry.service

import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Account
import org.scalatest.funsuite.AnyFunSuite
import tokenstandard.TokenStandardHelpers

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Wiring test: [[RegistryService.allocationContext]] via [[LocalRegistryApi]] over an in-memory
  * [[MockAcsSource]] carrying one allocation. Withdraw assembles for the authorizer with no
  * locked-holding disclosure; cancel adds the allocation's locked holding (`includeLocked`).
  * `F = Either[Throwable, *]`.
  */
class AllocationContextSpec extends AnyFunSuite:

    private type ErrOr[A] = Either[Throwable, A]

    private val basicAuthorizer: Account =
        new Account(Some("alice").toJava, Option.empty[String].toJava, "acc-alice")

    // 3-part `pkg:Module:Entity` template ids, as LocalRegistryApi.parseIdentifier expects.
    private val rules: Contract[TokenRulesPayload] =
        Contract(
          Cid("rules"),
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:TokenRules"),
          (),
          Blob("cnVsZXM="), // base64("rules")
          SynchronizerId("sync-1"),
        )
    private val configs: List[Contract[AccountConfigPayload]] =
        List(
          Contract(
            Cid("cfg-alice"),
            TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2.AccountConfig:AccountConfig"),
            AccountConfigPayload(basicAuthorizer),
            Blob("Y2Zn"), // base64("cfg")
            SynchronizerId("sync-1"),
          )
        )
    private val aHoldingDisclosure: Disclosure =
        Disclosure(
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:Holding"),
          Cid("h1"),
          Blob("aG9sZA=="), // base64("hold")
          SynchronizerId("sync-1"),
        )

    private val src = MockAcsSource[ErrOr](
      rules,
      configs,
      allocations = Map(Cid("a1") -> AllocationDetails(basicAuthorizer, List(Cid("h1")))),
      holdings = Map(Cid("h1") -> aHoldingDisclosure),
    )
    private val impl =
        LocalRegistryApi[ErrOr](RegistryService(src), RegistryMetadata.basic("adminTT2", Nil))

    private val expectedKeys = Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)
    private val meta = TokenStandardHelpers.emptyMetadata
    private val allocationCid = new Allocation.ContractId("a1")

    test("withdraw context: {tokenRules, accountConfigs}, no locked-holding disclosure"):
        val ctx = impl
            .getAllocationWithdrawContext(allocationCid, meta)
            .fold(err => fail(s"unexpected error: $err"), identity)
        assert(ctx.choiceContext.values.keySet.asScala.toSet == expectedKeys)
        assert(!ctx.disclosures.map(_.contractId).contains("h1"))

    test("cancel context: same keys plus the h1 locked-holding disclosure"):
        val ctx = impl
            .getAllocationCancelContext(allocationCid, meta)
            .fold(err => fail(s"unexpected error: $err"), identity)
        assert(ctx.choiceContext.values.keySet.asScala.toSet == expectedKeys)
        assert(ctx.disclosures.map(_.contractId).contains("h1"))
