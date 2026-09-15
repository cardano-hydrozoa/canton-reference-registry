package tokenstandard.registry.service.http

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.Uri
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.registry.service.*

import java.nio.file.Files
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** Anchors the hand-written route strings to the vendored OpenAPI specs: every `(method, path)` the
  * specs declare must be SERVED by [[RegistryRoutes]]. The per-endpoint round-trip specs prove the
  * client and server agree with each other — but agreeing-and-wrong paths would pass them; this is
  * the check that they agree with the SPEC. Path templates come from the yaml text (paths are the
  * two-space-indented `/...:` keys), `{param}`s are substituted with a fixture value, and an
  * endpoint counts as routed iff the app answers anything but 404 (handler errors on the dummy
  * inputs prove routing just as well).
  */
class RouteCoverageSpec extends AsyncFunSuite, AsyncIOSpec:

    private val rules =
        Contract(
          Cid("rules"),
          TemplateId("TestTokenV2:TokenRules"),
          (),
          Blob("blob-rules"),
          SynchronizerId("sync-1")
        )

    // Catalog contains the substituted instrument id "x" so /instruments/{instrumentId} answers
    // 200, not a semantic 404.
    private val app =
        RegistryRoutes[IO](
          RegistryService(MockAcsSource[IO](rules, Nil)),
          RegistryMetadata.basic("adminTT2", List("x")),
        ).routes.orNotFound

    /** All `(method, path-template)` pairs declared across the vendored specs. */
    private def specEndpoints: List[(String, String)] =
        val dir = Paths.get(sys.props("user.dir"), "registry-openapi")
        Files
            .list(dir)
            .iterator()
            .asScala
            .filter(_.toString.endsWith(".yaml"))
            .toList
            .flatMap { f =>
                val lines = Files.readAllLines(f).asScala.toList
                val pathRe = "^  (/[^\\s:]+):".r
                val methodRe = "^    (get|post|put|delete):".r
                lines
                    .foldLeft((Option.empty[String], List.empty[(String, String)])) {
                        case ((path, acc), line) =>
                            line match
                                case pathRe(p)   => (Some(p), acc)
                                case methodRe(m) =>
                                    (path, path.fold(acc)(p => (m.toUpperCase, p) :: acc))
                                case _ => (path, acc)
                    }
                    ._2
            }

    test("every endpoint declared in the vendored OpenAPI specs is routed"):
        val endpoints = specEndpoints
        assert(endpoints.size == 13, s"unexpected spec endpoint count: $endpoints")
        endpoints
            .traverse { (method, template) =>
                val path = template.replaceAll("\\{[^}]+\\}", "x")
                val req =
                    Request[IO](Method.fromString(method).toOption.get, Uri.unsafeFromString(path))
                // A raised handler error (bad dummy body/cid) still proves the path is routed;
                // only the orNotFound fallback yields 404.
                app.run(req).attempt.map(r => (method, template, r.map(_.status)))
            }
            .asserting { results =>
                val unrouted = results.collect { case (m, p, Right(Status.NotFound)) =>
                    s"$m $p"
                }
                assert(unrouted.isEmpty, s"spec endpoints not routed: $unrouted")
            }
