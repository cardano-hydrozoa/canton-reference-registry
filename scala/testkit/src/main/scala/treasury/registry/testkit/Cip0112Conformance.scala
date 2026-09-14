package treasury.registry.testkit

import scala.jdk.CollectionConverters.*

import cats.Monad
import cats.syntax.all.*

import com.daml.ledger.javaapi.data.DisclosedContract

import org.scalacheck.{Gen, Prop, Properties, PropertyM}
import org.scalacheck.util.Pretty

import treasury.registry.RegistryApi
import treasury.registry.RegistryApi.EnrichedFactoryChoice

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate

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
  * the prefetchability property.
  */
final case class CidVariant[A](base: A, sameAccountsDifferentCids: A)

/** Everything the conformance suite needs to exercise a specific [[RegistryApi]] implementation:
  * how to run its effect `F` to a `Prop`, and generators for choice arguments the implementation's
  * ledger state can serve. Kept separate from the impl so the suite stays parametric — a
  * mock-backed impl supplies in-memory scenarios, a Canton-backed impl supplies live ones.
  */
trait ConformanceFixture[F[_]]:
    given monad: Monad[F]

    /** Run an `F[Prop]` to a `Prop` (IO: `unsafeRunSync` via an `IORuntime`; `Either`: fold). */
    def runToProp(fp: F[Prop]): Prop

    /** Allocation-factory args the implementation under test can serve (accounts it knows). */
    def allocationArgs: Gen[CidVariant[AllocationFactory_Allocate]]

/** CIP-0112 registry conformance suite, parametric over any [[RegistryApi]] implementation and its
  * [[ConformanceFixture]]. Encodes the normative properties derivable from the token-standard
  * OpenAPI specs + Daml semantics (the CIP text itself defers to them):
  *   - P1 (MUST): every returned `DisclosedContract` has all four required fields populated.
  *   - P2 (MUST): `factoryId` is present.
  *   - P3 (SHOULD): `choiceContextData` does not depend on contract-ids in the choice arguments, so
  *     clients can prefetch (flag: `checkPrefetchability`).
  *   - P4: the assembled context is deterministic in the arguments + ledger state (flag:
  *     `checkDeterminism`).
  *
  * Returns an `org.scalacheck.Properties`; run it via the ScalaCheck framework (see the ported
  * `test.ScalaCheckFrameworkFixed`) or `Test.checkProperties`.
  */
object Cip0112Conformance:

    // PropertyM.forAllM needs an `A => Pretty`, and PropertyM.monadic an `A => Prop` finisher; the
    // property bodies below generate arbitrary args and yield Unit, so supply both generically.
    private given anyToPretty[A]: (A => Pretty) = a => Pretty.prettyAny(a)
    private given unitToProp: (Unit => Prop) = _ => Prop.proved

    def suite[F[_]](
        impl: RegistryApi[F],
        fixture: ConformanceFixture[F],
        flags: ConformanceFlags = ConformanceFlags.default,
    ): Properties =
        new Properties("cip0112-registry"):
            given Monad[F] = fixture.monad

            /** Turn a generated-then-monadic body into a `Prop`, running `F` via the fixture. */
            private def check[A](gen: Gen[A])(body: A => PropertyM[F, Unit]): Prop =
                PropertyM.monadic(fixture.runToProp, PropertyM.forAllM(gen, body))

            property("allocation-factory: factoryId present (P2) and disclosures complete (P1)") =
                check(fixture.allocationArgs.map(_.base)) { arg =>
                    for
                        r <- PropertyM.run(impl.getAllocationFactory(arg))
                        _ <- PropertyM.assertWith(
                          r.factoryCid.nonEmpty,
                          "factoryId must be present"
                        )
                        _ <- PropertyM.assertWith(
                          r.disclosures.forall(disclosureComplete),
                          "every disclosed contract must have templateId/contractId/blob/synchronizerId",
                        )
                    yield ()
                }

            if flags.checkPrefetchability then
                property("allocation-factory: choiceContextData independent of arg cids (P3)") =
                    check(fixture.allocationArgs) { v =>
                        for
                            r1 <- PropertyM.run(impl.getAllocationFactory(v.base))
                            r2 <- PropertyM.run(
                              impl.getAllocationFactory(v.sameAccountsDifferentCids)
                            )
                            _ <- PropertyM.assertWith(
                              contextData(r1) == contextData(r2),
                              "choiceContextData must not depend on contract-ids in the arguments",
                            )
                        yield ()
                    }

            if flags.checkDeterminism then
                property("allocation-factory: deterministic in args + state (P4)") =
                    check(fixture.allocationArgs.map(_.base)) { arg =>
                        for
                            r1 <- PropertyM.run(impl.getAllocationFactory(arg))
                            r2 <- PropertyM.run(impl.getAllocationFactory(arg))
                            _ <- PropertyM.assertWith(
                              contextData(r1) == contextData(r2) && r1.factoryCid == r2.factoryCid,
                              "same args + state must yield the same factory choice",
                            )
                        yield ()
                    }

    private def disclosureComplete(d: DisclosedContract): Boolean =
        d.templateId != null &&
            d.contractId.nonEmpty &&
            !d.createdEventBlob.isEmpty &&
            d.synchronizerId.isPresent && !d.synchronizerId.get.isEmpty

    /** The `choiceContextData` (Daml `ChoiceContext.values`) embedded in the returned choice arg.
      */
    private def contextData(
        r: EnrichedFactoryChoice[AllocationFactory_Allocate]
    ): Map[String, ?] =
        r.arg.extraArgs.context.values.asScala.toMap
