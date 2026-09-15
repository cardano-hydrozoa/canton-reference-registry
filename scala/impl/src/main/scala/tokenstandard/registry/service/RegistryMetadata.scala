package tokenstandard.registry.service

import tokenstandard.registry.openapi.metadata.models as md

/** The registry's static catalog (metadata-v1): registry info + instrument list. These endpoints
  * have no Daml counterpart — the TestTokenV2 registry keeps no catalog (instrument ids are
  * free-form strings) — so the reference implementation serves operator-supplied configuration.
  * Shared by [[LocalRegistryApi]] and [[http.RegistryRoutes]] so the pagination/lookup semantics
  * can't drift between them.
  */
final case class RegistryMetadata(
    info: md.GetRegistryInfoResponse,
    instruments: List[md.Instrument],
):
    /** One catalog page, ordered by instrument id; `pageToken` is the previous page's last id (the
      * spec's `nextPageToken` contract).
      */
    def page(pageSize: Option[Int], pageToken: Option[String]): md.ListInstrumentsResponse =
        val size = pageSize.getOrElse(RegistryMetadata.DefaultPageSize).max(1)
        val sorted = instruments.sortBy(_.id)
        val remaining = pageToken.fold(sorted)(t => sorted.dropWhile(_.id <= t))
        val page = remaining.take(size)
        md.ListInstrumentsResponse(page, Option.when(remaining.sizeIs > size)(page.last.id))

    def instrument(id: String): Option[md.Instrument] = instruments.find(_.id == id)

object RegistryMetadata:
    /** Spec default for `listInstruments.pageSize`. */
    val DefaultPageSize: Int = 25

    /** The token-standard APIs this reference registry serves, mapped to the minor version of the
      * vendored specs it implements them from.
      */
    val supportedApis: Map[String, Int] = Map(
      "splice-api-token-metadata-v1" -> 2,
      "splice-api-token-allocation-instruction-v2" -> 0,
      "splice-api-token-allocation-v2" -> 0,
      "splice-api-token-transfer-instruction-v2" -> 0,
    )

    /** A minimal catalog: basic instruments (id = name = symbol, Daml's full 10 decimals). */
    def basic(adminId: String, instrumentIds: List[String]): RegistryMetadata =
        RegistryMetadata(
          md.GetRegistryInfoResponse(adminId, supportedApis),
          instrumentIds.map(id =>
              md.Instrument(
                id = id,
                name = id,
                symbol = id,
                decimals = 10,
                supportedApis = supportedApis
              )
          ),
        )
