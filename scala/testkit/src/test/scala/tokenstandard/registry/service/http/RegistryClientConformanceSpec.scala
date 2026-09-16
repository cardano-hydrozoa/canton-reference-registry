package tokenstandard.registry.service.http

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Account
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.transferinstructionv2.Transfer
import daml.splice.api.token.transferinstructionv2.TransferFactory_Transfer
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import org.http4s.client.Client
import org.http4s.implicits.*
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers
import tokenstandard.TokenStandardHelpers.basicAccount
import tokenstandard.registry.RegistryApi.EnrichedFactoryChoice
import tokenstandard.registry.RegistryApi.OpenApiChoiceContext
import tokenstandard.registry.service.*
import tokenstandard.registry.testkit.Cip0112Conformance
import tokenstandard.registry.testkit.ContextView

import java.math.BigDecimal as JBigDecimal
import java.time.Instant
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** Conformance of the HTTP client [[RegistryBackendHttp]] to the CIP-0112 normative properties,
  * over all ten endpoints it serves (three factories + seven lifecycle contexts). The factory
  * checks reuse [[Cip0112Conformance]]'s check predicates (P1 disclosures-complete, P2
  * factoryId-present, P4 determinism) in an `AsyncIOSpec` rather than the ScalaCheck Properties
  * machinery — ScalaCheck is synchronous, so running an IO impl through the suite proper would need
  * an edge `unsafeRunSync`, which the `.asserting`/`IO[Assertion]` style avoids. The point: the
  * wire round-trip (arg → Daml-JSON → RegistryRoutes → service → response → rebuilt
  * EnrichedFactoryChoice) must preserve the properties the local reference impl satisfies
  * (property-tested across the full surface in `Cip0112ConformanceMockTest`).
  */
class RegistryClientConformanceSpec extends AsyncFunSuite, AsyncIOSpec:

    private val admin = PartyId("admin")
    private val alice = PartyId("alice")
    private val bob = PartyId("bob")

    private def b64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes)
    private def tid(e: String): TemplateId = TemplateId(s"pkg:Splice.Testing.Tokens.TestTokenV2:$e")
    private def basic(p: PartyId): Account = p.basicAccount

    private val rules: Contract[TokenRulesPayload] =
        Contract(Cid("rules"), tid("TokenRules"), (), Blob(b64("rules")), SynchronizerId("sync-1"))
    private def cfg(tag: String, a: Account): Contract[AccountConfigPayload] =
        Contract(
          Cid(tag),
          tid("AccountConfig"),
          AccountConfigPayload(a),
          Blob(b64(tag)),
          SynchronizerId("sync-1")
        )
    private val holdingDisc =
        Disclosure(tid("Holding"), Cid("h1"), Blob(b64("h1")), SynchronizerId("sync-1"))

    private val svc = RegistryService(
      MockAcsSource[IO](
        rules,
        List(cfg("cfg-alice", basic(alice)), cfg("cfg-bob", basic(bob))),
        holdings = Map(Cid("h1") -> holdingDisc),
        allocations = Map(Cid("alloc-1") -> AllocationDetails(basic(alice), List(Cid("h1")))),
        allocationInstructions = Map(Cid("ai-1") -> basic(alice)),
        transferInstructions =
            Map(Cid("ti-1") -> TransferDetails(basic(alice), basic(bob), List(Cid("h1")))),
      )
    )
    private val client: RegistryBackendHttp[IO] =
        RegistryBackendHttp[IO](
          Client.fromHttpApp(
            RegistryRoutes[IO](
              svc,
              RegistryMetadata.basic("adminTT2", List("X", "Y"))
            ).routes.orNotFound
          ),
          uri"http://registry.example",
        )

    private def allocArg =
        TokenStandardHelpers.allocationFactoryAllocate(
          TokenStandardHelpers.settlementInfo(List(admin), "s"),
          TokenStandardHelpers.allocationSpec(admin, alice.basicAccount, Nil, false, None),
          Instant.EPOCH,
          Nil,
          List(admin),
        )
    private def settleArg =
        val leg = TokenStandardHelpers
            .transferLeg("l", alice.basicAccount, bob.basicAccount, BigDecimal(1), "X")
        TokenStandardHelpers.settlementFactorySettleBatch(
          TokenStandardHelpers.settlementInfo(List(admin), "s"),
          List(leg),
          List(TokenStandardHelpers.nonIteratedAllocation(new Allocation.ContractId("alloc-1"))),
          List(admin),
        )

    private def transferArg =
        val transfer = new Transfer(
          alice.basicAccount,
          bob.basicAccount,
          JBigDecimal.ONE,
          TokenStandardHelpers.instrumentId(admin, "TT2"),
          Instant.EPOCH,
          Instant.EPOCH,
          List.empty[Holding.ContractId].asJava,
          TokenStandardHelpers.emptyMetadata,
        )
        new TransferFactory_Transfer(
          transfer,
          List(admin.value).asJava,
          TokenStandardHelpers.emptyExtraArgs,
        )

    private val meta = TokenStandardHelpers.emptyMetadata

    private def view[A](
        ec: EnrichedFactoryChoice[A],
        values: A => java.util.Map[String, ?]
    ): ContextView =
        ContextView.factory(ec.factoryCid, values(ec.arg), ec.disclosures)

    /** Assert a lifecycle-context round-trip: non-empty assembled context values, and complete (P1)
      * disclosures.
      */
    private def assertContext(oc: OpenApiChoiceContext) =
        assert(!oc.choiceContext.values.isEmpty, "context values non-empty")
        assert(
          oc.disclosures.nonEmpty && oc.disclosures.forall(Cip0112Conformance.disclosuresComplete),
          "P1: disclosures complete",
        )

    private def assertConformant(v1: ContextView, v2: ContextView) =
        assert(
          v1.disclosures.forall(Cip0112Conformance.disclosuresComplete),
          "P1: disclosures complete"
        )
        assert(Cip0112Conformance.factoryIdPresent(v1), "P2: factoryId present")
        assert(
          v1.contextValues == v2.contextValues && v1.factoryId == v2.factoryId,
          "P4: deterministic"
        )

    test("allocation-factory client conforms (P1 disclosures, P2 factoryId, P4 determinism)"):
        for
            r1 <- client.getAllocationFactory(allocArg)
            r2 <- client.getAllocationFactory(allocArg)
        yield assertConformant(
          view(r1, _.extraArgs.context.values),
          view(r2, _.extraArgs.context.values),
        )

    test("settlement-factory client conforms (P1 disclosures, P2 factoryId, P4 determinism)"):
        for
            r1 <- client.getSettlementFactory(settleArg)
            r2 <- client.getSettlementFactory(settleArg)
        yield assertConformant(
          view(r1, _.extraArgs.context.values),
          view(r2, _.extraArgs.context.values),
        )

    // -- the eight other newly-wired endpoints round-trip client -> routes -> service --------------

    test("transfer-factory client conforms (P1 disclosures, P2 factoryId, P4 determinism)"):
        for
            r1 <- client.getTransferFactory(transferArg)
            r2 <- client.getTransferFactory(transferArg)
        yield assertConformant(
          view(r1, _.extraArgs.context.values),
          view(r2, _.extraArgs.context.values),
        )

    test("allocation-withdraw-context client round-trips"):
        client
            .getAllocationWithdrawContext(new Allocation.ContractId("alloc-1"), meta)
            .asserting(assertContext)

    test("allocation-cancel-context client round-trips"):
        client
            .getAllocationCancelContext(new Allocation.ContractId("alloc-1"), meta)
            .asserting(assertContext)

    test("allocation-instruction-withdraw-context client round-trips"):
        client
            .getAllocationInstructionWithdrawContext(
              new AllocationInstruction.ContractId("ai-1"),
              meta
            )
            .asserting(assertContext)

    test("allocation-instruction-accept-context client round-trips"):
        client
            .getAllocationInstructionAcceptContext(
              new AllocationInstruction.ContractId("ai-1"),
              meta
            )
            .asserting(assertContext)

    test("transfer-instruction-accept-context client round-trips"):
        client
            .getTransferInstructionAcceptContext(new TransferInstruction.ContractId("ti-1"), meta)
            .asserting(assertContext)

    test("transfer-instruction-reject-context client round-trips"):
        client
            .getTransferInstructionRejectContext(new TransferInstruction.ContractId("ti-1"), meta)
            .asserting(assertContext)

    test("transfer-instruction-withdraw-context client round-trips"):
        client
            .getTransferInstructionWithdrawContext(new TransferInstruction.ContractId("ti-1"), meta)
            .asserting(assertContext)
