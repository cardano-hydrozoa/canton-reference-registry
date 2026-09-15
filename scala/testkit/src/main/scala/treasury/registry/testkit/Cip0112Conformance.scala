package treasury.registry.testkit

import cats.Monad
import cats.syntax.all.*
import com.daml.ledger.javaapi.data.DisclosedContract
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Properties
import org.scalacheck.PropertyM
import org.scalacheck.util.Pretty

import scala.jdk.CollectionConverters.*

/** Optional (RFC-2119 SHOULD/MAY) conformance properties, flag-gated with sensible defaults. The
  * MUST properties (P1 complete disclosures, P2 factoryId present) always run.
  */
final case class ConformanceFlags(
    checkPrefetchability: Boolean = true, // P3 (SHOULD): choiceContextData independent of arg cids
    checkDeterminism: Boolean = true, // P4: same args + state -> same context
)
object ConformanceFlags:
    val default: ConformanceFlags = ConformanceFlags()

/** A base choice arg and a variant referencing the SAME accounts but DIFFERENT contract-ids, for
  * the prefetchability property. For non-factory endpoints (where P3 does not apply) use
  * [[CidVariant.same]].
  */
final case class CidVariant[A](base: A, sameAccountsDifferentCids: A)
object CidVariant:
    def same[A](a: A): CidVariant[A] = CidVariant(a, a)

/** A normalized view of any registry endpoint's result — factories and lifecycle contexts alike —
  * over which the conformance properties are stated: the (optional) factory id, the assembled
  * choice-context values, and the disclosures. Fixtures build one from the concrete endpoint result
  * (they know its type; the suite stays generic).
  */
final case class ContextView(
    factoryId: Option[String],
    contextValues: Map[String, ?],
    disclosures: List[DisclosedContract],
)
object ContextView:
    def factory(
        factoryId: String,
        values: java.util.Map[String, ?],
        disclosures: List[DisclosedContract],
    ): ContextView = ContextView(Some(factoryId), values.asScala.toMap, disclosures)

    def context(
        values: java.util.Map[String, ?],
        disclosures: List[DisclosedContract],
    ): ContextView = ContextView(None, values.asScala.toMap, disclosures)

/** One endpoint of the implementation under test: a name, whether it is a factory (so P2 —
  * factoryId — applies), a generator of inputs (base + a same-accounts-different-cids variant for
  * P3), and how to call the impl and normalize its result to a [[ContextView]]. The input type `A`
  * is existential (an abstract member) so probes for different endpoints live in one
  * `List[EndpointProbe[F]]`.
  */
sealed trait EndpointProbe[F[_]]:
    type A
    def name: String
    def factoryLike: Boolean
    def inputs: Gen[CidVariant[A]]
    def call(a: A): F[ContextView]

object EndpointProbe:
    def apply[F[_], A0](
        name0: String,
        factoryLike0: Boolean,
        inputs0: Gen[CidVariant[A0]],
        call0: A0 => F[ContextView],
    ): EndpointProbe[F] = new EndpointProbe[F]:
        type A = A0
        val name = name0
        val factoryLike = factoryLike0
        val inputs = inputs0
        def call(a: A0): F[ContextView] = call0(a)

/** Everything the conformance suite needs to exercise a specific implementation: how to run its
  * effect `F` to a `Prop`, and the [[EndpointProbe]]s covering the endpoints it serves. Kept
  * separate from the impl so the suite stays parametric — a mock-backed impl supplies in-memory
  * scenarios, a Canton-backed impl supplies live ones, and an impl that serves only some endpoints
  * supplies only those probes.
  */
trait ConformanceFixture[F[_]]:
    given monad: Monad[F]

    /** Run an `F[Prop]` to a `Prop` (IO: `unsafeRunSync` via an `IORuntime`; `Either`: fold). */
    def runToProp(fp: F[Prop]): Prop

    /** The endpoints to exercise, each with input generators the implementation's state can serve.
      */
    def probes: List[EndpointProbe[F]]

/** CIP-0112 registry conformance suite, parametric over any [[RegistryApi]] implementation (via its
  * [[ConformanceFixture]]) and over the endpoints it serves. For every [[EndpointProbe]] it states
  * the normative properties derivable from the token-standard OpenAPI specs + Daml semantics:
  *   - P1 (MUST): every returned `DisclosedContract` has all four required fields populated.
  *   - P2 (MUST, factories only): `factoryId` is present.
  *   - P3 (SHOULD, factories only): `choiceContextData` does not depend on contract-ids in the
  *     choice arguments, so clients can prefetch (flag: `checkPrefetchability`).
  *   - P4: the assembled context is deterministic in the arguments + ledger state (flag:
  *     `checkDeterminism`).
  *
  * Returns an `org.scalacheck.Properties`; run it via the ScalaCheck framework (see the ported
  * `test.ScalaCheckFrameworkFixed`) or `Test.checkProperties`. The check predicates
  * ([[disclosuresComplete]], [[factoryIdPresent]]) are exposed so a non-ScalaCheck harness (e.g. an
  * `AsyncIOSpec` over an `IO`-only impl) can apply the same normative checks without the ScalaCheck
  * edge.
  */
object Cip0112Conformance:

    // PropertyM.forAllM needs an `A => Pretty`, and PropertyM.monadic an `A => Prop` finisher; the
    // property bodies below generate arbitrary args and yield Unit, so supply both generically.
    private given anyToPretty[A]: (A => Pretty) = a => Pretty.prettyAny(a)
    private given unitToProp: (Unit => Prop) = _ => Prop.proved

    def suite[F[_]](
        fixture: ConformanceFixture[F],
        flags: ConformanceFlags = ConformanceFlags.default,
    ): Properties =
        new Properties("cip0112-registry"):
            given Monad[F] = fixture.monad

            private def check[A](gen: Gen[A])(body: A => PropertyM[F, Unit]): Prop =
                PropertyM.monadic(fixture.runToProp, PropertyM.forAllM(gen, body))

            fixture.probes.foreach { probe =>
                property(s"${probe.name}: disclosures complete (P1) + factoryId present (P2)") =
                    check(probe.inputs.map(_.base)) { a =>
                        for
                            cv <- PropertyM.run(probe.call(a))
                            _ <- PropertyM.assertWith(
                              cv.disclosures.forall(disclosuresComplete),
                              "every disclosed contract must have templateId/contractId/blob/synchronizerId",
                            )
                            _ <- PropertyM.assertWith(
                              !probe.factoryLike || cv.factoryId.exists(_.nonEmpty),
                              "a factory endpoint must return a present factoryId",
                            )
                        yield ()
                    }

                if flags.checkPrefetchability && probe.factoryLike then
                    property(s"${probe.name}: choiceContextData independent of arg cids (P3)") =
                        check(probe.inputs) { v =>
                            for
                                r1 <- PropertyM.run(probe.call(v.base))
                                r2 <- PropertyM.run(probe.call(v.sameAccountsDifferentCids))
                                _ <- PropertyM.assertWith(
                                  r1.contextValues == r2.contextValues,
                                  "choiceContextData must not depend on contract-ids in the arguments",
                                )
                            yield ()
                        }

                if flags.checkDeterminism then
                    property(s"${probe.name}: deterministic in args + state (P4)") =
                        check(probe.inputs.map(_.base)) { a =>
                            for
                                r1 <- PropertyM.run(probe.call(a))
                                r2 <- PropertyM.run(probe.call(a))
                                _ <- PropertyM.assertWith(
                                  r1.contextValues == r2.contextValues && r1.factoryId == r2.factoryId,
                                  "same args + state must yield the same context",
                                )
                            yield ()
                        }
            }

    /** P1: a disclosed contract has all four wire fields populated. */
    def disclosuresComplete(d: DisclosedContract): Boolean =
        d.templateId != null &&
            d.contractId.nonEmpty &&
            !d.createdEventBlob.isEmpty &&
            d.synchronizerId.isPresent && !d.synchronizerId.get.isEmpty

    /** P2: a factory endpoint returns a present factoryId. */
    def factoryIdPresent(cv: ContextView): Boolean = cv.factoryId.exists(_.nonEmpty)
