package tokenstandard.engine

import cats.arrow.FunctionK
import cats.data.StateT
import tokenstandard.PartyId
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.Error
import tokenstandard.registry.service.LocalRegistryApi
import tokenstandard.registry.service.RegistryMetadata
import tokenstandard.registry.service.RegistryService

/** The reference registry for one admin, backed by the engine store ([[EngineAcsSource]]) and
  * lifted into the flow's [[EngineM]] effect. The registry runs in [[RegEngineM]] (`MonadThrow`);
  * this bridges it into `EngineM` (error = `Error`) over the same store — the engine analogue of
  * the old `TestRegistries.inMemory`, but reading real contracts instead of a synthetic map.
  */
object EngineRegistry:

    def apply(engine: DamlEngine, admin: PartyId, instruments: List[String]): RegistryApi[EngineM] =
        val impl = LocalRegistryApi[RegEngineM](
          RegistryService(EngineAcsSource(engine, admin)),
          RegistryMetadata.basic(admin.value, instruments),
        )
        RegistryApi.mapK(impl)(
          new FunctionK[RegEngineM, EngineM]:
              def apply[A](fa: RegEngineM[A]): EngineM[A] =
                  StateT { store =>
                      fa.run(store).left.map {
                          case e: Error => e
                          case t => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))
                      }
                  }
        )
