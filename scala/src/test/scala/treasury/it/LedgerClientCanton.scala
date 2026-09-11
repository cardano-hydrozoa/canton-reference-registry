package treasury.it

import java.util.{Optional, UUID}
import scala.jdk.CollectionConverters.*

import cats.data.EitherT
import cats.effect.IO

import com.daml.ledger.javaapi.data.{CommandsSubmission, ContractFilter}
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
              rules.headOption.map(_.id).toRight(Error.Unexpected("TokenRules not in ACS after create"))
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

    private def submitAndWait(actAs: PartyId, cmds: List[HasCommands]): CantonM[Unit] =
        val submission = CommandsSubmission
            .create(userId, UUID.randomUUID().toString, Optional.empty(), cmds.asJava)
            .withActAs(actAs)
        single(client.getCommandClient.submitAndWait(submission)).map(_ => ())

    private def single[A](s: => Single[A]): CantonM[A] = blocking(s.blockingGet())

    private def blocking[A](a: => A): CantonM[A] =
        EitherT(
          IO.blocking(a).attempt.map(_.left.map(t => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))))
        )

    def close(): Unit = client.close()

object LedgerClientCanton:
    def connect(host: String, port: Int, userId: String = "treasury-it"): LedgerClientCanton =
        val client = DamlLedgerClient.newBuilder(host, port).build()
        client.connect()
        new LedgerClientCanton(client, userId)
