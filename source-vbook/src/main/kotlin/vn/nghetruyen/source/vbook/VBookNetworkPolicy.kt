package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.runtime.SourceResourceProvider

// one full-authority in-app mode
object VBookNetworkPolicy {
    private val allMethods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")

    fun effectiveManifest(
        base: SourceManifest,
        plugin: VBookExtensionManifest,
        resources: SourceResourceProvider,
        configValues: VBookConfigValues = VBookConfigValues.resolve(plugin),
        additionalScriptPaths: Set<String> = emptySet(),
    ): SourceManifest {
        val existing = base.capabilities.network
        val connection = configValues.connectionSettings()
        val network = (existing ?: SourceNetworkCapability(
            methods = allMethods,
            maxResponseBytes = 16 * 1024 * 1024,
            maxRequestBytes = 4 * 1024 * 1024,
            requestsPerMinute = 600,
            maxConcurrent = connection.threadNum,
        )).copy(
            methods = allMethods,
            maxConcurrent = connection.threadNum,
            publicInternet = true,
            allowCleartext = true,
        )
        return base.copy(capabilities = base.capabilities.copy(network = network))
    }

    fun requiresLegacyCleartext(
        plugin: VBookExtensionManifest,
        resources: SourceResourceProvider,
        additionalScriptPaths: Set<String> = emptySet(),
    ): Boolean {
        if (plugin.metadata.source.startsWith("http://", ignoreCase = true)) return true
        if (plugin.config.values.any { it.defaultValue.startsWith("http://", ignoreCase = true) }) return true
        val paths = plugin.allDeclaredScriptPaths() + additionalScriptPaths.map(VBookPaths::normalizeScriptPath)
        return paths.any { path ->
            resources.read(path, 2 * 1024 * 1024)
                ?.toString(Charsets.UTF_8)
                ?.contains("http://", ignoreCase = true) == true
        }
    }
}
