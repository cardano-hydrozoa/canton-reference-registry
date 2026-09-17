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
import tokenstandard.registry.RegistryApi
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

    private def backend(
        svc: RegistryService[IO],
        metadata: RegistryMetadata = RegistryMetadata.basic("adminTT2", Nil),
    ): RegistryBackendHttp[IO] =
        RegistryBackendHttp[IO](
          Client.fromHttpApp(RegistryRoutes[IO](svc, metadata).routes.orNotFound),
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
        val svc = RegistryService(
          MockAcsSource[IO](
            rules,
            List(cfg("cfg-alice", basic(alice)), cfg("cfg-bob", basic(bob))),
            holdings = Map(Cid("locked-1") -> holdingDisc("locked-1")),
            allocations =
                Map(Cid("alloc-1") -> AllocationDetails(basic(alice), List(Cid("locked-1")))),
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

    test("metadata endpoints round-trip: info, paged instrument list, lookup, 404"):
        val svc = RegistryService(MockAcsSource[IO](rules, Nil))
        val api = backend(svc, RegistryMetadata.basic("adminTT2", List("X", "Y", "Z")))
        for
            info <- api.getRegistryInfo
            page1 <- api.listInstruments(pageSize = Some(2), pageToken = None)
            page2 <- api.listInstruments(pageSize = Some(2), pageToken = page1.nextPageToken)
            x <- api.getInstrument("X")
            missing <- api.getInstrument("nope").attempt
        yield
            assert(info.adminId == "adminTT2")
            assert(info.supportedApis.contains("splice-api-token-metadata-v1"))
            assert(page1.instruments.map(_.id) == Seq("X", "Y"))
            assert(page1.nextPageToken.contains("Y"))
            assert(page2.instruments.map(_.id) == Seq("Z"))
            assert(page2.nextPageToken.isEmpty)
            assert(x.id == "X" && x.decimals == 10)
            assert(missing.left.exists {
                case RegistryApi.Error.InstrumentNotFound("nope") => true
                case _                                            => false
            })
