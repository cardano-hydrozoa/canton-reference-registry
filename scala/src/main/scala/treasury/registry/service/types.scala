package treasury.registry.service

import treasury.PartyId

/** Domain types for the TestTokenV2 registry service — the Scala port of Splice's
  * `TestTokenV2_RegistryV2` off-ledger API. Deliberately lightweight (no Daml Java codegen types)
  * so the pure [[Assemble]] core and its conformance tests carry no codegen dependency; the codegen
  * types appear only at the edges (the Canton [[AcsSource]] and the HTTP layer).
  */

// Identifier types: each is an opaque `String` (with an `apply` constructor and `value` extractor),
// so distinct identifiers sharing the same representation can never be transposed. Party ids stay
// [[treasury.PartyId]], the project-wide alias.

/** A registry-local account identifier (`HoldingV2.Account.id`). */
opaque type AccountId = String
object AccountId:
    def apply(s: String): AccountId = s
    extension (a: AccountId) def value: String = a

/** An instrument's textual identifier (`HoldingV2.InstrumentId.id`). */
opaque type InstrumentId = String
object InstrumentId:
    def apply(s: String): InstrumentId = s
    extension (i: InstrumentId) def value: String = i

/** Port of Splice `Splice.Api.Token.HoldingV2.Account`: a holding account at a registry. `owner`/
  * `provider` are the controlling parties (both optional, as in the standard — the registry's own
  * "special" accounts have neither).
  */
final case class Account(owner: Option[PartyId], provider: Option[PartyId], id: AccountId)

/** Port of `HoldingV2.InstrumentId`: an instrument = admin party + textual `id`. Defined for the
  * transfer/settlement surface; not yet read by the flow.
  */
final case class Instrument(admin: PartyId, id: InstrumentId)

/** One leg of a settlement batch: move the instrument from `sender` to `receiver`. Mirrors the
  * `sender`/`receiver` accounts read off `SettlementFactory_SettleBatch.transferLegs` in the Daml
  * `registryApi_getSettlementFactoryV2`.
  */
final case class TransferLeg(sender: Account, receiver: Account)

/** A contract id as it appears on the wire and inside `AnyValue` context values. Opaque so cids and
  * disclosure blobs (both `String`) can't be transposed.
  */
opaque type Cid = String
object Cid:
    def apply(s: String): Cid = s
    extension (c: Cid) def value: String = c

/** The serialized created-event of a contract, forwarded verbatim as a Daml-JSON-API
  * `DisclosedContract.createdEventBlob`. Opaque here: the Canton [[AcsSource]] fills it from an ACS
  * query with `includeCreatedEventBlob`; mock sources use any stable token.
  */
opaque type Blob = String
object Blob:
    def apply(s: String): Blob = s
    extension (b: Blob) def value: String = b

/** A fully-qualified template id `<pkgId>:<Module>:<Entity>` (`DisclosedContract.templateId`). */
opaque type TemplateId = String
object TemplateId:
    def apply(s: String): TemplateId = s
    extension (t: TemplateId) def value: String = t

/** The synchronizer a contract is assigned to (`DisclosedContract.synchronizerId`). */
opaque type SynchronizerId = String
object SynchronizerId:
    def apply(s: String): SynchronizerId = s
    extension (s: SynchronizerId) def value: String = s

/** Everything needed to render a wire `DisclosedContract` (all four required fields of the OpenAPI
  * schema): the submitter attaches these so the registry's admin-owned contracts are visible in its
  * transaction. Port of an entry of Daml `Disclosures'`.
  */
final case class Disclosure(
    templateId: TemplateId,
    contractId: Cid,
    createdEventBlob: Blob,
    synchronizerId: SynchronizerId,
)

/** A contract read from the ACS: its id and payload (the assembly branches on the payload), plus
  * the fields needed to disclose it. `templateId`/`synchronizerId` come from the Ledger-API active
  * contract; `createdEventBlob` from an `includeCreatedEventBlob` query.
  */
final case class Contract[+A](
    cid: Cid,
    templateId: TemplateId,
    payload: A,
    createdEventBlob: Blob,
    synchronizerId: SynchronizerId,
):
    def disclosure: Disclosure = Disclosure(templateId, cid, createdEventBlob, synchronizerId)

/** The only `AccountConfig` field the context assembly reads: the account it configures (used to
  * match configs to requested accounts, cf. Daml `getAccountConfig'`'s
  * `config.account == account`). The on-ledger contract also carries admin/owner/provider configs;
  * those ride along in the `blob`.
  */
final case class AccountConfigPayload(account: Account)

/** `TokenRules` exposes no field the assembly reads — only its cid (the factory contract) and blob
  * are needed — so its payload is modelled as `Unit`.
  */
type TokenRulesPayload = Unit

/** The registry's slice of Splice `MetadataV1.AnyValue`: only the constructors this registry emits
  * into a `ChoiceContext`. Kept as our own ADT so the pure core stays codegen-free; the HTTP layer
  * renders it to `choiceContextData` (the Daml-JSON-API encoding of `AnyValue`).
  */
enum CtxValue:
    case CtxContractId(cid: Cid) // AV_ContractId
    case CtxList(items: List[CtxValue]) // AV_List

/** The assembled off-ledger result for a factory choice: the factory contract to exercise, the
  * choice-context values (keyed by the registry's context keys), and the disclosures to attach.
  * Port of Daml `EnrichedFactoryChoice` minus the choice argument, which the client owns on the
  * HTTP boundary (the `/factory` endpoint returns only `factoryId` + `choiceContext`).
  */
final case class ContextBundle(
    factoryId: Cid,
    values: Map[String, CtxValue],
    disclosures: List[Disclosure],
)

/** The TestTokenV2 registry's choice-context keys. Both are registry-implementation-specific (not
  * CIP standard schema), taken verbatim from Splice source: `tokenRulesContextKey` in
  * `TestTokenV2.Util`, `accountConfigsContextKey` in `TestTokenV2.AccountConfig`.
  */
object ContextKeys:
    val tokenRules: String = "testTokenV2/tokenRules"
    val accountConfigs: String = "testTokenV2/accountConfigs"

/** Errors raised by the pure assembly. Extends `RuntimeException` so the F-level service can raise
  * it via `MonadThrow` without a wrapper (mirrors `RegistryApi.Error`).
  */
enum AssembleError(val message: String) extends RuntimeException(message):
    /** Daml `getAccountConfig'` aborts when more than one config matches an account. */
    case DuplicateAccountConfig(account: Account)
        extends AssembleError(s"more than one account config for $account")
