package treasury.registry.service

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import org.scalatest.funsuite.AnyFunSuite

import treasury.TokenStandardHelpers
import treasury.registry.{RegistryApi, Tracer}

import daml.splice.api.token.holdingv2.Account
import treasury.registry.RegistryApi.OpenApiChoiceContext

import daml.splice.api.token.transferinstructionv2.TransferInstruction

/** Cluster-D wiring test: the transfer-instruction lifecycle contexts (accept / reject / withdraw —
  * one shared recipe) driven through the reference [[LocalRegistryApi]] over an in-memory
  * [[MockAcsSource]]. Asserts each handler assembles the registry choice context (keys {tokenRules,
  * accountConfigs}) and discloses the instruction's input holdings. `F = Either[Throwable, *]`.
  */
class TransferInstructionContextSpec extends AnyFunSuite:

    private type ErrOr[A] = Either[Throwable, A]

    // Owner-only accounts (Daml `basicAccount`: id ""), matched by the AccountConfigs below.
    private def acct(owner: String): Account =
        new Account(Some(owner).toJava, Option.empty[String].toJava, "")
    private val sender = acct("alice")
    private val receiver = acct("bob")

    // 3-part `pkg:Module:Entity` template ids + base64 blobs: LocalRegistryApi renders every
    // disclosure (parseIdentifier split-on-":" + Base64 decode), including the input holding.
    private val rules: Contract[TokenRulesPayload] =
        Contract(
          Cid("rules"),
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:TokenRules"),
          (),
          Blob("cnJ1bGVz"),
          SynchronizerId("sync-1"),
        )
    private def cfg(cidTag: String, account: Account): Contract[AccountConfigPayload] =
        Contract(
          Cid(cidTag),
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2.AccountConfig:AccountConfig"),
          AccountConfigPayload(account),
          Blob("Y2Zn"),
          SynchronizerId("sync-1"),
        )
    private val configs = List(cfg("cfg-alice", sender), cfg("cfg-bob", receiver))

    private val aHoldingDisclosure: Disclosure =
        Disclosure(
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:Holding"),
          Cid("h1"),
          Blob("aDE="), // base64("h1")
          SynchronizerId("sync-1"),
        )

    private val mock = MockAcsSource[ErrOr](
      rules,
      configs,
      holdings = Map(Cid("h1") -> aHoldingDisclosure),
      transferInstructions = Map(Cid("ti1") -> TransferDetails(sender, receiver, List(Cid("h1")))),
    )
    private val api: RegistryApi[ErrOr] =
        LocalRegistryApi[ErrOr](
          RegistryService(mock),
          Tracer.noop[ErrOr, treasury.registry.RegistryApiEvent],
        )

    private val instr = new TransferInstruction.ContractId("ti1")
    private val meta = TokenStandardHelpers.emptyMetadata

    private def assertContext(result: ErrOr[OpenApiChoiceContext]): Unit =
        val ctx = result.fold(err => fail(s"unexpected error: $err"), identity)
        assert(
          ctx.choiceContext.values.keySet.asScala.toSet ==
              Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)
        )
        assert(ctx.disclosures.map(_.contractId).contains("h1"))

    test("accept context: token rules + account configs, discloses input holding"):
        assertContext(api.getTransferInstructionAcceptContext(instr, meta))

    test("reject context: token rules + account configs, discloses input holding"):
        assertContext(api.getTransferInstructionRejectContext(instr, meta))

    test("withdraw context: token rules + account configs, discloses input holding"):
        assertContext(api.getTransferInstructionWithdrawContext(instr, meta))
