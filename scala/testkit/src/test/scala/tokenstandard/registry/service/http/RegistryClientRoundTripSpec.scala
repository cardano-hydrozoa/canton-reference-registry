package tokenstandard.registry.service.http

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Account
import org.http4s.client.Client
import org.http4s.implicits.*
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers
import tokenstandard.TokenStandardHelpers.basicAccount
import tokenstandard.registry.service.*

import java.time.Instant
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** End-to-end round-trip of the HTTP registry pair with no socket: [[RegistryBackendHttp]] (client)
  * → an `http4s` `Client.fromHttpApp` over [[RegistryRoutes]] (server) → a [[MockAcsSource]]-backed
  * [[RegistryService]] → back. Exercises the client's full path: encode the codegen arg to
  * Daml-JSON, POST, decode the returned `ChoiceContext` (via the codegen `fromJson`) + disclosures,
  * and rebuild the [[tokenstandard.registry.RegistryApi.EnrichedFactoryChoice]]. `factoryCid`, the
  * embedded context keys, and the disclosure set must survive the trip.
  *
  * Fixtures use 3-part `pkg:Module:Entity` template ids + base64 blobs because the *client* renders
  * every disclosure (`parseIdentifier` split-on-":" + Base64 decode) — unlike [[RegistryHttpSpec]],
  * which only inspects the wire DTOs.
  */
class RegistryClientRoundTripSpec extends AsyncFunSuite, AsyncIOSpec:

    private val admin = PartyId("admin")
    private val alice = PartyId("alice")
    private val bob = PartyId("bob")

    private def b64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes)
    private def tid(entity: String): TemplateId =
        TemplateId(s"pkg:Splice.Testing.Tokens.TestTokenV2:$entity")

    // The basic-account domain account the server extracts from the encoded arg (owner-only, id "").
    private def basic(p: PartyId): Account = p.basicAccount

    private val rules: Contract[TokenRulesPayload] =
        Contract(Cid("rules"), tid("TokenRules"), (), Blob(b64("rules")), SynchronizerId("sync-1"))
    private def cfg(cidTag: String, account: Account): Contract[AccountConfigPayload] =
        Contract(
          Cid(cidTag),
          tid("AccountConfig"),
          AccountConfigPayload(account),
          Blob(b64(cidTag)),
          SynchronizerId("sync-1")
        )
    private def holdingDisc(cidTag: String): Disclosure =
        Disclosure(tid("Holding"), Cid(cidTag), Blob(b64(cidTag)), SynchronizerId("sync-1"))

    private def backend(svc: RegistryService[IO]): RegistryBackendHttp[IO] =
        RegistryBackendHttp[IO](
          Client.fromHttpApp(RegistryRoutes[IO](svc).routes.orNotFound),
          uri"http://registry.example",
        )

    test("getAllocationFactory round-trips through HTTP: factoryCid + context keys + disclosures"):
        val svc = RegistryService(MockAcsSource[IO](rules, List(cfg("cfg-alice", basic(alice)))))
        val arg = TokenStandardHelpers.allocationFactoryAllocate(
          TokenStandardHelpers.settlementInfo(List(admin), "s1"),
          TokenStandardHelpers.allocationSpec(admin, alice.basicAccount, Nil, false, None),
          Instant.EPOCH,
          Nil,
          List(admin),
        )

        backend(svc).getAllocationFactory(arg).asserting { ec =>
            assert(ec.factoryCid == "rules")
            assert(
              ec.arg.extraArgs.context.values.keySet.asScala.toSet ==
                  Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)
            )
            assert(ec.disclosures.map(_.contractId) == List("rules", "cfg-alice"))
        }

    test("getSettlementFactory round-trips: legs + allocations thread into disclosures"):
        val locked = Map(Cid("alloc-1") -> List(holdingDisc("locked-1")))
        val svc = RegistryService(
          MockAcsSource[IO](
            rules,
            List(cfg("cfg-alice", basic(alice)), cfg("cfg-bob", basic(bob))),
            locked
          )
        )
        val leg = TokenStandardHelpers
            .transferLeg("l", alice.basicAccount, bob.basicAccount, BigDecimal(1), "X")
        val arg = TokenStandardHelpers.settlementFactorySettleBatch(
          TokenStandardHelpers.settlementInfo(List(admin), "s1"),
          List(leg),
          List(TokenStandardHelpers.nonIteratedAllocation(new Allocation.ContractId("alloc-1"))),
          List(admin),
        )

        backend(svc).getSettlementFactory(arg).asserting { ec =>
            assert(ec.factoryCid == "rules")
            assert(
              ec.disclosures.map(_.contractId) == List("rules", "cfg-alice", "cfg-bob", "locked-1")
            )
        }
