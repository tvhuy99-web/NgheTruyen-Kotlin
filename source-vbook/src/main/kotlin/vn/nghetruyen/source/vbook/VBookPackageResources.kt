package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.runtime.SourceResourceProvider

/** Read-only resource view over the exact files retained by [VBookPackageReader]. */
class VBookPackageResourceProvider(
    private val pkg: VBookPackage,
) : SourceResourceProvider {
    override fun read(path: String, maxBytes: Int): ByteArray? {
        require(maxBytes >= 0) { "VBOOK_RESOURCE_LIMIT_INVALID" }
        val normalized = path.replace('\\', '/').removePrefix("/")
        val bytes = when (normalized) {
            "plugin.json" -> pkg.pluginJsonBytes
            "icon.png" -> pkg.iconBytes
            else -> lookup(normalized) ?: normalized.takeUnless { it.startsWith("src/") }?.let { lookup("src/$it") }
        } ?: return null
        return bytes.takeIf { it.size <= maxBytes }?.copyOf()
    }

    private fun lookup(path: String): ByteArray? = pkg.scripts[path] ?: pkg.resources[path]
}
