package treasury.registry.service.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import io.circe.Json
import io.circe.syntax.*

import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.implicits.*

import org.scalatest.funsuite.AnyFunSuite

import treasury.registry.service.*

import treasury.registry.openapi.alloc.models as al
import treasury.registry.openapi.allocinstr.models as ai

/** In-process end-to-end test of the HTTP layer: a real http4s request routed through
  * [[RegistryRoutes]] over a [[MockAcsSource]]-backed [[RegistryService]], driven by an `http4s`
  * `Client.fromHttpApp` (no socket, no Canton). Exercises request decode -> account extraction ->
  * service -> response encode -> client decode with the generated wire DTOs.
  */
class RegistryHttpSpec extends AnyFunSuite:

    private def acct(id: String, owner: String): Account = Account(Some(owner), None, id)
    private def acctJson(a: Account): Json =
        Json.obj("owner" -> a.owner.asJson, "provider" -> a.provider.asJson, "id" -> a.id.asJson)
    private def cfg(cidTag: String, account: Account): Contract[AccountConfigPayload] =
        Contract(
          Cid(cidTag),
          "TestTokenV2:AccountConfig",
          AccountConfigPayload(account),
          Blob(s"blob-$cidTag"),
          "sync-1"
        )

    private val rules =
        Contract(Cid("rules"), "TestTokenV2:TokenRules", (), Blob("blob-rules"), "sync-1")
    private val alice = acct("acc-alice", "alice")
    private val bob = acct("acc-bob", "bob")

    private def client(svc: RegistryService[IO]): Client[IO] =
        Client.fromHttpApp(RegistryRoutes[IO](svc).routes.orNotFound)

    // Guards the build.sbt post-processing that strips openapi-generator's rogue Json codecs: a
    // free-form choiceArguments object must round-trip as embedded JSON, not an escaped string.
    test("GetFactoryRequest keeps choiceArguments as embedded JSON (not a stringified object)"):
        val ca = Json.obj("allocation" -> Json.obj("authorizer" -> acctJson(alice)))
        val decoded = ai.GetFactoryRequest(choiceArguments = ca).asJson.as[ai.GetFactoryRequest]
        assert(decoded.map(_.choiceArguments) == Right(ca))

    test("allocation-factory endpoint round-trips request → context bundle"):
        val svc = RegistryService(MockAcsSource[IO](rules, List(cfg("cfgA", alice))))
        val ca = Json.obj("allocation" -> Json.obj("authorizer" -> acctJson(alice)))
        val req =
            Request[IO](Method.POST, uri"/registry/allocation-instruction/v2/allocation-factory")
                .withEntity(ai.GetFactoryRequest(choiceArguments = ca))

        val resp = client(svc).expect[ai.FactoryWithChoiceContext](req).unsafeRunSync()

        assert(resp.factoryId == "rules")
        assert(resp.choiceContext.disclosedContracts.map(_.contractId) == List("rules", "cfgA"))
        val keys =
            resp.choiceContext.choiceContextData.hcursor.downField("values").keys.map(_.toSet)
        assert(keys.contains(Set(ContextKeys.tokenRules, ContextKeys.accountConfigs)))

    test("settlement-factory endpoint threads legs + allocations into disclosures"):
        val locked = Map(
          Cid("alloc-1") -> List(
            Disclosure("TestTokenV2:Holding", Cid("locked-1"), Blob("b"), "sync-1")
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

        val resp = client(svc).expect[al.FactoryWithChoiceContext](req).unsafeRunSync()

        assert(resp.factoryId == "rules")
        assert(
          resp.choiceContext.disclosedContracts
              .map(_.contractId) == List("rules", "cfgA", "cfgB", "locked-1")
        )
