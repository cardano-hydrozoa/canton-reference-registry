package tokenstandard.registry.service.http

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import daml.splice.api.token.holdingv2.Account
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.registry.openapi.alloc.models as al
import tokenstandard.registry.openapi.allocinstr.models as ai
import tokenstandard.registry.openapi.transfer.models as tr
import tokenstandard.registry.service.*

import scala.jdk.OptionConverters.*

/** In-process end-to-end test of the HTTP layer: a real http4s request routed through
  * [[RegistryRoutes]] over a [[MockAcsSource]]-backed [[RegistryService]], driven by an `http4s`
  * `Client.fromHttpApp` (no socket, no Canton). Exercises request decode -> account extraction ->
  * service -> response encode -> client decode with the generated wire DTOs.
  */
class RegistryHttpSpec extends AsyncFunSuite, AsyncIOSpec:

    private def acct(id: String, owner: String): Account =
        new Account(Some(owner).toJava, Option.empty[String].toJava, id)
    private def acctJson(a: Account): Json =
        Json.obj(
          "owner" -> a.owner.toScala.asJson,
          "provider" -> a.provider.toScala.asJson,
          "id" -> a.id.asJson
        )
    private def cfg(cidTag: String, account: Account): Contract[AccountConfigPayload] =
        Contract(
          Cid(cidTag),
          TemplateId("TestTokenV2:AccountConfig"),
          AccountConfigPayload(account),
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

    private def client(svc: RegistryService[IO]): Client[IO] =
        Client.fromHttpApp(
          RegistryRoutes[IO](svc, RegistryMetadata.basic("adminTT2", Nil)).routes.orNotFound
        )

    // Guards the build.sbt post-processing that strips openapi-generator's rogue Json codecs: a
    // free-form choiceArguments object must round-trip as embedded JSON, not an escaped string.
    test("GetFactoryRequest keeps choiceArguments as embedded JSON (not a stringified object)"):
        IO {
            val ca = Json.obj("allocation" -> Json.obj("authorizer" -> acctJson(alice)))
            val decoded = ai.GetFactoryRequest(choiceArguments = ca).asJson.as[ai.GetFactoryRequest]
            assert(decoded.map(_.choiceArguments) == Right(ca))
        }

    test("allocation-factory endpoint round-trips request → context bundle"):
        val svc = RegistryService(MockAcsSource[IO](rules, List(cfg("cfgA", alice))))
        val ca = Json.obj("allocation" -> Json.obj("authorizer" -> acctJson(alice)))
        val req =
            Request[IO](Method.POST, uri"/registry/allocation-instruction/v2/allocation-factory")
                .withEntity(ai.GetFactoryRequest(choiceArguments = ca))

        client(svc).expect[ai.FactoryWithChoiceContext](req).asserting { resp =>
            assert(resp.factoryId == "rules")
            assert(resp.choiceContext.disclosedContracts.map(_.contractId) == List("rules", "cfgA"))
            val keys =
                resp.choiceContext.choiceContextData.hcursor.downField("values").keys.map(_.toSet)
            assert(keys.contains(Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)))
        }

    test("settlement-factory endpoint threads legs + allocations into disclosures"):
        val locked = Map(
          Cid("alloc-1") -> List(
            Disclosure(
              TemplateId("TestTokenV2:Holding"),
              Cid("locked-1"),
              Blob("b"),
              SynchronizerId("sync-1")
            )
          )
        )
        val svc = RegistryService(
          MockAcsSource[IO](rules, List(cfg("cfgA", alice), cfg("cfgB", bob)), locked)
        )
        val ca = Json.obj(
          "transferLegs" -> Json.arr(
            Json.obj("sender" -> acctJson(alice), "receiver" -> acctJson(bob))
          ),
          "allocations" -> Json.arr(Json.obj("allocationCid" -> "alloc-1".asJson)),
        )
        val req = Request[IO](Method.POST, uri"/registry/allocation/v2/settlement-factory")
            .withEntity(al.GetFactoryRequest(choiceArguments = ca))

        client(svc).expect[al.FactoryWithChoiceContext](req).asserting { resp =>
            assert(resp.factoryId == "rules")
            assert(
              resp.choiceContext.disclosedContracts
                  .map(_.contractId) == List("rules", "cfgA", "cfgB", "locked-1")
            )
        }

    test("transfer-factory serves the transfer schema: required transferKind (offer vs self)"):
        val svc = RegistryService(MockAcsSource[IO](rules, Nil))
        def req(sender: Account, receiver: Account) =
            Request[IO](Method.POST, uri"/registry/transfer-instruction/v2/transfer-factory")
                .withEntity(
                  tr.GetFactoryRequest(choiceArguments =
                      Json.obj(
                        "transfer" -> Json.obj(
                          "sender" -> acctJson(sender),
                          "receiver" -> acctJson(receiver),
                        )
                      )
                  )
                )
        for
            offer <- client(svc).expect[tr.TransferFactoryWithChoiceContext](req(alice, bob))
            self <- client(svc).expect[tr.TransferFactoryWithChoiceContext](req(alice, alice))
        yield
            assert(offer.factoryId == "rules")
            assert(offer.transferKind == tr.TransferFactoryWithChoiceContextTransferKind.Offer)
            assert(self.transferKind == tr.TransferFactoryWithChoiceContextTransferKind.Self)
