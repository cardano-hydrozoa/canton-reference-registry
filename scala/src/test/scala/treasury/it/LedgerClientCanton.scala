package treasury.it

import java.util.{Base64, Optional, UUID}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import cats.data.EitherT
import cats.effect.IO

import com.daml.ledger.javaapi.data.{ActiveContract, CommandsSubmission, ContractFilter, CreatedEvent, Identifier}
import com.daml.ledger.javaapi.data.codegen.HasCommands
import com.daml.ledger.rxjava.DamlLedgerClient
import io.reactivex.Single

import treasury.PartyId
import treasury.registry.RegistryBackend.Error

import daml.splice.testing.tokens.testtokenv2.TokenRules

/** Canton effect: IO with the domain error in an Either base, so the flow's `MonadError[F, Error]`
  * is satisfied (plain IO only has `MonadError[IO, Throwable]`).
  */
type CantonM[A] = EitherT[IO, Error, A]

/** Ledger-API client against a live Canton participant (Phase 2). Wraps the rxjava
  * `DamlLedgerClient`; each call runs the blocking rx call on IO and maps failures to `Error`.
  *
  * Slice 2b-1: the submission + typed-ACS-read foundation (create a contract, read it back). The
  * `LedgerClient` trait methods (factory exercises, balance reads) build on these primitives next.
  */
final class LedgerClientCanton private (client: DamlLedgerClient, userId: String):

    /** Create the registry's TokenRules contract, returning its id. Ledger API v2 here does not
      * implement SubmitAndWaitForTransactionTree, so we submit-and-wait then read the cid back from
      * the ACS.
      */
    def createTokenRules(admin: PartyId): CantonM[TokenRules.ContractId] =
        for
            _ <- submitAndWait(admin, List(TokenRules.create(admin)))
            rules <- activeContractsOf(ContractFilter.of(TokenRules.COMPANION), admin)
            cid <- EitherT.fromEither[IO](
              rules.headOption
                  .map(_.id)
                  .toRight(Error.Unexpected("TokenRules not in ACS after create"))
            )
        yield cid

    /** Typed ACS query for a template/interface, as seen by `readAs`. */
    def activeContractsOf[Ct](filter: ContractFilter[Ct], readAs: PartyId): CantonM[List[Ct]] =
        for
            end <- single(client.getStateClient.getLedgerEnd)
            batches <- blocking(
              client.getStateClient
                  .getActiveContracts(filter, Set(readAs).asJava, false, end)
                  .blockingIterable()
                  .asScala
                  .toList
            )
        yield batches.flatMap(_.activeContracts.asScala.toList)

    /** ACS query that also returns each contract's disclosure metadata: the decoded contract plus
      * the `createdEventBlob` (base64), fully-qualified template id, contract id and synchronizer
      * id — i.e. everything needed to build a wire `DisclosedContract`. Uses the raw `EventFormat`
      * overload with `includeCreatedEventBlob`, which the typed overload does not expose.
      */
    def activeWithDisclosure[Ct](
        filter: ContractFilter[Ct],
        readAs: PartyId,
    ): CantonM[List[LedgerClientCanton.Disclosed[Ct]]] =
        val fmt =
            filter.withIncludeCreatedEventBlob(true).eventFormat(Optional.of(Set(readAs).asJava))
        for
            end <- single(client.getStateClient.getLedgerEnd)
            responses <- blocking(
              client.getStateClient.getActiveContracts(fmt, end).blockingIterable().asScala.toList
            )
        yield responses.flatMap { r =>
            r.getContractEntry.toScala.collect { case ac: ActiveContract =>
                val ce: CreatedEvent = ac.getCreatedEvent
                LedgerClientCanton.Disclosed(
                  contract = filter.toContract(ce),
                  contractId = ce.getContractId,
                  templateId = LedgerClientCanton.identifierString(ce.getTemplateId),
                  createdEventBlobBase64 =
                      Base64.getEncoder.encodeToString(ce.getCreatedEventBlob.toByteArray),
                  synchronizerId = ac.getSynchronizerId,
                )
            }
        }

    private def submitAndWait(actAs: PartyId, cmds: List[HasCommands]): CantonM[Unit] =
        val submission = CommandsSubmission
            .create(userId, UUID.randomUUID().toString, Optional.empty(), cmds.asJava)
            .withActAs(actAs)
        single(client.getCommandClient.submitAndWait(submission)).map(_ => ())

    private def single[A](s: => Single[A]): CantonM[A] = blocking(s.blockingGet())

    private def blocking[A](a: => A): CantonM[A] =
        EitherT(
          IO.blocking(a)
              .attempt
              .map(_.left.map(t => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))))
        )

    def close(): Unit = client.close()

object LedgerClientCanton:
    /** A contract read from the ACS together with the data needed to disclose it. */
    final case class Disclosed[Ct](
        contract: Ct,
        contractId: String,
        templateId: String,
        createdEventBlobBase64: String,
        synchronizerId: String,
    )

    /** Fully-qualified template id `<pkgId>:<Module>:<Entity>`, as used in `DisclosedContract`. */
    def identifierString(id: Identifier): String =
        s"${id.getPackageId}:${id.getModuleName}:${id.getEntityName}"

    def connect(host: String, port: Int, userId: String = "treasury-it"): LedgerClientCanton =
        val client = DamlLedgerClient.newBuilder(host, port).build()
        client.connect()
        new LedgerClientCanton(client, userId)
