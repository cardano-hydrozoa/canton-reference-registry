package tokenstandard.ledger

import cats.Applicative
import com.daml.ledger.javaapi.data.ExercisedEvent
import com.daml.ledger.javaapi.data.codegen.Exercised
import com.daml.ledger.javaapi.data.codegen.Update

/** A batch of commands submitted as ONE Ledger-API submission — i.e. ONE atomic transaction — with
  * a typed decode of the results. The Scala counterpart of Daml Script's `Commands`:
  *
  *   - `Applicative`: combining submissions concatenates their commands and pairs their results —
  *     `Submission.exercise(a) *> Submission.exercise(b)` is Daml's `exerciseCmd a *> exerciseCmd
  *     b`, the mechanism behind atomic cross-registry settlement (all commands commit in one
  *     transaction or none do).
  *   - Deliberately NOT a `Monad`: a later command cannot depend on an earlier command's result,
  *     because the whole command list is built before submission — results only exist once the
  *     ledger interprets the transaction. (Daml's *on-ledger* `Update` monad is a different type:
  *     the effect inside choice bodies. The bindings' client-side `Update[U]` — one command + its
  *     result decoder — is a fragment of `Commands`, despite the name.)
  *
  * Sequencing submissions in `F` (`flatMap`) is the non-atomic composition: separate transactions.
  *
  * `decode` is positional: the interpreter supplies one decoded result per command, in command
  * order.
  */
final case class Submission[A](commands: List[Update[?]], decode: List[Any] => A)

object Submission:

    /** A single exercise as a one-command submission. */
    def exercise[U](update: Update[U]): Submission[U] =
        Submission(List(update), rs => rs.head.asInstanceOf[U])

    given Applicative[Submission] with
        def pure[A](a: A): Submission[A] = Submission(Nil, _ => a)
        def ap[A, B](ff: Submission[A => B])(fa: Submission[A]): Submission[B] =
            Submission(
              ff.commands ++ fa.commands,
              rs =>
                  val (fr, ar) = rs.splitAt(ff.commands.size)
                  ff.decode(fr)(fa.decode(ar))
            )

    /** Decode one root `ExercisedEvent` through its update's own result decoder + continuation —
      * the shared per-command step of every [[LedgerClient.submit]] interpreter.
      */
    def decodeExercised(update: Update[?], event: ExercisedEvent): Any =
        update match
            case eu: Update.ExerciseUpdate[?, ?] => decodeVia(eu, event)
            case other                           =>
                throw new IllegalArgumentException(
                  s"not an exercise update: ${other.getClass.getName}"
                )

    private def decodeVia[R, U](eu: Update.ExerciseUpdate[R, U], event: ExercisedEvent): U =
        eu.k.apply(Exercised.fromEvent(eu.returnTypeDecoder, event))
