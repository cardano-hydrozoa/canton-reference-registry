package tokenstandard.engine

import cats.data.StateT
import com.daml.ledger.javaapi.data as japi
import com.digitalasset.daml.lf.archive.DarDecoder
import com.digitalasset.daml.lf.command.ApiCommand
import com.digitalasset.daml.lf.command.ApiCommands
import com.digitalasset.daml.lf.crypto
import com.digitalasset.daml.lf.data.Bytes
import com.digitalasset.daml.lf.data.FrontStack
import com.digitalasset.daml.lf.data.ImmArray
import com.digitalasset.daml.lf.data.Numeric
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.SortedLookupList
import com.digitalasset.daml.lf.data.Time
import com.digitalasset.daml.lf.engine.*
import com.digitalasset.daml.lf.language.Ast
import com.digitalasset.daml.lf.language.LanguageVersion
import com.digitalasset.daml.lf.transaction.CreationTime
import com.digitalasset.daml.lf.transaction.FatContractInstance
import com.digitalasset.daml.lf.transaction.NextGenContractStateMachine
import com.digitalasset.daml.lf.transaction.Node
import com.digitalasset.daml.lf.transaction.SubmittedTransaction
import com.digitalasset.daml.lf.value.ContractIdVersion
import com.digitalasset.daml.lf.value.Value as Lf
import tokenstandard.registry.RegistryApi.Error

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The in-process contract store the [[EngineLedger]] and [[EngineAcsSource]] share: the live
  * contracts by id, plus a monotonic counter that seeds each submission's (deterministic) contract
  * ids. A single global store — no per-party visibility — so disclosures are unnecessary (every
  * contract is resolvable), unlike a real participant.
  */
final case class EngineStore(contracts: Map[Lf.ContractId, FatContractInstance], seed: Int)
object EngineStore:
    val empty: EngineStore = EngineStore(Map.empty, 0)

/** Ledger effect: a pure state transition over the [[EngineStore]] with the domain error in the
  * `Either` base — the same shape the hand-written in-memory ledger had, so the flows still run as
  * a deterministic `State` transition with no IO runtime.
  */
type EngineM[A] = StateT[[X] =>> Either[Error, X], EngineStore, A]

/** Registry effect over the same store: `MonadThrow` (error = `Throwable`), as `RegistryService` /
  * `LocalRegistryApi` require to raise `AssembleError`. Bridged into [[EngineM]] by
  * `EngineRegistry` (the same store threads through; a `Throwable` Left maps to `Error`).
  */
type RegEngineM[A] = StateT[[X] =>> Either[Throwable, X], EngineStore, A]

/** The real Daml interpreter (`daml-lf-engine`) loaded over the vendored DARs, wrapped so callers
  * submit codegen commands and read contracts back without touching LF internals. Load once with
  * [[DamlEngine.load]] (decodes ~20 DARs); the result is immutable and threads only the
  * [[EngineStore]] through [[EngineM]].
  *
  * This is the faithful counterpart of `LedgerClientCanton`'s participant: real interpretation and
  * authorization, run in-process. See [[EngineLedger]] for the `LedgerClient` on top.
  */
final class DamlEngine private (
    engine: Engine,
    packages: Map[Ref.PackageId, Ast.Package],
    packageMap: Map[Ref.PackageId, (Ref.PackageName, Ref.PackageVersion)],
    packagePreference: Set[Ref.PackageId],
):
    private given com.digitalasset.canton.tracing.TraceContext =
        com.digitalasset.canton.tracing.TraceContext.empty
    private val participant = Ref.ParticipantId.assertFromString("engine")

    /** Interpret `commands` as one atomic transaction authorized by `submitters`, apply its effects
      * (creates filed, consumed contracts removed) to the store, and return the transaction. The
      * submission seed is drawn from the store counter, so contract ids are deterministic and
      * distinct across submits.
      */
    def submit(
        submitters: Set[Ref.Party],
        commands: List[ApiCommand]
    ): EngineM[SubmittedTransaction] =
        StateT { store =>
            val seedStr = s"engine-submit-${store.seed}"
            val result = consume(
              store,
              engine.submit(
                packageMap = packageMap,
                packagePreference = packagePreference,
                submitters = submitters,
                readAs = Set.empty,
                cmds = ApiCommands(
                  ImmArray.from(commands),
                  Time.Timestamp.assertFromInstant(DamlEngine.LedgerTime),
                  seedStr,
                ),
                participantId = participant,
                submissionSeed = crypto.Hash.hashPrivateKey(seedStr),
                contractIdVersion = ContractIdVersion.V1,
                contractStateMode = NextGenContractStateMachine.Mode.NUCK,
                prefetchKeys = Seq.empty,
              ),
            )
            result.flatMap { case (tx, _) =>
                // The engine emits LOCAL (unsuffixed) V1 contract ids; a real participant suffixes
                // them so later fetches resolve. Suffix the whole transaction (nodes + argument
                // values + choice results) with a constant suffix — discriminators are globally
                // unique (distinct submission seed per submit), so the absolute cids stay unique and
                // the flow sees the same suffixed cids we store.
                tx.suffixCid(_ => DamlEngine.SuffixBytes, _ => DamlEngine.SuffixBytes)
                    .left
                    .map(e => Error.Unexpected(s"cid suffixing: $e"))
                    .map { stx =>
                        val contracts = stx.nodes.values.foldLeft(store.contracts) {
                            case (acc, c: Node.Create) =>
                                acc.updated(
                                  c.coid,
                                  FatContractInstance.fromCreateNode(
                                    c,
                                    CreationTime.CreatedAt(
                                      Time.Timestamp.assertFromInstant(DamlEngine.LedgerTime)
                                    ),
                                    Bytes.Empty,
                                  ),
                                )
                            case (acc, e: Node.Exercise) if e.consuming => acc.removed(e.targetCoid)
                            case (acc, _)                               => acc
                        }
                        (EngineStore(contracts, store.seed + 1), SubmittedTransaction(stx))
                    }
            }
        }

    /** The root exercise results of `tx`, in command order (the transaction's `roots` preserve
      * submission order — no node-id sorting needed, unlike the Ledger-API path).
      */
    def rootExerciseResults(tx: SubmittedTransaction): List[Lf] =
        tx.roots.toList.flatMap(id =>
            tx.nodes.get(id).collect { case e: Node.Exercise => e.exerciseResult }.flatten
        )

    /** Active contracts of the template named by `templateId` (matched on module:entity — template
      * names are unique across our DAR set), as `(contractId, createArgument)` pairs for decoding.
      */
    def active(store: EngineStore, templateId: japi.Identifier): List[(String, japi.Value)] =
        val wanted = s"${templateId.getModuleName}:${templateId.getEntityName}"
        store.contracts.toList.collect {
            case (cid, fci) if fci.templateId.qualifiedName.toString == wanted =>
                (cid.coid, fromLf(fci.createArg))
        }

    // --- javaapi <-> LF value bridge ------------------------------------------------------------

    def toTypeConRef(id: japi.Identifier): Ref.TypeConRef =
        val qn = Ref.QualifiedName.assertFromString(s"${id.getModuleName}:${id.getEntityName}")
        val pkg = id.getPackageId
        if pkg.startsWith("#") then
            Ref.TypeConRef(Ref.PackageRef.Name(Ref.PackageName.assertFromString(pkg.drop(1))), qn)
        else Ref.TypeConRef(Ref.PackageRef.Id(Ref.PackageId.assertFromString(pkg)), qn)

    def toLf(v: japi.Value): Lf = v match
        case r: japi.DamlRecord =>
            Lf.ValueRecord(
              None,
              ImmArray.from(r.getFields.asScala.map(f => (None, toLf(f.getValue))))
            )
        case v: japi.Variant =>
            Lf.ValueVariant(None, Ref.Name.assertFromString(v.getConstructor), toLf(v.getValue))
        case e: japi.DamlEnum => Lf.ValueEnum(None, Ref.Name.assertFromString(e.getConstructor))
        case l: japi.DamlList =>
            Lf.ValueList(l.stream().iterator().asScala.map(toLf).to(FrontStack))
        case o: japi.DamlOptional => Lf.ValueOptional(o.getValue.toScala.map(toLf))
        case m: japi.DamlTextMap  =>
            Lf.ValueTextMap(
              SortedLookupList
                  .fromImmArray(
                    ImmArray.from(
                      m.stream().iterator().asScala.map(e => e.getKey -> toLf(e.getValue)).toSeq
                    )
                  )
                  .fold(sys.error, identity)
            )
        case m: japi.DamlGenMap =>
            Lf.ValueGenMap(
              ImmArray.from(
                m.stream().iterator().asScala.map(e => toLf(e.getKey) -> toLf(e.getValue)).toSeq
              )
            )
        case p: japi.Party   => Lf.ValueParty(Ref.Party.assertFromString(p.getValue))
        case t: japi.Text    => Lf.ValueText(t.getValue)
        case i: japi.Int64   => Lf.ValueInt64(i.getValue)
        case n: japi.Numeric =>
            Lf.ValueNumeric(
              Numeric.assertFromBigDecimal(Numeric.Scale.assertFromInt(10), n.getValue)
            )
        case t: japi.Timestamp => Lf.ValueTimestamp(Time.Timestamp.assertFromInstant(t.getValue))
        case d: japi.Date      =>
            Lf.ValueDate(Time.Date.assertFromDaysSinceEpoch(d.getValue.toEpochDay.toInt))
        case b: japi.Bool       => Lf.ValueBool(b.getValue)
        case _: japi.Unit       => Lf.ValueUnit
        case c: japi.ContractId => Lf.ValueContractId(Lf.ContractId.assertFromString(c.getValue))
        case other              => sys.error(s"toLf: unhandled ${other.getClass}")

    def fromLf(v: Lf): japi.Value = v match
        case Lf.ValueRecord(_, fields) =>
            new japi.DamlRecord(
              fields.toSeq.map((_, v) => new japi.DamlRecord.Field(fromLf(v))).asJava
            )
        case Lf.ValueVariant(_, ctor, value) => new japi.Variant(ctor, fromLf(value))
        case Lf.ValueEnum(_, ctor)           => new japi.DamlEnum(ctor)
        case Lf.ValueList(vs)    => japi.DamlList.of(vs.toImmArray.toSeq.map(fromLf).asJava)
        case Lf.ValueOptional(o) => japi.DamlOptional.of(o.map(fromLf).toJava)
        case Lf.ValueTextMap(m)  =>
            japi.DamlTextMap.of(m.toImmArray.toSeq.map((k, v) => k -> fromLf(v)).toMap.asJava)
        case Lf.ValueGenMap(entries) =>
            japi.DamlGenMap.of(entries.toSeq.map((k, v) => fromLf(k) -> fromLf(v)).toMap.asJava)
        case Lf.ValueParty(p)      => new japi.Party(p)
        case Lf.ValueText(t)       => new japi.Text(t)
        case Lf.ValueInt64(i)      => new japi.Int64(i)
        case Lf.ValueNumeric(n)    => new japi.Numeric(n)
        case Lf.ValueTimestamp(t)  => japi.Timestamp.fromInstant(t.toInstant)
        case Lf.ValueDate(d)       => new japi.Date(d.days)
        case Lf.ValueBool(b)       => japi.Bool.of(b)
        case Lf.ValueUnit          => japi.Unit.getInstance()
        case Lf.ValueContractId(c) => new japi.ContractId(c.coid)

    /** Drive the engine's result loop, answering package/contract lookups from `store`. Contract
      * ids always resolve (single global store), so `ContractNotFound` only surfaces genuine
      * misses.
      */
    private def consume[A](store: EngineStore, r: Result[A]): Either[Error, A] = r match
        case ResultDone(a)                    => Right(a)
        case ResultError(e)                   => Left(Error.Unexpected(e.message))
        case ResultNeedPackage(pkgId, resume) => consume(store, resume(packages.get(pkgId)))
        case ResultNeedContract(cid, resume)  =>
            val response = store.contracts.get(cid) match
                case Some(inst) =>
                    ResultNeedContract.Response
                        .ContractFound(inst, crypto.Hash.HashingMethod.TypedNormalForm, _ => true)
                case None => ResultNeedContract.Response.ContractNotFound
            consume(store, resume(response))
        case ResultNeedKey(_, _, _, _) =>
            Left(Error.Unexpected("unexpected ResultNeedKey — TestTokenV2 uses no contract keys"))
        case ResultInterruption(continue, _) => consume(store, continue())
        case ResultPrefetch(_, _, resume)    => consume(store, resume())

object DamlEngine:
    /** Fixed ledger effective time. Must be after any `requestedAt`/`offeredAt` the flows use (they
      * pass `Instant.EPOCH`) and before any deadline (the flows leave those open).
      */
    val LedgerTime: Instant = Instant.parse("2020-01-01T00:00:00Z")

    /** Constant contract-id suffix that makes engine-local V1 ids absolute. Discriminators are
      * globally unique across submits, so a constant suffix keeps every absolute id unique.
      */
    val SuffixBytes: Bytes = Bytes.fromByteArray(Array[Byte](0.toByte))

    /** Load and decode the bundled DARs from the classpath and build the interpreter. The vendored
      * splice DARs ship as resources under `/dars/` (see this module's `resourceGenerators`),
      * listed in `/dars/index.txt`, so a consumer needs nothing on disk. DARs a 3.5 engine can't
      * read are skipped (none needed for TestTokenV2 are among them); the engine is pinned to the
      * container LF, so all token-standard DARs decode.
      */
    def load(): DamlEngine =
        val darNames =
            val is = Option(getClass.getResourceAsStream("/dars/index.txt"))
                .getOrElse(sys.error("bundled /dars/index.txt not found on the classpath"))
            try
                scala.io.Source
                    .fromInputStream(is)(scala.io.Codec.UTF8)
                    .getLines()
                    .filter(_.nonEmpty)
                    .toList
            finally is.close()
        val packages: Map[Ref.PackageId, Ast.Package] =
            darNames.flatMap { name =>
                val zis =
                    new java.util.zip.ZipInputStream(getClass.getResourceAsStream(s"/dars/$name"))
                try scala.util.Try(DarDecoder.assertReadArchive(name, zis).all).getOrElse(Nil)
                finally zis.close()
            }.toMap
        val packageMap = packages.view.mapValues(p => (p.metadata.name, p.metadata.version)).toMap
        // One preferred package id per name (daml-stdlib appears at several versions): highest wins.
        val packagePreference =
            packageMap.toList.groupBy(_._2._1).values.map(_.maxBy(_._2._2)._1).toSet
        val engine = new Engine(
          EngineConfig(allowedLanguageVersions = LanguageVersion.allLfVersions),
          com.digitalasset.canton.logging.NamedLoggerFactory.root,
        )
        new DamlEngine(engine, packages, packageMap, packagePreference)
