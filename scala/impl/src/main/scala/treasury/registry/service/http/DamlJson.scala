package treasury.registry.service.http

import scala.jdk.OptionConverters.*

import cats.syntax.all.*

import io.circe.{Decoder, DecodingFailure, Json}
import io.circe.syntax.*

import treasury.registry.service.*
import treasury.registry.service.CtxValue.{CtxContractId, CtxList}

import daml.splice.api.token.holdingv2.Account

/** The Daml-JSON-encoding-sensitive boundary of the registry service, isolated here so it can be
  * unit-tested on its own and swapped/verified against the canonical Daml Java-codegen encoder when
  * the Canton integration lands (Tier-2 / step 7).
  *
  * Two directions:
  *   - decode: pull the accounts (and allocation cids) the assembly needs out of the incoming
  *     `choiceArguments` — a Daml value (`AllocationFactory_Allocate` /
  *     `SettlementFactory_SettleBatch`) in Daml-JSON-API encoding.
  *   - encode: render a [[ContextBundle]]'s context values as `choiceContextData` — the Daml-JSON
  *     of a `MetadataV1.ChoiceContext` (`{ "values": { key -> AnyValue } }`), the shape the client
  *     feeds back into `extraArgs.context`.
  */
object DamlJson:

    // --- decode: choiceArguments -> accounts ------------------------------------------------------

    /** Daml `HoldingV2.Account` = `{ owner: Optional Party, provider: Optional Party, id: Text }`;
      * Optional Party encodes as JSON null / string.
      */
    private given accountDecoder: Decoder[Account] = Decoder.instance { c =>
        for
            owner <- c.get[Option[String]]("owner")
            provider <- c.get[Option[String]]("provider")
            id <- c.get[String]("id")
        yield new Account(owner.toJava, provider.toJava, id)
    }

    /** `AllocationFactory_Allocate.allocation.authorizer` — the single account of the allocation.
      */
    def allocationAuthorizer(choiceArguments: Json): Either[DecodingFailure, Account] =
        choiceArguments.hcursor.downField("allocation").downField("authorizer").as[Account]

    /** The sender + receiver accounts of every `SettlementFactory_SettleBatch.transferLeg` (the
      * assembly dedups them; only the accounts drive the context).
      */
    def settlementAccounts(choiceArguments: Json): Either[DecodingFailure, List[Account]] =
        choiceArguments.hcursor
            .downField("transferLegs")
            .as[List[Json]]
            .flatMap(_.traverse { leg =>
                for
                    sender <- leg.hcursor.get[Account]("sender")
                    receiver <- leg.hcursor.get[Account]("receiver")
                yield List(sender, receiver)
            }.map(_.flatten))

    /** `SettlementFactory_SettleBatch.allocations[].allocationCid` (a contract id = JSON string).
      */
    def settlementAllocationCids(choiceArguments: Json): Either[DecodingFailure, List[Cid]] =
        choiceArguments.hcursor
            .downField("allocations")
            .as[List[Json]]
            .flatMap(_.traverse(_.hcursor.get[String]("allocationCid").map(Cid(_))))

    // --- encode: ContextBundle values -> choiceContextData ----------------------------------------

    /** One `MetadataV1.AnyValue`, Daml-JSON-encoded as a variant
      * `{ "tag": <ctor>, "value": <arg> }`. The registry only ever emits the contract-id and list
      * constructors.
      */
    private def anyValue(v: CtxValue): Json = v match
        case CtxContractId(cid) =>
            Json.obj("tag" -> "AV_ContractId".asJson, "value" -> cid.value.asJson)
        case CtxList(items) =>
            Json.obj("tag" -> "AV_List".asJson, "value" -> Json.arr(items.map(anyValue)*))

    /** The `choiceContextData` payload: Daml-JSON of `ChoiceContext { values : TextMap AnyValue }`.
      */
    def renderChoiceContextData(values: Map[String, CtxValue]): Json =
        Json.obj("values" -> Json.obj(values.toSeq.map((k, v) => k -> anyValue(v))*))
