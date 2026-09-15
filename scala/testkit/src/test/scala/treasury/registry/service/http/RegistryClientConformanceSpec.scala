package treasury.registry.service.http

import java.time.Instant
import java.util.Base64

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import org.http4s.client.Client
import org.http4s.implicits.*

import org.scalatest.funsuite.AsyncFunSuite

import treasury.PartyId
import treasury.TokenStandardHelpers
import treasury.TokenStandardHelpers.basicAccount
import treasury.registry.{RegistryApiEvent, Tracer}
import treasury.registry.RegistryApi.EnrichedFactoryChoice
import treasury.registry.service.*
import treasury.registry.testkit.{Cip0112Conformance, ContextView}

import daml.splice.api.token.allocationv2.Allocation

/** Conformance of the HTTP client [[RegistryBackendHttp]] to the CIP-0112 normative properties,
  * over the two factory endpoints it serves. Reuses [[Cip0112Conformance]]'s check predicates (P1
  * disclosures-complete, P2 factoryId-present, P4 determinism) in an `AsyncIOSpec` rather than the
  * ScalaCheck Properties machinery — ScalaCheck is synchronous, so running an IO impl through the
  * suite proper would need an edge `unsafeRunSync`, which the `.asserting`/`IO[Assertion]` style
  * avoids. The point: the wire round-trip (arg → Daml-JSON → RegistryRoutes → service → response →
  * rebuilt EnrichedFactoryChoice) must preserve the properties the local reference impl satisfies
  * (property-tested across the full surface in `Cip0112ConformanceMockTest`).
  */
class RegistryClientConformanceSpec extends AsyncFunSuite, AsyncIOSpec:

    private val admin = PartyId("admin")
    private val alice = PartyId("alice")
    private val bob = PartyId("bob")

    private def b64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes)
    private def tid(e: String): TemplateId = TemplateId(s"pkg:Splice.Testing.Tokens.TestTokenV2:$e")
    private def basic(p: PartyId): Account = Account(Some(p), None, AccountId(""))

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
        locked = Map(Cid("alloc-1") -> List(holdingDisc)),
      )
    )
    private val client: RegistryBackendHttp[IO] =
        RegistryBackendHttp[IO](
          Client.fromHttpApp(RegistryRoutes[IO](svc).routes.orNotFound),
          uri"http://registry.example",
          Tracer.noop[IO, RegistryApiEvent],
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

    private def view[A](
        ec: EnrichedFactoryChoice[A],
        values: A => java.util.Map[String, ?]
    ): ContextView =
        ContextView.factory(ec.factoryCid, values(ec.arg), ec.disclosures)

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
