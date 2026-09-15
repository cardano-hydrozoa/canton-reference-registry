package tokenstandard.registry.service

import cats.syntax.all.*
import daml.splice.api.token.holdingv2.Account
import tokenstandard.registry.service.CtxValue.CtxContractId
import tokenstandard.registry.service.CtxValue.CtxList

/** Pure port of `TestTokenV2_RegistryV2`'s off-ledger context assembly —
  * `getAccountMapAndTokenRulesC` and the factory methods that call it. No effects: given the
  * contracts already read from the ledger, it builds the [[ContextBundle]]. This is the conformance
  * target (Tier-1 asserts these outputs structurally against the Daml source; Tier-2 diffs them
  * against the live Daml-script registry).
  */
object Assemble:

    /** Port of `getAccountMapAndTokenRulesC`. For the requested `accounts`, select each account's
      * config from `allConfigs` (matching on `config.account`, dropping accounts with none,
      * erroring on duplicates — cf. `getAccountConfig'`), then build:
      *   - the choice context: `accountConfigsContextKey` -> `AV_List` of the matched config cids
      *     in `accounts` order; `tokenRulesContextKey` -> the rules cid, and
      *   - the disclosures: the rules blob followed by each matched config blob, then
      *     `extraDisclosures` (the settlement path's locked-holding blobs).
      *
      * `factoryId` is the `TokenRules` cid (the registry exercises the factory choices on it).
      */
    def contextBundle(
        tokenRules: Contract[TokenRulesPayload],
        allConfigs: List[Contract[AccountConfigPayload]],
        accounts: List[Account],
        extraDisclosures: List[Disclosure] = Nil,
    ): Either[AssembleError, ContextBundle] =
        // Per account: its unique config, or None if absent (catOptionals drops it); >1 aborts.
        // traverse over Either short-circuits on the first duplicate, as the Daml `abort` does.
        accounts.traverse(matchConfig(allConfigs, _)).map { matched =>
            val configs = matched.flatten
            val values = Map(
              ContextKeys.accountConfigs -> CtxList(configs.map(c => CtxContractId(c.cid))),
              ContextKeys.tokenRules -> CtxContractId(tokenRules.cid),
            )
            val disclosures =
                tokenRules.disclosure :: configs.map(_.disclosure) ::: extraDisclosures
            ContextBundle(tokenRules.cid, values, disclosures)
        }

    /** The (at most one) config whose account matches, per Daml `getAccountConfig'`: none ->
      * `None`, one -> `Some`, many -> abort.
      */
    private def matchConfig(
        allConfigs: List[Contract[AccountConfigPayload]],
        account: Account,
    ): Either[AssembleError, Option[Contract[AccountConfigPayload]]] =
        allConfigs.filter(_.payload.account == account) match
            case Nil      => Right(None)
            case c :: Nil => Right(Some(c))
            case _        => Left(AssembleError.DuplicateAccountConfig(account))

    /** Port of `registryApi_getAllocationFactoryV2`: the single account is the allocation
      * authorizer.
      */
    def allocationFactory(
        tokenRules: Contract[TokenRulesPayload],
        allConfigs: List[Contract[AccountConfigPayload]],
        authorizer: Account,
    ): Either[AssembleError, ContextBundle] =
        contextBundle(tokenRules, allConfigs, List(authorizer))

    /** Port of `registryApi_getSettlementFactoryV2`: `accounts` are the sender+receiver of every
      * transfer leg (the caller extracts them off `SettlementFactory_SettleBatch.transferLegs`),
      * dedup'd here; disclosures additionally include the locked holdings of each allocation
      * (already resolved by the caller via [[AcsSource.lockedHoldingDisclosures]]).
      */
    def settlementFactory(
        tokenRules: Contract[TokenRulesPayload],
        allConfigs: List[Contract[AccountConfigPayload]],
        accounts: List[Account],
        lockedHoldings: List[Disclosure],
    ): Either[AssembleError, ContextBundle] =
        // dedup (Daml `DA.List.dedup`) = order-preserving, first-occurrence; List.distinct matches.
        contextBundle(tokenRules, allConfigs, accounts.distinct, lockedHoldings)
