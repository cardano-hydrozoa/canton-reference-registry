package treasury.registry.service

import java.math.BigDecimal as JBigDecimal
import java.time.Instant
import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite

import treasury.PartyId
import treasury.TokenStandardHelpers
import treasury.TokenStandardHelpers.basicAccount
import treasury.registry.{RegistryApiEvent, Tracer}

import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.transferinstructionv2.{Transfer, TransferFactory_Transfer}

/** Wiring test for [[LocalRegistryApi.getTransferFactory]] over the in-memory [[MockAcsSource]]
  * (mirrors [[Cip0112ConformanceMockTest]]'s `Either[Throwable, *]` setup): the sender + receiver
  * of the choice arg drive the assembled context, which is embedded back into `extraArgs.context`.
  * Port of `registryApi_getTransferFactoryV2`.
  */
class TransferFactorySpec extends AnyFunSuite:

    private type ErrOr[A] = Either[Throwable, A]

    private val admin = PartyId("adminTT2")
    private val alice = PartyId("alice")
    private val bob = PartyId("bob")

    private val rules =
        Contract(
          Cid("rules"),
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:TokenRules"),
          (),
          Blob("cnJ1bGVz"), // base64("rules")
          SynchronizerId("sync-1")
        )

    // Two owner-only accounts; no AccountConfigs in the ACS (matchConfig -> None for both).
    private val transfer: Transfer =
        new Transfer(
          alice.basicAccount,
          bob.basicAccount,
          JBigDecimal.ONE,
          TokenStandardHelpers.instrumentId(admin, "TT2"),
          Instant.EPOCH,
          Instant.EPOCH,
          List.empty[Holding.ContractId].asJava,
          TokenStandardHelpers.emptyMetadata,
        )

    private val arg: TransferFactory_Transfer =
        new TransferFactory_Transfer(
          transfer,
          List(admin.value).asJava,
          TokenStandardHelpers.emptyExtraArgs,
        )

    test("getTransferFactory embeds the assembled context and returns the rules cid as factoryCid"):
        val svc = RegistryService(MockAcsSource[ErrOr](rules, Nil))
        val api = LocalRegistryApi[ErrOr](svc, Tracer.noop[ErrOr, RegistryApiEvent])

        val enriched =
            api.getTransferFactory(arg).fold(err => fail(s"unexpected error: $err"), identity)

        assert(
          enriched.arg.extraArgs.context.values.keySet.asScala.toSet ==
              Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)
        )
        assert(enriched.factoryCid == rules.cid.value)
