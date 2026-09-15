package tokenstandard.registry.service

import cats.MonadThrow
import cats.syntax.all.*
import com.daml.ledger.javaapi.data.DisclosedContract
import com.daml.ledger.javaapi.data.Identifier
import com.google.protobuf.ByteString
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.metadatav1.AnyContract
import daml.splice.api.token.metadatav1.AnyValue
import daml.splice.api.token.metadatav1.ChoiceContext
import daml.splice.api.token.metadatav1.ExtraArgs
import daml.splice.api.token.metadatav1.Metadata
import daml.splice.api.token.metadatav1.anyvalue.AV_ContractId
import daml.splice.api.token.metadatav1.anyvalue.AV_List
import daml.splice.api.token.transferinstructionv2.TransferFactory_Transfer
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.EnrichedFactoryChoice
import tokenstandard.registry.RegistryApi.OpenApiChoiceContext

import java.util.Base64
import scala.jdk.CollectionConverters.*

/** The reference [[RegistryApi]] implementation: the CIP-0112 registry surface over a
  * [[RegistryService]]. It reads the domain params off the codegen choice argument, runs the pure
  * assembly (via the service), then converts the resulting [[ContextBundle]] back into codegen
  * types — embedding the assembled `ChoiceContext` into the argument's `extraArgs.context` and
  * rendering each [[Disclosure]] as a wire `DisclosedContract` — so the returned
  * [[EnrichedFactoryChoice]] is ready to exercise. Implements the full CIP-0112 surface — both
  * factories and all seven lifecycle contexts.
  */
final class LocalRegistryApi[F[_]](
    service: RegistryService[F]
)(using F: MonadThrow[F])
    extends RegistryApi[F]:

    override def getAllocationFactory(
        arg: AllocationFactory_Allocate
    ): F[EnrichedFactoryChoice[AllocationFactory_Allocate]] =
        service.getAllocationFactory(arg.allocation.authorizer).map { bundle =>
            val withCtx = new AllocationFactory_Allocate(
              arg.settlement,
              arg.allocation,
              arg.requestedAt,
              arg.inputHoldingCids,
              embedContext(arg.extraArgs, bundle),
              arg.actors,
            )
            EnrichedFactoryChoice(bundle.factoryId.value, withCtx, disclosuresOf(bundle))
        }

    override def getSettlementFactory(
        arg: SettlementFactory_SettleBatch
    ): F[EnrichedFactoryChoice[SettlementFactory_SettleBatch]] =
        val accounts =
            arg.transferLegs.asScala.toList.flatMap(l => List(l.sender, l.receiver))
        val allocationCids =
            arg.allocations.asScala.toList.map(fa => Cid(fa.allocationCid.contractId))
        service.getSettlementFactory(accounts, allocationCids).map { bundle =>
            val withCtx = new SettlementFactory_SettleBatch(
              arg.settlement,
              arg.transferLegs,
              arg.allocations,
              arg.actors,
              embedContext(arg.extraArgs, bundle),
            )
            EnrichedFactoryChoice(bundle.factoryId.value, withCtx, disclosuresOf(bundle))
        }

    // -- Transfer factory (fan-out: cluster A) -----------------------------------------------------

    /** Cluster A hole. Mirror [[getAllocationFactory]]: read sender + receiver off `arg.transfer`,
      * assemble via `service.choiceContext(List(sender, receiver))`, rebuild
      * `TransferFactory_Transfer` with `embedContext(arg.extraArgs, bundle)`, and return the
      * `EnrichedFactoryChoice` (`bundle.factoryId.value`, the rebuilt arg,
      * `disclosuresOf(bundle)`). Port of `registryApi_getTransferFactoryV2`.
      */
    override def getTransferFactory(
        arg: TransferFactory_Transfer
    ): F[EnrichedFactoryChoice[TransferFactory_Transfer]] =
        service.choiceContext(List(arg.transfer.sender, arg.transfer.receiver)).map { bundle =>
            val withCtx = new TransferFactory_Transfer(
              arg.transfer,
              arg.actors,
              embedContext(arg.extraArgs, bundle),
            )
            EnrichedFactoryChoice(bundle.factoryId.value, withCtx, disclosuresOf(bundle))
        }

    // -- Lifecycle choice contexts (pre-wired to the RegistryService cluster recipe methods) -------

    override def getAllocationWithdrawContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        service
            .allocationContext(Cid(allocation.contractId), includeLocked = false)
            .map(toOpenApiContext)

    override def getAllocationCancelContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        service
            .allocationContext(Cid(allocation.contractId), includeLocked = true)
            .map(toOpenApiContext)

    override def getAllocationInstructionWithdrawContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        service.allocationInstructionContext(Cid(instruction.contractId)).map(toOpenApiContext)

    override def getAllocationInstructionAcceptContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        service.allocationInstructionContext(Cid(instruction.contractId)).map(toOpenApiContext)

    override def getTransferInstructionAcceptContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        service.transferInstructionContext(Cid(instruction.contractId)).map(toOpenApiContext)

    override def getTransferInstructionRejectContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        service.transferInstructionContext(Cid(instruction.contractId)).map(toOpenApiContext)

    override def getTransferInstructionWithdrawContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        service.transferInstructionContext(Cid(instruction.contractId)).map(toOpenApiContext)

    // -- ContextBundle -> codegen conversions ------------------------------------------------------

    private def embedContext(extra: ExtraArgs, bundle: ContextBundle): ExtraArgs =
        new ExtraArgs(toChoiceContext(bundle.values), extra.meta)

    private def toOpenApiContext(bundle: ContextBundle): OpenApiChoiceContext =
        OpenApiChoiceContext(toChoiceContext(bundle.values), disclosuresOf(bundle))

    private def toChoiceContext(values: Map[String, CtxValue]): ChoiceContext =
        new ChoiceContext(values.view.mapValues(toAnyValue).toMap.asJava)

    private def toAnyValue(v: CtxValue): AnyValue = v match
        case CtxValue.CtxContractId(cid) => new AV_ContractId(new AnyContract.ContractId(cid.value))
        case CtxValue.CtxList(items)     => new AV_List(items.map(toAnyValue).asJava)

    private def disclosuresOf(bundle: ContextBundle): List[DisclosedContract] =
        bundle.disclosures.map { d =>
            new DisclosedContract(
              parseIdentifier(d.templateId.value),
              d.contractId.value,
              ByteString.copyFrom(Base64.getDecoder.decode(d.createdEventBlob.value)),
              d.synchronizerId.value,
            )
        }

    /** Parse a `<pkgId>:<Module>:<Entity>` template id (as [[LedgerClientCanton.identifierString]]
      * produces) back into a Ledger-API `Identifier`.
      */
    private def parseIdentifier(s: String): Identifier =
        s.split(":") match
            case Array(pkg, module, entity) => new Identifier(pkg, module, entity)
            case _ => throw RegistryApi.Error.Decode(s"malformed template id: $s")
