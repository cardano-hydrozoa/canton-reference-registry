package treasury.registry

import cats.Applicative

/** Minimal contravariant tracer, standing in for hydrozoa's `ContraTracer[F, E]` (which pulls a
  * tracing dependency this sandbox doesn't have yet). Same shape — a backend holds one and emits
  * typed events — so swapping in the real one later is mechanical.
  */
trait Tracer[F[_], -E]:
    def trace(event: E): F[Unit]

object Tracer:
    def noop[F[_], E](using F: Applicative[F]): Tracer[F, E] =
        new Tracer[F, E]:
            def trace(event: E): F[Unit] = F.unit
