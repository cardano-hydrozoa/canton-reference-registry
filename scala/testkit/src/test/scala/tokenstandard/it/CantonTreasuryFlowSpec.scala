package tokenstandard.it

import cats.data.EitherT
import cats.effect.Clock
import cats.effect.IO
import cats.effect.Resource
import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.metadatav1.Metadata
import daml.splice.api.token.transferinstructionv2.TransferFactory_Transfer
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.PartyId
import tokenstandard.TreasuryEnv
import tokenstandard.TreasuryFlow
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.ledger.CantonM
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.EnrichedFactoryChoice
import tokenstandard.registry.RegistryApi.Error
import tokenstandard.registry.RegistryApi.OpenApiChoiceContext
import tokenstandard.registry.service.LocalRegistryApi
import tokenstandard.registry.service.RegistryService

/** The Phase-2 milestone: run the UNMODIFIED [[TreasuryFlow]] — the Scala port of
  * `TestHydrozoaTreasury`, balance checkpoints and all — against a live Canton, with all three
  * production pieces wired together: [[LocalRegistryApi]] over [[AcsSourceCanton]] (registry) and
  * [[LedgerClientCanton]] (ledger). The very same flow code runs green over the in-memory pair in
  * `TreasuryFlowSpec`; this proves the ports are interchangeable.
  *
  * The registry stack runs in `IO` (see [[AcsSourceCanton]] for why not `CantonM`), the ledger in
  * `CantonM`; the flow needs one `F`, so [[RegistryApiCanton]] lifts the registry into `CantonM`.
  * Gated on `CANTON_IT=1` (see [[CantonSmokeSpec]] for the sandbox run recipe).
  */
class CantonTreasuryFlowSpec extends AsyncFunSuite, AsyncIOSpec:

    private val cantonContainer: Resource[IO, GenericContainer] =
        Resource.make(IO.blocking { val c = CantonContainer(); c.start(); c })(c =>
            IO.blocking(c.stop())
        )

    private def ledgerClient(host: String, port: Int): Resource[IO, LedgerClientCanton] =
        Resource.make(IO.blocking(LedgerClientCanton.connect(host, port)))(l =>
            IO.blocking(l.close())
        )

    private def run[A](c: CantonM[A]): IO[A] = c.value.flatMap(IO.fromEither)

    /** Lift the IO-based reference registry into the flow's `CantonM` (RegistryApi errors pass
      * through; anything else is wrapped as `Unexpected`).
      */
    private final class RegistryApiCanton(underlying: RegistryApi[IO]) extends RegistryApi[CantonM]:
        private def lift[A](io: IO[A]): CantonM[A] =
            EitherT(io.attempt.map(_.left.map {
                case e: Error => e
                case t        => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))
            }))

        override def getTransferFactory(
            arg: TransferFactory_Transfer
        ): CantonM[EnrichedFactoryChoice[TransferFactory_Transfer]] =
            lift(underlying.getTransferFactory(arg))
        override def getAllocationFactory(
            arg: AllocationFactory_Allocate
        ): CantonM[EnrichedFactoryChoice[AllocationFactory_Allocate]] =
            lift(underlying.getAllocationFactory(arg))
        override def getSettlementFactory(
            arg: SettlementFactory_SettleBatch
        ): CantonM[EnrichedFactoryChoice[SettlementFactory_SettleBatch]] =
            lift(underlying.getSettlementFactory(arg))
        override def getAllocationWithdrawContext(
            allocation: Allocation.ContractId,
            meta: Metadata,
        ): CantonM[OpenApiChoiceContext] =
            lift(underlying.getAllocationWithdrawContext(allocation, meta))
        override def getAllocationCancelContext(
            allocation: Allocation.ContractId,
            meta: Metadata,
        ): CantonM[OpenApiChoiceContext] =
            lift(underlying.getAllocationCancelContext(allocation, meta))
        override def getAllocationInstructionWithdrawContext(
            instruction: AllocationInstruction.ContractId,
            meta: Metadata,
        ): CantonM[OpenApiChoiceContext] =
            lift(underlying.getAllocationInstructionWithdrawContext(instruction, meta))
        override def getAllocationInstructionAcceptContext(
            instruction: AllocationInstruction.ContractId,
            meta: Metadata,
        ): CantonM[OpenApiChoiceContext] =
            lift(underlying.getAllocationInstructionAcceptContext(instruction, meta))
        override def getTransferInstructionAcceptContext(
            instruction: TransferInstruction.ContractId,
            meta: Metadata,
        ): CantonM[OpenApiChoiceContext] =
            lift(underlying.getTransferInstructionAcceptContext(instruction, meta))
        override def getTransferInstructionRejectContext(
            instruction: TransferInstruction.ContractId,
            meta: Metadata,
        ): CantonM[OpenApiChoiceContext] =
            lift(underlying.getTransferInstructionRejectContext(instruction, meta))
        override def getTransferInstructionWithdrawContext(
            instruction: TransferInstruction.ContractId,
            meta: Metadata,
        ): CantonM[OpenApiChoiceContext] =
            lift(underlying.getTransferInstructionWithdrawContext(instruction, meta))

    test("TreasuryFlow (port of TestHydrozoaTreasury) runs green on live Canton"):
        assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests"
        )

        val partyHints = List("adminTF", "providerTF", "aliceTF", "bobTF", "hydrozoaTF")
        val setup =
            for
                container <- cantonContainer
                port <- Resource.eval(
                  IO.blocking(container.mappedPort(CantonContainer.LedgerApiPort))
                )
                parties <- Resource.eval(
                  IO.blocking(CantonParties.allocate("localhost", port, partyHints))
                )
                ledger <- ledgerClient("localhost", port)
            yield (parties, ledger)

        setup
            .use { (parties: Map[String, PartyId], ledger: LedgerClientCanton) =>
                val env = TreasuryEnv(
                  admin = parties("adminTF"),
                  provider = parties("providerTF"),
                  alice = parties("aliceTF"),
                  bob = parties("bobTF"),
                  hydrozoa = parties("hydrozoaTF"),
                )
                val registry = RegistryApiCanton(
                  LocalRegistryApi[IO](RegistryService(AcsSourceCanton(ledger, env.admin)))
                )
                val flow = new TreasuryFlow[CantonM](registry, ledger)
                for
                    now <- Clock[IO].realTimeInstant
                    _ <- run(ledger.createTokenRules(env.admin))
                    // The flow's precondition: alice holds 1000 X, bob holds 1000 Y.
                    _ <- run(ledger.mint(env.admin, env.alice, "X", BigDecimal(1000), now))
                    _ <- run(ledger.mint(env.admin, env.bob, "Y", BigDecimal(1000), now))
                    // The flow carries every TestHydrozoaTreasury balance checkpoint internally;
                    // reaching the end means all of them held on the live ledger.
                    _ <- run(flow.run(env, now))
                yield ()
            }
            .asserting(_ => succeed)
