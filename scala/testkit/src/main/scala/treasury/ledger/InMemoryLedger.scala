package treasury.ledger

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import cats.data.StateT

import treasury.PartyId
import treasury.ledger.LedgerClient.SettleResult
import treasury.registry.RegistryApi.{EnrichedFactoryChoice, Error}

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.{Allocation, SettlementFactory_SettleBatch, TransferSide}
import daml.splice.api.token.holdingv2.{Holding, InstrumentId}

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

/** In-memory stand-in for a Canton ledger + registry contracts, faithful enough to reproduce the
  * balance transitions `TestHydrozoaTreasury` asserts. Pure and deterministic (no runtime). It
  * interprets the CIP-0112 codegen args the stub echoes back:
  *
  *   - allocate: each SENDER-side leg locks its amount from the authorizer (unlocked → locked);
  *     receiver-side and empty (treasury) allocations lock nothing.
  *   - settle: each leg moves its amount from sender's locked to the receiver; the receiver's funds
  *     land *locked* iff the receiver authorizes an iterated (pool) allocation in the batch — that
  *     is what keeps deposits in the treasury — and *unlocked* otherwise (a payout to a party). An
  *     iterated finalized allocation rolls forward to a fresh allocation holding its funding.
  *
  * None of these operations fail in the treasury flow, so each returns `Right`; the `Either` base
  * is there to satisfy the `MonadError` the flow's assertions raise into.
  */
object InMemoryLedger extends LedgerClient[LedgerM]:

    def exerciseAllocationFactory(
        actAs: PartyId,
        bundle: EnrichedFactoryChoice[AllocationFactory_Allocate],
    ): LedgerM[Allocation.ContractId] =
        StateT { s =>
            val spec = bundle.arg.allocation
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
            Right((afterId.putAlloc(rec), new Allocation.ContractId(id)))
        }

    def exerciseSettlementFactory(
        actAs: PartyId,
        bundle: EnrichedFactoryChoice[SettlementFactory_SettleBatch],
    ): LedgerM[SettleResult] =
        StateT { s =>
            val arg = bundle.arg
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
            // flow can read the treasury's next iteration off the head.
            val (afterAllocs, nexts) =
                finalized.foldLeft((afterLegs, List.empty[Option[Allocation.ContractId]])) {
                    case ((st, acc), fa) =>
                        val cid = fa.allocationCid.contractId
                        val prior = st.allocs.get(cid)
                        val closed = prior.fold(st)(p => st.putAlloc(p.copy(closed = true)))
                        fa.nextIterationFunding.toScala match
                            case Some(fundingJava) =>
                                val funding =
                                    fundingJava.asScala.view.mapValues(BigDecimal(_)).toMap
                                val (nid, withId) = closed.freshAllocId
                                val auth = prior.map(_.authorizer).getOrElse(PartyId(""))
                                val rolled = withId.putAlloc(
                                  AllocRec(nid, auth, funding, iterated = true, closed = false)
                                )
                                (rolled, acc :+ Some(new Allocation.ContractId(nid)))
                            case None =>
                                (closed, acc :+ None)
                }
            Right((afterAllocs, SettleResult(nexts)))
        }

    def unlockedBalance(owner: PartyId, instrument: InstrumentId): LedgerM[BigDecimal] =
        StateT.inspect(_.bal(owner, instrument.id).unlocked)

    def lockedBalance(owner: PartyId, instrument: InstrumentId): LedgerM[BigDecimal] =
        StateT.inspect(_.bal(owner, instrument.id).locked)

    def listHoldingCids(
        owner: PartyId,
        instrument: InstrumentId
    ): LedgerM[List[Holding.ContractId]] =
        StateT.inspect { s =>
            // One synthetic cid representing the party's unlocked holding of the instrument; the
            // flow feeds these back as inputs, which this fake locks by amount (not by cid).
            if s.bal(owner, instrument.id).unlocked > 0 then
                List(new Holding.ContractId(s"holding/$owner/${instrument.id}"))
            else Nil
        }

    def activeAllocations(owner: PartyId): LedgerM[List[Allocation.ContractId]] =
        StateT.inspect { s =>
            s.allocs.values
                .filter(a => a.authorizer == owner && !a.closed)
                .map(a => new Allocation.ContractId(a.id))
                .toList
        }
