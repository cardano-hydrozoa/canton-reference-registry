package treasury.registry

/** Events emitted by a [[RegistryApi]]. Split by implementation the way hydrozoa's
  * `CardanoBackendEvent` is (mock- vs Blockfrost-specific), so each backend traces its own
  * concerns.
  */
enum RegistryApiEvent:
    // Stub backend (pure tests)
    case StubReturnedCannedChoice(kind: String)

    // HTTP/OpenAPI backend (testnet) — populated in Phase 3
    case HttpRequested(path: String)
    case HttpFailed(path: String, status: Int)
