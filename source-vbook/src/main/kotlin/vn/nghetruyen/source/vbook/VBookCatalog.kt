package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import java.net.URI
import java.security.MessageDigest

data class VBookCatalogMetadata(
    val author: String,
    val description: String,
    val unknown: Map<String, JsonValue>,
)

data class VBookCatalogItem(
    val name: String,
    val author: String,
    val packageUrl: String,
    val version: String,
    val source: String,
    val iconUrl: String?,
    val description: String,
    val rawType: String?,
    val contentType: VBookContentType,
    val locale: String?,
    val nsfw: Boolean,
    val unknown: Map<String, JsonValue>,
) {
    fun stableRemoteIdentity(catalogUrl: String): String = sha256(canonicalCatalogUrl(catalogUrl) + "\n" + canonicalPackageUrl(packageUrl))

    val usesCleartextHttp: Boolean
        get() = source.startsWith("http://", ignoreCase = true) || packageUrl.startsWith("http://", ignoreCase = true)

    companion object {
        private fun canonicalCatalogUrl(value: String): String = canonicalUrl(value)
        private fun canonicalPackageUrl(value: String): String = canonicalUrl(value)
        private fun canonicalUrl(value: String): String = runCatching {
            val uri = URI(value.trim())
            URI(
                uri.scheme?.lowercase(),
                uri.userInfo,
                uri.host?.lowercase(),
                uri.port,
                uri.path,
                uri.query,
                null,
            ).toString()
        }.getOrDefault(value.trim())

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

data class VBookCatalog(
    val metadata: VBookCatalogMetadata,
    val items: List<VBookCatalogItem>,
)

object VBookCatalogParser {
    private val knownItem = setOf(
        "name", "author", "path", "version", "source", "icon", "description", "type", "locale", "tag", "nsfw",
    )

    fun parse(json: String): VBookCatalog {
        val root = JsonCodec.parse(json, maxDepth = 64, maxNodes = 500_000) as? JsonValue.Obj
            ?: error("VBOOK_CATALOG_OBJECT_REQUIRED")
        val metadataObj = root.obj("metadata")
        val metadata = VBookCatalogMetadata(
            author = metadataObj?.string("author").orEmpty(),
            description = metadataObj?.string("description").orEmpty(),
            unknown = metadataObj?.values?.filterKeys { it !in setOf("author", "description") }.orEmpty(),
        )
        val data = root.array("data")?.values ?: error("VBOOK_CATALOG_DATA_REQUIRED")
        require(data.size <= 100_000) { "VBOOK_CATALOG_TOO_LARGE" }
        val items = data.mapNotNull { raw ->
            val obj = raw as? JsonValue.Obj ?: return@mapNotNull null
            val path = obj.string("path")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val rawType = obj.string("type")
            VBookCatalogItem(
                name = obj.string("name").orEmpty(),
                author = obj.string("author") ?: metadata.author,
                packageUrl = path,
                version = when (val version = obj["version"]) {
                    is JsonValue.Str -> version.value
                    is JsonValue.Num -> version.raw
                    else -> ""
                },
                source = obj.string("source").orEmpty(),
                iconUrl = obj.string("icon"),
                description = obj.string("description").orEmpty(),
                rawType = rawType,
                contentType = VBookContentType.from(rawType),
                locale = obj.string("locale"),
                nsfw = obj.bool("nsfw") ?: obj.string("tag")?.equals("nsfw", true) == true,
                unknown = obj.values.filterKeys { it !in knownItem },
            )
        }
        return VBookCatalog(metadata, items)
    }
}

data class VBookRepositoryCatalog(
    val descriptor: VBookRepositoryDescriptor,
    val catalog: VBookCatalog,
)

data class VBookRepositoryCorpusSummary(
    val repositoryCount: Int,
    val catalogItemCount: Int,
    val uniqueArtifactCount: Int,
    val byType: Map<VBookContentType, Int>,
    val cleartextItemCount: Int,
    val missingTypeCount: Int,
)

object VBookRepositoryCorpus {
    fun summarize(catalogs: List<VBookRepositoryCatalog>): VBookRepositoryCorpusSummary {
        val all = catalogs.flatMap { row -> row.catalog.items.map { row.descriptor.link to it } }
        val identities = all.map { (catalogUrl, item) -> item.stableRemoteIdentity(catalogUrl) }.toSet()
        return VBookRepositoryCorpusSummary(
            repositoryCount = catalogs.map { it.descriptor.link }.distinct().size,
            catalogItemCount = all.size,
            uniqueArtifactCount = identities.size,
            byType = all.map { it.second.contentType }.groupingBy { it }.eachCount(),
            cleartextItemCount = all.count { it.second.usesCleartextHttp },
            missingTypeCount = all.count { it.second.contentType == VBookContentType.UNKNOWN },
        )
    }
}
