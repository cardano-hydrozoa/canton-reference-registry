package treasury.it

import scala.jdk.OptionConverters.*

import cats.effect.IO

import com.daml.ledger.javaapi.data.ContractFilter

import treasury.PartyId
import treasury.registry.service.*

import daml.splice.api.token.holdingv2.Account as DamlAccount
import daml.splice.testing.tokens.testtokenv2.TokenRules
import daml.splice.testing.tokens.testtokenv2.accountconfig.AccountConfig

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

    def lockedHoldingDisclosures(allocationCids: List[Cid]): IO[List[Disclosure]] = IO.pure(Nil)

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
