package tokenstandard.it

import cats.data.EitherT
import cats.effect.IO
import com.daml.ledger.javaapi.data.ContractFilter
import daml.splice.api.token.holdingv2.Account as DamlAccount
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import daml.splice.api.token.metadatav1.AnyContract
import daml.splice.api.token.metadatav1.AnyValue
import daml.splice.api.token.metadatav1.ChoiceContext
import daml.splice.api.token.metadatav1.ExtraArgs
import daml.splice.api.token.metadatav1.anyvalue.AV_ContractId
import daml.splice.api.token.metadatav1.anyvalue.AV_List
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import daml.splice.api.token.transferinstructionv2.TransferInstruction_Accept
import daml.splice.testing.tokens.testtokenv2.TokenRules
import daml.splice.testing.tokens.testtokenv2.TokenRules_OfferMint
import daml.splice.testing.tokens.testtokenv2.accountconfig.AccountConfig
import daml.splice.testing.tokens.testtokenv2.accountconfig.PartyConfig
import daml.splice.testing.tokens.testtokenv2.transfer.TokenTransferOffer
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers
import tokenstandard.ledger.CantonM
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.RegistryApi.Error
import tokenstandard.registry.service.ContextKeys

import java.util.Optional
import scala.jdk.CollectionConverters.*

/** TestTokenV2 registry-harness operations over a [[LedgerClientCanton]]: deploy the registry's
  * `TokenRules` and mint holdings. Test-scope concerns (the harness sets the registry up); the
  * client itself stays registry-agnostic.
  */
object CantonTestTokenOps:

    extension (ledger: LedgerClientCanton)

        /** Create the registry's TokenRules contract, returning its id. Ledger API v2 here does not
          * implement SubmitAndWaitForTransactionTree, so we submit-and-wait then read the cid back
          * from the ACS.
          */
        def createTokenRules(admin: PartyId): CantonM[TokenRules.ContractId] =
            for
                _ <- ledger.submitAndWait(admin, List(TokenRules.create(admin.value)))
                rules <- ledger.activeContractsOf(ContractFilter.of(TokenRules.COMPANION), admin)
                cid <- EitherT.fromEither[IO](
                  rules.headOption
                      .map(_.id)
                      .toRight(Error.Unexpected("TokenRules not in ACS after create"))
                )
            yield cid

        /** Mint `amount` of instrument `instrumentName` into `owner`'s basic (owner-only) account,
          * returning the resulting unlocked [[Holding]] cids — the funding for a sender-side
          * allocation. Two ledger steps mirroring `TestTokenV2_RegistryV2.mint`: the admin offers
          * the mint (`TokenRules_OfferMint`), then the owner accepts the resulting
          * `TransferInstruction`.
          *
          * A basic-account mint stores no `AccountConfig` (the config is passed inline to the
          * offer) and locks no input holdings, so the accept's choice context is just the two
          * standard keys — `tokenRules` and an empty `accountConfigs` list — plus the TokenRules
          * disclosure; no `getTransferInstruction_AcceptContext` registry round-trip is needed. The
          * offer cid is read back from the ACS (this Ledger API build has no
          * submit-and-wait-for-transaction-tree).
          */
        def mint(
            admin: PartyId,
            owner: PartyId,
            instrumentName: String,
            amount: BigDecimal,
            offeredAt: java.time.Instant,
        ): CantonM[List[Holding.ContractId]] =
            val receiver = new DamlAccount(Optional.of(owner.value), Optional.empty(), "")
            val instrument = new InstrumentId(admin.value, instrumentName)
            for
                rulesD <- ledger
                    .activeWithDisclosure(ContractFilter.of(TokenRules.COMPANION), admin)
                    .map(_.head)
                _ <- ledger.submitAndWait(
                  admin,
                  List(
                    new TokenRules.ContractId(rulesD.contractId).exerciseTokenRules_OfferMint(
                      new TokenRules_OfferMint(
                        receiver,
                        amount.bigDecimal,
                        instrument,
                        offeredAt,
                        new AccountConfig(
                          admin.value,
                          receiver,
                          new PartyConfig(true, true),
                          new PartyConfig(false, false),
                        ),
                      )
                    )
                  ),
                  Nil,
                )
                offers <- ledger.activeContractsOf(
                  ContractFilter.of(TokenTransferOffer.COMPANION),
                  admin,
                )
                // The mint is a transfer from a special `cip-112/mint` account (principal = admin) to
                // the receiver: admin pre-authorizes TIA_Accept as the offerer, leaving the receiver's
                // party to accept. So `owner` must be a non-admin party — if receiver == admin, offerer
                // and acceptor collapse, the offer reaches TIS_Accepted at OfferMint time, and there is
                // no TIA_Accept left to exercise.
                offerCid <- EitherT.fromEither[IO](
                  offers.headOption
                      .map(_.id)
                      .toRight(Error.Unexpected("no TokenTransferOffer in ACS after OfferMint"))
                )
                ctx = new ChoiceContext(
                  Map[String, AnyValue](
                    ContextKeys.accountConfigs -> new AV_List(List.empty[AnyValue].asJava),
                    ContextKeys.tokenRules -> new AV_ContractId(
                      new AnyContract.ContractId(rulesD.contractId)
                    ),
                  ).asJava
                )
                _ <- ledger.submitAndWait(
                  owner,
                  List(
                    new TransferInstruction.ContractId(offerCid.contractId)
                        .exerciseTransferInstruction_Accept(
                          new TransferInstruction_Accept(
                            List(owner.value).asJava,
                            new ExtraArgs(ctx, TokenStandardHelpers.emptyMetadata),
                          )
                        )
                  ),
                  List(LedgerClientCanton.toDisclosedContract(rulesD)),
                )
                holdings <- ledger.activeContractsOf(Holding.contractFilter(), owner)
            yield holdings
                .filter(c => c.data.instrumentId == instrument && c.data.lock.isEmpty)
                .map(_.id)
