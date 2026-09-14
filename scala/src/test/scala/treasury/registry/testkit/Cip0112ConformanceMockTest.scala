package treasury.registry.testkit

import cats.Monad
import cats.effect.{Clock, IO}
import cats.effect.unsafe.implicits.global
import cats.instances.either.*

import org.scalacheck.{Gen, Prop, Properties}

import treasury.PartyId
import treasury.TokenStandardHelpers
import treasury.TokenStandardHelpers.basicAccount
import treasury.registry.{RegistryApi, Tracer}
import treasury.registry.service.*

import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.holdingv2.Holding

/** Runs the [[Cip0112Conformance]] suite against the reference [[LocalRegistryApi]] over an
  * in-memory [[MockAcsSource]] — the pure P1–P4 tier (no ledger). `F = Either[Throwable, *]`, so a
  * property failure surfaces via `runToProp`'s fold. The live P5 tier runs the same suite against a
  * Canton-backed impl in the integration tests.
  */
object Cip0112ConformanceMockTest extends Properties("cip0112-conformance-mock"):

    private type EitherT[A] = Either[Throwable, A]
    private val eitherMonad: Monad[EitherT] = Monad[EitherT]

    private val admin = PartyId("adminTT2")
    private val parties = List(PartyId("alice"), PartyId("bob"))
    // requestedAt is a don't-care for context assembly; source one timestamp from cats-effect's Clock
    // (not java.time) at init rather than per generated arg.
    private val requestedAt = Clock[IO].realTimeInstant.unsafeRunSync()
    // Match the accounts the mock's AccountConfigs carry (basicAccount = owner only, id "").
    private def domainAccount(p: PartyId): Account = Account(Some(p), None, AccountId(""))

    // 3-part `pkg:Module:Entity` template ids, as LocalRegistryApi.parseIdentifier expects.
    private val rules: Contract[TokenRulesPayload] =
        Contract(
          Cid("rules"),
          TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:TokenRules"),
          (),
          Blob("cnJ1bGVz"), // base64("rules")
          SynchronizerId("sync-1"),
        )
    private val configs: List[Contract[AccountConfigPayload]] =
        parties.map(p =>
            Contract(
              Cid(s"cfg-${p.value}"),
              TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2.AccountConfig:AccountConfig"),
              AccountConfigPayload(domainAccount(p)),
              Blob("Y2Zn"), // base64("cfg")
              SynchronizerId("sync-1"),
            )
        )

    private val impl: RegistryApi[EitherT] =
        LocalRegistryApi[EitherT](
          RegistryService(MockAcsSource[EitherT](rules, configs)),
          Tracer.noop[EitherT, treasury.registry.RegistryApiEvent],
        )

    private val fixture: ConformanceFixture[EitherT] = new ConformanceFixture[EitherT]:
        given monad: Monad[EitherT] = eitherMonad
        def runToProp(fp: EitherT[Prop]): Prop = fp.fold(Prop.exception(_), identity)
        def allocationArgs: Gen[CidVariant[AllocationFactory_Allocate]] =
            Gen.oneOf(parties).map { p =>
                def mk(holdingCid: String): AllocationFactory_Allocate =
                    TokenStandardHelpers.allocationFactoryAllocate(
                      TokenStandardHelpers.settlementInfo(List(admin), "settlement-1"),
                      TokenStandardHelpers.allocationSpec(admin, p.basicAccount, Nil, false, None),
                      requestedAt,
                      List(new Holding.ContractId(holdingCid)),
                      List(admin),
                    )
                // Same authorizer, different input-holding cids → the prefetchability variant.
                CidVariant(mk("holding-1"), mk("holding-2"))
            }

    include(Cip0112Conformance.suite(impl, fixture))
