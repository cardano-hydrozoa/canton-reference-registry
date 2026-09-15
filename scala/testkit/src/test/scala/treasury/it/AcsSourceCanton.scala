package treasury.it

import scala.jdk.CollectionConverters.*

import cats.effect.IO

import com.daml.ledger.javaapi.data.ContractFilter

import treasury.PartyId
import treasury.registry.service.*

import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.Account
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import daml.splice.testing.tokens.testtokenv2.TokenRules
import daml.splice.testing.tokens.testtokenv2.accountconfig.AccountConfig
import daml.splice.testing.tokens.testtokenv2.holding.Token

/** Canton-backed [[AcsSource]]: reads the registry admin's ACS over the Ledger API (with
  * created-event blobs) and maps the codegen contracts to the service's domain types. Runs in `IO`
  * — an unambiguous `MonadThrow` (unlike `EitherT[IO, Error, *]`), and [[RegistryService]] only
  * needs to raise `AssembleError`, which is a `RuntimeException`.
  *
  * The interface views (`allocation`/`allocationInstruction`/`transferInstruction`) and the
  * holding/locked disclosures all list-and-filter-by-cid rather than fetching per cid — see
  * `holdingDisclosures` for why (interface reads carry no usable blob; the rxjava by-cid fetch
  * predates Canton's mandatory `event_format`). The selected set is what the Daml
  * `queryInterfaceContractId`/`queryDisclosure'` yield per cid.
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
          _.map(d => toContract(d, AccountConfigPayload(d.contract.data.account)))
        )

    /** Disclose the given holdings via the `Token` *template* (as the Daml
      * `queryDisclosure' @Token` does), selecting them by contract id. Two Ledger-API constraints
      * force list-and-filter here (both diverge from the Daml's per-cid fetch): disclosures must
      * come from the template read, not the `Holding` interface — an interface-filtered read
      * carries no usable `createdEventBlob`, which the ledger rejects (`MISSING_FIELD:
      * DisclosedContract.createdEventBlob`); and rxjava's `EventQueryService.getEventsByContractId`
      * predates Canton's mandatory `event_format` (`MISSING_FIELD: event_format`), so there is no
      * usable by-cid fetch.
      */
    def holdingDisclosures(holdingCids: List[Cid]): IO[List[Disclosure]] =
        val wanted = holdingCids.map(_.value).toSet
        if wanted.isEmpty then IO.pure(Nil)
        else
            runIO(ledger.activeWithDisclosure(ContractFilter.of(Token.COMPANION), admin)).map(
              _.collect {
                  case h if wanted.contains(h.contractId) =>
                      Disclosure(
                        TemplateId(h.templateId),
                        Cid(h.contractId),
                        Blob(h.createdEventBlobBase64),
                        SynchronizerId(h.synchronizerId),
                      )
              }
            )

    /** Port of `getLockedTokensForAllocationsD`: the union of the named allocations' locked
      * holdings, disclosed via [[holdingDisclosures]].
      */
    def lockedHoldingDisclosures(allocationCids: List[Cid]): IO[List[Disclosure]] =
        val wanted = allocationCids.map(_.value).toSet
        for
            allocs <- runIO(ledger.activeWithDisclosure(Allocation.contractFilter(), admin))
            holdingCids = allocs
                .filter(a => wanted.contains(a.contractId))
                .flatMap(_.contract.data.holdingCids.asScala.toList.map(h => Cid(h.contractId)))
                .distinct
            discs <- holdingDisclosures(holdingCids)
        yield discs

    def allocation(cid: Cid): IO[AllocationDetails] =
        findByCid(Allocation.contractFilter(), cid).map { d =>
            val v = d.contract.data
            AllocationDetails(
              v.allocation.authorizer,
              v.holdingCids.asScala.toList.map(h => Cid(h.contractId)),
            )
        }

    def allocationInstruction(cid: Cid): IO[Account] =
        findByCid(AllocationInstruction.contractFilter(), cid)
            .map(_.contract.data.allocation.authorizer)

    def transferInstruction(cid: Cid): IO[TransferDetails] =
        findByCid(TransferInstruction.contractFilter(), cid).map { d =>
            val t = d.contract.data.transfer
            TransferDetails(
              t.sender,
              t.receiver,
              t.inputHoldingCids.asScala.toList.map(h => Cid(h.contractId)),
            )
        }

    /** Read one contract's interface view by cid (list-and-filter, per the class note). */
    private def findByCid[Ct](
        filter: ContractFilter[Ct],
        cid: Cid,
    ): IO[LedgerClientCanton.Disclosed[Ct]] =
        runIO(ledger.activeWithDisclosure(filter, admin)).flatMap { ds =>
            ds.find(_.contractId == cid.value)
                .fold(
                  IO.raiseError[LedgerClientCanton.Disclosed[Ct]](
                    new RuntimeException(s"contract not found by id: ${cid.value}")
                  )
                )(IO.pure)
        }

    private def toContract[Ct, A](d: LedgerClientCanton.Disclosed[Ct], payload: A): Contract[A] =
        Contract(
          Cid(d.contractId),
          TemplateId(d.templateId),
          payload,
          Blob(d.createdEventBlobBase64),
          SynchronizerId(d.synchronizerId),
        )

    private def runIO[A](c: CantonM[A]): IO[A] =
        c.value.flatMap(_.fold(IO.raiseError, IO.pure))
