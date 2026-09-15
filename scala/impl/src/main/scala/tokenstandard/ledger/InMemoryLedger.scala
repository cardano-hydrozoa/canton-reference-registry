package tokenstandard.ledger

import cats.data.StateT
import com.daml.ledger.javaapi.data.DisclosedContract
import com.daml.ledger.javaapi.data.ExerciseCommand
import com.daml.ledger.javaapi.data.ExercisedEvent
import com.daml.ledger.javaapi.data.Value
import com.daml.ledger.javaapi.data.codegen.Update
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationinstructionv2.AllocationInstructionResult
import daml.splice.api.token.allocationinstructionv2.allocationinstructionresult_output.AllocationInstructionResult_Completed
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.AllocationResult
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatchResult
import daml.splice.api.token.allocationv2.TransferSide
import daml.splice.api.token.allocationv2.allocationresult_output.AllocationResult_Settled
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers.emptyMetadata
import tokenstandard.registry.RegistryApi.Error

import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A party's balance of one instrument. */
final case class Bal(unlocked: BigDecimal, locked: BigDecimal)

/** A live (or consumed) allocation and the funds it holds. */
final case class AllocRec(
    id: String,
    authorizer: PartyId,
    holds: Map[String, BigDecimal],
    iterated: Boolean,
    closed: Boolean,
)

/** The whole in-memory ledger, tracked per (owner, instrumentId.id); the single admin is implicit.
  */
final case class LedgerState(
    balances: Map[(PartyId, String), Bal],
    allocs: Map[String, AllocRec],
    nextId: Int,
):
    def bal(owner: PartyId, instr: String): Bal = balances.getOrElse((owner, instr), Bal(0, 0))
    def adjust(
        owner: PartyId,
        instr: String,
        dUnlocked: BigDecimal,
        dLocked: BigDecimal
    ): LedgerState =
        val b = bal(owner, instr)
        copy(balances =
            balances.updated((owner, instr), Bal(b.unlocked + dUnlocked, b.locked + dLocked))
        )
    def putAlloc(rec: AllocRec): LedgerState = copy(allocs = allocs.updated(rec.id, rec))
    def freshAllocId: (String, LedgerState) = (s"alloc-$nextId", copy(nextId = nextId + 1))

object LedgerState:
    val empty: LedgerState = LedgerState(Map.empty, Map.empty, 0)

    /** Pre-seed unlocked balances (the registry-admin mint done at setup). */
    def seed(initial: List[(PartyId, InstrumentId, BigDecimal)]): LedgerState =
        initial.foldLeft(empty)((s, t) => s.adjust(t._1, t._2.id, t._3, BigDecimal(0)))

/** Ledger effect: a state transition over [[LedgerState]] with the error in the `Either` base, so a
  * failed operation aborts with no state change (transactional). Mirrors `CardanoBackendMock`'s
  * pure `State`, plus the error channel every `LedgerClient` method needs.
  */
type LedgerM[A] = StateT[[X] =>> Either[Error, X], LedgerState, A]

/** In-memory reference [[LedgerClient]]: a stand-in for a Canton ledger + registry contracts,
  * faithful enough to reproduce the balance transitions `TestHydrozoaTreasury` asserts. Pure and
  * deterministic (no runtime).
  *
  * [[exercise]] interprets the codegen `Update`'s `ExerciseCommand` by choice name — decoding the
  * argument with the choice's `valueDecoder`, simulating the transition, and feeding the typed
  * result back through the update's own decoder/continuation — so callers use the exact same
  * codegen `exercise*` surface a live client accepts. Supported choices:
  *
  *   - `AllocationFactory_Allocate`: each SENDER-side leg locks its amount from the authorizer
  *     (unlocked → locked); receiver-side and empty (pool) allocations lock nothing.
  *   - `SettlementFactory_SettleBatch`: each leg moves its amount from sender's locked to the
  *     receiver; the receiver's funds land *locked* iff the receiver authorizes an iterated (pool)
  *     allocation in the batch, and *unlocked* otherwise. An iterated finalized allocation rolls
  *     forward to a fresh allocation holding its funding.
  *
  * Visibility is total: `actAs`/`readAs`/`as` and `disclosures` are accepted (same signatures as a
  * live client) but not enforced.
  */
object InMemoryLedger extends LedgerClient[LedgerM]:

    def submit[A](
        actAs: PartyId,
        readAs: List[PartyId],
        submission: Submission[A],
        disclosures: List[DisclosedContract],
    ): LedgerM[A] =
        // One state transition for the WHOLE batch: any failing command short-circuits the Either
        // and no intermediate state escapes — the in-memory model of an atomic transaction.
        StateT { s0 =>
            submission.commands
                .foldLeft[Either[Error, (LedgerState, List[Any])]](Right((s0, Nil))) {
                    (acc, update) =>
                        acc.flatMap { (s, results) =>
                            update match
                                case eu: Update.ExerciseUpdate[?, ?] =>
                                    eu.commands().asScala.toList match
                                        case (cmd: ExerciseCommand) :: Nil =>
                                            interpret(cmd, s).map { (s2, resultValue) =>
                                                val decoded = Submission.decodeExercised(
                                                  eu,
                                                  synthEvent(cmd, resultValue),
                                                )
                                                (s2, results :+ decoded)
                                            }
                                        case other =>
                                            Left(
                                              Error.Unexpected(
                                                s"InMemoryLedger: expected 1 exercise command, got $other"
                                              )
                                            )
                                case other =>
                                    Left(
                                      Error.NotImplemented(
                                        s"InMemoryLedger update: ${other.getClass.getName}"
                                      )
                                    )
                        }
                }
                .map { (s2, results) => (s2, submission.decode(results)) }
        }

    /** Simulate one choice: decode the argument, apply the transition, return the encoded result.
      */
    private def interpret(
        cmd: ExerciseCommand,
        s: LedgerState,
    ): Either[Error, (LedgerState, Value)] =
        cmd.getChoice match
            case "AllocationFactory_Allocate" =>
                val arg = AllocationFactory_Allocate.valueDecoder().decode(cmd.getChoiceArgument)
                val (s2, cid) = allocate(arg, s)
                val result = new AllocationInstructionResult(
                  new AllocationInstructionResult_Completed(cid),
                  Map.empty[String, java.util.List[Holding.ContractId]].asJava,
                  emptyMetadata,
                )
                Right((s2, result.toValue))
            case "SettlementFactory_SettleBatch" =>
                val arg = SettlementFactory_SettleBatch.valueDecoder().decode(cmd.getChoiceArgument)
                val (s2, nexts) = settle(arg, s)
                val result = new SettlementFactory_SettleBatchResult(
                  nexts.map { next =>
                      new AllocationResult(
                        new AllocationResult_Settled(next.toJava),
                        Map.empty[String, java.util.List[Holding.ContractId]].asJava,
                        emptyMetadata,
                      )
                  }.asJava,
                  emptyMetadata,
                )
                Right((s2, result.toValue))
            case other => Left(Error.NotImplemented(s"InMemoryLedger choice: $other"))

    private def allocate(
        arg: AllocationFactory_Allocate,
        s: LedgerState,
    ): (LedgerState, Allocation.ContractId) =
        val spec = arg.allocation
        val authorizer = PartyId(spec.authorizer.owner.toScala.getOrElse(""))
        val sides = spec.transferLegSides.asScala.toList
        val (afterLocks, holds) =
            sides
                .filter(_.side == TransferSide.SENDERSIDE)
                .foldLeft((s, Map.empty[String, BigDecimal])) { case ((st, h), side) =>
                    val amt = BigDecimal(side.amount)
                    val instr = side.instrumentId
                    (
                      st.adjust(authorizer, instr, -amt, amt),
                      h.updated(instr, h.getOrElse(instr, BigDecimal(0)) + amt)
                    )
                }
        val (id, afterId) = afterLocks.freshAllocId
        val rec = AllocRec(
          id,
          authorizer,
          holds,
          iterated = spec.nextIterationFunding.isPresent,
          closed = false
        )
        (afterId.putAlloc(rec), new Allocation.ContractId(id))

    private def settle(
        arg: SettlementFactory_SettleBatch,
        s: LedgerState,
    ): (LedgerState, List[Option[Allocation.ContractId]]) =
        val legs = arg.transferLegs.asScala.toList
        val finalized = arg.allocations.asScala.toList

        // Receivers whose funds stay locked: authorizers of the iterated (pool) allocations.
        val poolAuthorizers: Set[PartyId] =
            finalized.flatMap { fa =>
                if fa.nextIterationFunding.isPresent then
                    s.allocs.get(fa.allocationCid.contractId).map(_.authorizer)
                else None
            }.toSet

        val afterLegs = legs.foldLeft(s) { (st, leg) =>
            val amt = BigDecimal(leg.amount)
            val instr = leg.instrumentId
            val sender = PartyId(leg.sender.owner.toScala.getOrElse(""))
            val receiver = PartyId(leg.receiver.owner.toScala.getOrElse(""))
            val st1 = st.adjust(sender, instr, BigDecimal(0), -amt) // sender: locked -amt
            if poolAuthorizers.contains(receiver) then
                st1.adjust(receiver, instr, BigDecimal(0), amt) // stays locked in pool
            else st1.adjust(receiver, instr, amt, BigDecimal(0)) // delivered, unlocked
        }

        // Close each consumed allocation; roll iterated ones forward. Order preserved so the
        // flow can read the pool's next iteration off the head.
        finalized.foldLeft((afterLegs, List.empty[Option[Allocation.ContractId]])) {
            case ((st, acc), fa) =>
                val cid = fa.allocationCid.contractId
                val prior = st.allocs.get(cid)
                val closed = prior.fold(st)(p => st.putAlloc(p.copy(closed = true)))
                fa.nextIterationFunding.toScala match
                    case Some(fundingJava) =>
                        val funding = fundingJava.asScala.view.mapValues(BigDecimal(_)).toMap
                        val (nid, withId) = closed.freshAllocId
                        val auth = prior.map(_.authorizer).getOrElse(PartyId(""))
                        val rolled = withId.putAlloc(
                          AllocRec(nid, auth, funding, iterated = true, closed = false)
                        )
                        (rolled, acc :+ Some(new Allocation.ContractId(nid)))
                    case None =>
                        (closed, acc :+ None)
        }

    /** A synthetic [[ExercisedEvent]] carrying the simulated result, so the update's own
      * `Exercised.fromEvent` decoder path runs exactly as it would on a live transaction.
      */
    private def synthEvent(cmd: ExerciseCommand, result: Value): ExercisedEvent =
        new ExercisedEvent(
          java.util.List.of(), // witnessParties
          java.lang.Long.valueOf(0L), // offset
          java.lang.Integer.valueOf(0), // nodeId
          cmd.getTemplateId,
          "in-memory", // packageName
          Optional.empty(), // interfaceId
          cmd.getContractId,
          cmd.getChoice,
          cmd.getChoiceArgument,
          java.util.List.of(), // actingParties
          true, // consuming
          java.lang.Integer.valueOf(0), // lastDescendantNodeId
          result,
          java.util.List.of(), // implementedInterfaces
        )

    def unlockedBalance(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId
    ): LedgerM[BigDecimal] =
        StateT.inspect(_.bal(owner, instrument.id).unlocked)

    def lockedBalance(as: PartyId, owner: PartyId, instrument: InstrumentId): LedgerM[BigDecimal] =
        StateT.inspect(_.bal(owner, instrument.id).locked)

    def listHoldingCids(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId,
    ): LedgerM[List[Holding.ContractId]] =
        StateT.inspect { s =>
            // One synthetic cid representing the party's unlocked holding of the instrument; the
            // flow feeds these back as inputs, which this fake locks by amount (not by cid).
            if s.bal(owner, instrument.id).unlocked > 0 then
                List(new Holding.ContractId(s"holding/$owner/${instrument.id}"))
            else Nil
        }

    def activeAllocations(as: PartyId, owner: PartyId): LedgerM[List[Allocation.ContractId]] =
        StateT.inspect { s =>
            s.allocs.values
                .filter(a => a.authorizer == owner && !a.closed)
                .map(a => new Allocation.ContractId(a.id))
                .toList
        }
