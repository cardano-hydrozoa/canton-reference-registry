package tokenstandard

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.AllocationSpecification
import daml.splice.api.token.allocationv2.FinalizedAllocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.allocationv2.SettlementInfo
import daml.splice.api.token.allocationv2.TransferLeg
import daml.splice.api.token.allocationv2.TransferLegSide
import daml.splice.api.token.allocationv2.TransferSide
import daml.splice.api.token.holdingv2.Account
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import daml.splice.api.token.metadatav1.AnyValue
import daml.splice.api.token.metadatav1.ChoiceContext
import daml.splice.api.token.metadatav1.ExtraArgs
import daml.splice.api.token.metadatav1.Metadata

import java.math.BigDecimal as JBigDecimal
import java.time.Instant
import java.util.Optional
import scala.jdk.CollectionConverters.*

/** Scala-side constructors for the CIP-0112 codegen types — the port of
  * `Splice.TokenStandard.Utils` plus the treasury flow's `mkLeg`/`mkAlloc` smart constructors.
  * Isolates all Java interop (Optional/List/Map/BigDecimal boxing) so the flow reads like the Daml.
  */
object TokenStandardHelpers:

    def emptyMetadata: Metadata = new Metadata(Map.empty[String, String].asJava)
    def emptyChoiceContext: ChoiceContext = new ChoiceContext(Map.empty[String, AnyValue].asJava)
    def emptyExtraArgs: ExtraArgs = new ExtraArgs(emptyChoiceContext, emptyMetadata)

    extension (party: PartyId)
        /** Account with only an owner set (Daml's `basicAccount`). */
        def basicAccount: Account =
            new Account(Optional.of(party.value), Optional.empty(), "")

    def instrumentId(admin: PartyId, id: String): InstrumentId = new InstrumentId(admin.value, id)

    def transferLeg(
        transferLegId: String,
        sender: Account,
        receiver: Account,
        amount: BigDecimal,
        instrumentId: String,
    ): TransferLeg =
        new TransferLeg(
          transferLegId,
          sender,
          receiver,
          amount.bigDecimal,
          instrumentId,
          emptyMetadata
        )

    /** The sending party's view of a leg (Daml's `senderSide`): counterparty is the receiver. */
    def senderSide(leg: TransferLeg): TransferLegSide =
        new TransferLegSide(
          leg.transferLegId,
          TransferSide.SENDERSIDE,
          leg.receiver,
          leg.amount,
          leg.instrumentId,
          leg.meta
        )

    /** The receiving party's view of a leg (Daml's `receiverSide`): counterparty is the sender. */
    def receiverSide(leg: TransferLeg): TransferLegSide =
        new TransferLegSide(
          leg.transferLegId,
          TransferSide.RECEIVERSIDE,
          leg.sender,
          leg.amount,
          leg.instrumentId,
          leg.meta
        )

    def allocationSpec(
        admin: PartyId,
        authorizer: Account,
        sides: List[TransferLegSide],
        committed: Boolean,
        nextIterationFunding: Option[Map[String, BigDecimal]],
    ): AllocationSpecification =
        new AllocationSpecification(
          admin.value,
          authorizer,
          sides.asJava,
          Optional.empty(), // settlementDeadline
          fundingOpt(nextIterationFunding),
          java.lang.Boolean.valueOf(committed),
          emptyMetadata,
        )

    def settlementInfo(executors: List[PartyId], id: String): SettlementInfo =
        new SettlementInfo(executors.map(_.value).asJava, id, Optional.empty(), emptyMetadata)

    def allocationFactoryAllocate(
        settlement: SettlementInfo,
        spec: AllocationSpecification,
        requestedAt: Instant,
        inputs: List[Holding.ContractId],
        actors: List[PartyId],
    ): AllocationFactory_Allocate =
        new AllocationFactory_Allocate(
          settlement,
          spec,
          requestedAt,
          inputs.asJava,
          emptyExtraArgs,
          actors.map(_.value).asJava
        )

    def settlementFactorySettleBatch(
        settlement: SettlementInfo,
        legs: List[TransferLeg],
        allocations: List[FinalizedAllocation],
        actors: List[PartyId],
    ): SettlementFactory_SettleBatch =
        new SettlementFactory_SettleBatch(
          settlement,
          legs.asJava,
          allocations.asJava,
          actors.map(_.value).asJava,
          emptyExtraArgs
        )

    def finalizedAllocation(
        allocationCid: Allocation.ContractId,
        extraTransferLegSides: List[TransferLegSide],
        nextIterationFunding: Option[Map[String, BigDecimal]],
    ): FinalizedAllocation =
        new FinalizedAllocation(
          allocationCid,
          extraTransferLegSides.asJava,
          fundingOpt(nextIterationFunding)
        )

    /** A settled allocation with no rolled-forward iteration (Daml's `nonIteratedAllocation`). */
    def nonIteratedAllocation(allocationCid: Allocation.ContractId): FinalizedAllocation =
        finalizedAllocation(allocationCid, Nil, None)

    private def fundingOpt(
        f: Option[Map[String, BigDecimal]]
    ): Optional[java.util.Map[String, JBigDecimal]] =
        f match
            case Some(m) => Optional.of(m.map((k, v) => (k, v.bigDecimal)).asJava)
            case None    => Optional.empty()
