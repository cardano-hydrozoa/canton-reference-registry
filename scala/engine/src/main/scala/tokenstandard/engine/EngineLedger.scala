package tokenstandard.engine

import cats.data.StateT
import cats.syntax.all.*
import com.daml.ledger.javaapi.data.ExerciseCommand
import com.daml.ledger.javaapi.data.ExercisedEvent
import com.daml.ledger.javaapi.data.Value
import com.daml.ledger.javaapi.data.codegen.Update
import com.digitalasset.daml.lf.command.ApiCommand
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.value.Value as Lf
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Account as DamlAccount
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.HoldingView
import daml.splice.api.token.holdingv2.InstrumentId
import daml.splice.testing.tokens.testtokenv2.TokenRules
import daml.splice.testing.tokens.testtokenv2.allocation.TokenAllocationV2
import daml.splice.testing.tokens.testtokenv2.holding.Token
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers.emptyMetadata
import tokenstandard.ledger.LedgerClient
import tokenstandard.ledger.Submission
import tokenstandard.registry.RegistryApi.Error

import java.util.Optional
import scala.jdk.CollectionConverters.*

/** [[LedgerClient]] backed by the real Daml interpreter ([[DamlEngine]]) over an in-process
  * [[EngineStore]] — the reference in-memory ledger, replacing the hand-written state machine with
  * genuine interpretation and authorization. Pure (`EngineM` = `StateT` over `Either`), so the
  * flows still run with no IO runtime; the same flow text runs live against `LedgerClientCanton`.
  *
  * [[submit]] turns each codegen `Update`'s exercise command into an `ApiCommand`, interprets the
  * batch as ONE transaction, then decodes each root exercise result through the update's own
  * codegen decoder (via a synthetic [[ExercisedEvent]], as a live transaction would). `disclosures`
  * are ignored: the store is globally visible, so every contract resolves without disclosure.
  */
final class EngineLedger(engine: DamlEngine) extends LedgerClient[EngineM]:

    def submit[A](
        actAs: List[PartyId],
        readAs: List[PartyId],
        submission: Submission[A],
        disclosures: List[com.daml.ledger.javaapi.data.DisclosedContract],
    ): EngineM[A] =
        val submitters = actAs.map(p => Ref.Party.assertFromString(p.value)).toSet
        StateT
            .liftF(submission.commands.traverse(exerciseCommandOf))
            .flatMap { commands =>
                for
                    tx <- engine.submit(submitters, commands.map(toApiCommand))
                    out <- StateT.liftF[[X] =>> Either[Error, X], EngineStore, A](
                      decodeResults(submission, commands, engine.rootExerciseResults(tx))
                    )
                yield out
            }

    /** Each `Update` must carry exactly one exercise command (the standard's flows are entirely
      * factory-mediated); anything else is unsupported, matching `LedgerClientCanton`.
      */
    private def exerciseCommandOf(update: Update[?]): Either[Error, ExerciseCommand] =
        update match
            case eu: Update.ExerciseUpdate[?, ?] =>
                eu.commands().asScala.toList match
                    case (cmd: ExerciseCommand) :: Nil => Right(cmd)
                    case other                         =>
                        Left(Error.NotImplemented(s"EngineLedger: expected 1 exercise, got $other"))
            case other =>
                Left(Error.NotImplemented(s"EngineLedger update: ${other.getClass.getName}"))

    private def toApiCommand(cmd: ExerciseCommand): ApiCommand =
        ApiCommand.Exercise(
          engine.toTypeConRef(cmd.getTemplateId),
          Lf.ContractId.assertFromString(cmd.getContractId),
          Ref.ChoiceName.assertFromString(cmd.getChoice),
          engine.toLf(cmd.getChoiceArgument),
        )

    private def decodeResults[A](
        submission: Submission[A],
        commands: List[ExerciseCommand],
        results: List[Lf],
    ): Either[Error, A] =
        Either.cond(
          results.sizeIs == commands.size,
          submission.decode(
            submission.commands.lazyZip(commands).lazyZip(results).map { (update, cmd, resultLf) =>
                Submission.decodeExercised(update, synthEvent(cmd, engine.fromLf(resultLf)))
            }
          ),
          Error.Unexpected(
            s"expected ${commands.size} root exercise results, got ${results.size}"
          ),
        )

    /** A synthetic [[ExercisedEvent]] carrying the interpreted result, so the update's own
      * `Exercised.fromEvent` decoder path runs exactly as on a live transaction.
      */
    private def synthEvent(cmd: ExerciseCommand, result: Value): ExercisedEvent =
        new ExercisedEvent(
          java.util.List.of(), // witnessParties
          java.lang.Long.valueOf(0L), // offset
          java.lang.Integer.valueOf(0), // nodeId
          cmd.getTemplateId,
          "engine", // packageName
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

    // --- ACS reads (`as`/`owner`: `as` is signature parity only — single-store, no visibility) ---

    def unlockedBalance(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId
    ): EngineM[BigDecimal] =
        holdingsOf(owner, instrument).map(
          _.filter(_.holding.lock.isEmpty).map(t => BigDecimal(t.holding.amount)).sum
        )

    def lockedBalance(as: PartyId, owner: PartyId, instrument: InstrumentId): EngineM[BigDecimal] =
        holdingsOf(owner, instrument).map(
          _.filter(_.holding.lock.isPresent).map(t => BigDecimal(t.holding.amount)).sum
        )

    def listHoldingCids(
        as: PartyId,
        owner: PartyId,
        instrument: InstrumentId,
    ): EngineM[List[Holding.ContractId]] =
        tokens.map(_.collect {
            case (cid, t)
                if t.holding.account.owner == Optional.of(owner.value)
                    && t.holding.instrumentId == instrument
                    && t.holding.lock.isEmpty =>
                new Holding.ContractId(cid)
        })

    def activeAllocations(as: PartyId, owner: PartyId): EngineM[List[Allocation.ContractId]] =
        StateT.inspect { store =>
            engine.active(store, TokenAllocationV2.TEMPLATE_ID).collect {
                case (cid, v) if allocOwner(v) == Optional.of(owner.value) =>
                    new Allocation.ContractId(cid)
            }
        }

    private def allocOwner(v: Value): Optional[String] =
        TokenAllocationV2.valueDecoder().decode(v).allocation.authorizer.owner

    /** `owner`'s Token holdings of `instrument` (locked or not), decoded from the store. */
    private def holdingsOf(owner: PartyId, instrument: InstrumentId): EngineM[List[Token]] =
        tokens.map(_.collect {
            case (_, t)
                if t.holding.account.owner == Optional.of(owner.value)
                    && t.holding.instrumentId == instrument =>
                t
        })

    private def tokens: EngineM[List[(String, Token)]] =
        StateT.inspect { store =>
            engine
                .active(store, Token.TEMPLATE_ID)
                .map((cid, v) => (cid, Token.valueDecoder().decode(v)))
        }

    // --- test-harness setup (no LedgerClient counterpart; the registry admin's on-ledger prep) ----

    /** Create the registry admin's `TokenRules`, returning its id. */
    def createTokenRules(admin: PartyId): EngineM[TokenRules.ContractId] =
        val create = new TokenRules(admin.value).create
        for cid <- exerciseless(Set(admin), create.commands().asScala.head)
        yield new TokenRules.ContractId(cid)

    /** Seed an unlocked `Token` holding of `amount` `instrument` into `owner`'s basic account,
      * authorized by the account party + the instrument admin (the `Token` signatories) — the setup
      * analogue of a mint, without the offer/accept choreography.
      */
    def seedHolding(
        admin: PartyId,
        owner: PartyId,
        instrument: InstrumentId,
        amount: BigDecimal,
    ): EngineM[Unit] =
        val account = new DamlAccount(Optional.of(owner.value), Optional.empty(), "")
        val view =
            new HoldingView(account, instrument, amount.bigDecimal, Optional.empty(), emptyMetadata)
        exerciseless(Set(admin, owner), new Token(view).create.commands().asScala.head).map(_ => ())

    /** Submit a single create command as `submitters` and return the created contract id. */
    private def exerciseless(
        submitters: Set[PartyId],
        command: com.daml.ledger.javaapi.data.Command,
    ): EngineM[String] =
        val create = command.asInstanceOf[com.daml.ledger.javaapi.data.CreateCommand]
        val api = ApiCommand.Create(
          engine.toTypeConRef(create.getTemplateId),
          engine.toLf(create.getCreateArguments),
        )
        for
            tx <- engine.submit(submitters.map(p => Ref.Party.assertFromString(p.value)), List(api))
            cid <- StateT.liftF[[X] =>> Either[Error, X], EngineStore, String](
              tx.nodes.values
                  .collectFirst { case c: com.digitalasset.daml.lf.transaction.Node.Create =>
                      c.coid.coid
                  }
                  .toRight(Error.Unexpected("no Create node in seed transaction"))
            )
        yield cid
