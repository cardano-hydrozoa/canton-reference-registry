package treasury.it

import scala.jdk.OptionConverters.*

import cats.effect.IO

import scala.jdk.CollectionConverters.*

import com.daml.ledger.javaapi.data.ContractFilter

import treasury.PartyId
import treasury.registry.service.*

import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Account as DamlAccount
import daml.splice.testing.tokens.testtokenv2.TokenRules
import daml.splice.testing.tokens.testtokenv2.accountconfig.AccountConfig
import daml.splice.testing.tokens.testtokenv2.holding.Token

/** Canton-backed [[AcsSource]]: reads the registry admin's ACS over the Ledger API (with
  * created-event blobs) and maps the codegen contracts to the service's domain [[Contract]]s. Runs
  * in `IO` — an unambiguous `MonadThrow` (unlike `EitherT[IO, Error, *]`), and [[RegistryService]]
  * only needs to raise `AssembleError`, which is a `RuntimeException`.
  *
  * `lockedHoldingDisclosures` (the settlement path, port of `getLockedTokensForAllocationsD`) is a
  * later slice; allocation-factory assembly does not use it.
  */
final class AcsSourceCanton(ledger: LedgerClientCanton, admin: PartyId) extends AcsSource[IO]:

    def tokenRules: IO[Contract[TokenRulesPayload]] =
        runIO(ledger.activeWithDisclosure(ContractFilter.of(TokenRules.COMPANION), admin)).flatMap {
            case one :: Nil => IO.pure(toContract(one, ()))
            case Nil        =>
                IO.raiseError(
                  new RuntimeException("no TokenRules in ACS — registry not initialized")
                )
            case many =>
                IO.raiseError(new RuntimeException(s"expected 1 TokenRules, found ${many.size}"))
        }

    def accountConfigs: IO[List[Contract[AccountConfigPayload]]] =
        runIO(ledger.activeWithDisclosure(ContractFilter.of(AccountConfig.COMPANION), admin)).map(
          _.map(d => toContract(d, AccountConfigPayload(toDomainAccount(d.contract.data.account))))
        )

    /** Port of `getLockedTokensForAllocationsD`: for each named allocation, read its `Allocation`
      * interface view to find the holdings it locked, then disclose those holdings so the settlement
      * submission can see the admin-owned locked tokens. The holdings are disclosed via the `Token`
      * *template* (as the Daml `queryDisclosure' @Token` does), not the `Holding` interface — an
      * interface-filtered ACS read carries no usable template `createdEventBlob`, which the ledger
      * rejects (`MISSING_FIELD: DisclosedContract.createdEventBlob`).
      */
    def lockedHoldingDisclosures(allocationCids: List[Cid]): IO[List[Disclosure]] =
        val wanted = allocationCids.map(_.value).toSet
        for
            allocs <- runIO(ledger.activeWithDisclosure(Allocation.contractFilter(), admin))
            lockedCids = allocs
                .filter(a => wanted.contains(a.contractId))
                .flatMap(_.contract.data.holdingCids.asScala.toList.map(_.contractId))
                .toSet
            holdings <- runIO(ledger.activeWithDisclosure(ContractFilter.of(Token.COMPANION), admin))
        yield holdings.collect {
            case h if lockedCids.contains(h.contractId) =>
                Disclosure(
                  TemplateId(h.templateId),
                  Cid(h.contractId),
                  Blob(h.createdEventBlobBase64),
                  SynchronizerId(h.synchronizerId),
                )
        }

    private def toContract[Ct, A](d: LedgerClientCanton.Disclosed[Ct], payload: A): Contract[A] =
        Contract(
          Cid(d.contractId),
          TemplateId(d.templateId),
          payload,
          Blob(d.createdEventBlobBase64),
          SynchronizerId(d.synchronizerId)
        )

    private def toDomainAccount(a: DamlAccount): Account =
        Account(
          a.owner.toScala.map(PartyId(_)),
          a.provider.toScala.map(PartyId(_)),
          AccountId(a.id)
        )

    private def runIO[A](c: CantonM[A]): IO[A] =
        c.value.flatMap(_.fold(IO.raiseError, IO.pure))
