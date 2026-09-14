package treasury.registry.service.http

import io.circe.Json
import io.circe.syntax.*

import org.scalatest.funsuite.AnyFunSuite

import treasury.registry.service.*
import treasury.registry.service.CtxValue.{CtxContractId, CtxList}

/** Unit tests for the Daml-JSON boundary, kept separate from the HTTP plumbing so the
  * encoding-sensitive logic is pinned on its own. The exact AnyValue/ChoiceContext shapes asserted
  * here are re-verified against the canonical Daml Java-codegen encoder at the Canton integration.
  */
class DamlJsonSpec extends AnyFunSuite:

    private def acctJson(owner: String, id: String): Json =
        Json.obj("owner" -> owner.asJson, "provider" -> Json.Null, "id" -> id.asJson)

    test("renderChoiceContextData wraps values as a ChoiceContext record of AnyValue variants"):
        val values = Map(
          ContextKeys.tokenRules -> CtxContractId(Cid("rules")),
          ContextKeys.accountConfigs -> CtxList(List(CtxContractId(Cid("cfgA")))),
        )
        val vals = DamlJson.renderChoiceContextData(values).hcursor.downField("values")
        assert(
          vals.downField(ContextKeys.tokenRules)
              .focus
              .contains(
                Json.obj("tag" -> "AV_ContractId".asJson, "value" -> "rules".asJson)
              )
        )
        assert(
          vals.downField(ContextKeys.accountConfigs)
              .focus
              .contains(
                Json.obj(
                  "tag" -> "AV_List".asJson,
                  "value" -> Json
                      .arr(Json.obj("tag" -> "AV_ContractId".asJson, "value" -> "cfgA".asJson)),
                )
              )
        )

    test("allocationAuthorizer extracts the account from Daml-JSON choiceArguments"):
        val ca = Json.obj("allocation" -> Json.obj("authorizer" -> acctJson("alice", "acc-alice")))
        assert(
          DamlJson.allocationAuthorizer(ca) == Right(
            Account(Some("alice"), None, AccountId("acc-alice"))
          )
        )

    test("settlementLegs and settlementAllocationCids extract legs and allocation cids"):
        val ca = Json.obj(
          "transferLegs" -> Json.arr(
            Json.obj(
              "sender" -> acctJson("alice", "acc-alice"),
              "receiver" -> acctJson("bob", "acc-bob")
            )
          ),
          "allocations" -> Json.arr(
            Json.obj("allocationCid" -> "alloc-1".asJson),
            Json.obj("allocationCid" -> "alloc-2".asJson),
          ),
        )
        assert(
          DamlJson.settlementLegs(ca) == Right(
            List(
              TransferLeg(
                Account(Some("alice"), None, AccountId("acc-alice")),
                Account(Some("bob"), None, AccountId("acc-bob"))
              )
            )
          )
        )
        assert(DamlJson.settlementAllocationCids(ca) == Right(List(Cid("alloc-1"), Cid("alloc-2"))))
