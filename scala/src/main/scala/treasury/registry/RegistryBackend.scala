package treasury.registry

import com.daml.ledger.javaapi.data.DisclosedContract
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch

/** Off-ledger registry API of a CIP-0112 token-standard registry — the Scala port of Daml's
  * `Splice.Testing.TokenStandard.RegistryApiV2.RegistryApi` typeclass. Each call "locates the
  * factory contract, assembles the choice context, and gathers the disclosures the submitter
  * needs," returning a ready-to-exercise bundle. Who computes that bundle is the backend seam:
  *   - [[treasury.registry.RegistryBackendStub]] returns canned bundles (pure tests),
  *   - a local-assembly backend reads a Canton ACS (localnet integration) — Phase 2,
  *   - an HTTP/OpenAPI backend calls a real registry (testnet) — Phase 3.
  *
  * Tagless-final over `F[_]` with a `protected tracer`, as in hydrozoa's `CardanoBackend`. Where
  * `CardanoBackend` returns `F[Either[Error, A]]` (error as a value), we fold the error into `F`
  * (the flow runs in a `MonadError[F, Error]`), since every method fails the same way — see
  * [[treasury.ledger.LedgerClient]].
  *
  * The treasury flow uses only [[getAllocationFactory]] and [[getSettlementFactory]]. The rest of
  * the `RegistryApiV2` surface — the transfer factory and the allocation/transfer-instruction
  * lifecycle context handlers (which return an `OpenApiChoiceContext` wrapping a `ChoiceContext`) —
  * is deferred until a flow needs it.
  */
trait RegistryBackend[F[_]]:
    import RegistryBackend.*

    protected def tracer: Tracer[F, RegistryBackendEvent]

    def getAllocationFactory(
        arg: AllocationFactory_Allocate
    ): F[EnrichedFactoryChoice[AllocationFactory_Allocate]]

    def getSettlementFactory(
        arg: SettlementFactory_SettleBatch
    ): F[EnrichedFactoryChoice[SettlementFactory_SettleBatch]]

object RegistryBackend:

    /** A disclosed contract the submitter must attach so admin-owned contracts (factory rules,
      * account configs, locked holdings) are visible in its transaction.
      */
    type Disclosure = DisclosedContract

    /** Daml's `EnrichedFactoryChoice`: the factory contract to exercise on, the choice argument
      * with `extraArgs.context` filled in, and the disclosures to attach.
      *
      * `factoryCid` is an opaque contract-id string in Phase 1 (the stub never exercises it); Phase
      * 2 replaces it with a typed `ContractId` of the factory interface.
      */
    final case class EnrichedFactoryChoice[Arg](
        factoryCid: String,
        arg: Arg,
        disclosures: List[Disclosure],
    )

    enum Error(val message: String) extends RuntimeException(message):
        case FactoryNotFound(what: String) extends Error(s"factory not found: $what")
        case AccountConfigNotFound(account: String) extends Error(s"no account config for $account")
        case Http(status: Int, body: String) extends Error(s"registry HTTP $status: $body")
        case Decode(detail: String) extends Error(s"decode failure: $detail")
        case Unexpected(detail: String) extends Error(detail)
