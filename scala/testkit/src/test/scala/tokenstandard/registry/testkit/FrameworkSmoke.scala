package tokenstandard.registry.testkit

import org.scalacheck.Prop
import org.scalacheck.Properties

/** Smoke check that the ported [[test.ScalaCheckFrameworkFixed]] runs *every* property of a suite
  * (sbt 2's native ScalaCheck integration drops all but the last). Two properties → both must
  * report.
  */
object FrameworkSmoke extends Properties("framework-smoke"):
    property("addition-identity") = Prop.forAll((n: Int) => n + 0 == n)
    property("multiplication-identity") = Prop.forAll((n: Int) => n * 1 == n)
