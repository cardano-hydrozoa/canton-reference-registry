package treasury.registry.service

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import org.scalatest.funsuite.AnyFunSuite

import treasury.TokenStandardHelpers
import treasury.registry.{RegistryApi, RegistryApiEvent, Tracer}

import daml.splice.api.token.holdingv2.Account

import daml.splice.api.token.allocationinstructionv2.AllocationInstruction

/** Cluster C wiring test: both allocation-instruction lifecycle contexts (withdraw + accept)
  * resolve through [[RegistryService.allocationInstructionContext]] to a context assembled for the
  * instruction's authorizer only — no locked-holding disclosures (unlike the allocation/settlement
  * paths). `F = Either[Throwable, *]`.
  */
class AllocationInstructionContextSpec extends AnyFunSuite:

    private type ErrOr[A] = Either[Throwable, A]

    // basicAccount = owner-only, id "" (matches the config below and Daml's `basicAccount`).
    private val basicAuthorizer: Account =
        new Account(Some("alice").toJava, Option.empty[String].toJava, "")

    // 3-part `pkg:Module:Entity` template ids + base64 blobs — LocalRegistryApi renders every
    // disclosure via parseIdentifier (split-on-":") + Base64 decode.
    private val rules: Contract[TokenRulesPayload] =
        Contract(
          Cid("rules"),
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:TokenRules"),
          (),
          Blob("cnJ1bGVz"), // base64("rules")
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

    private val impl: RegistryApi[ErrOr] =
        LocalRegistryApi[ErrOr](
          RegistryService(
            MockAcsSource[ErrOr](
              rules,
              configs,
              allocationInstructions = Map(Cid("ai1") -> basicAuthorizer),
            )
          ),
          Tracer.noop[ErrOr, RegistryApiEvent],
        )

    private val meta = TokenStandardHelpers.emptyMetadata
    private val instructionCid = new AllocationInstruction.ContractId("ai1")

    for (label, run) <- List(
          "withdraw" -> (() => impl.getAllocationInstructionWithdrawContext(instructionCid, meta)),
          "accept" -> (() => impl.getAllocationInstructionAcceptContext(instructionCid, meta)),
        )
    do
        test(s"getAllocationInstruction${label.capitalize}Context: authorizer-only, no holdings"):
            val ctx = run().fold(err => fail(s"unexpected error: $err"), identity)
            // Exactly the two registry context keys.
            assert(
              ctx.choiceContext.values.keySet.asScala.toSet ==
                  Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)
            )
            // Disclosures are rules + the authorizer's config only (no locked/holding disclosures).
            assert(ctx.disclosures.map(_.contractId) == List("rules", "cfg-alice"))
