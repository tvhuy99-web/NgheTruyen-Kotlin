package vn.nghetruyen.source.vbook

fun interface VBookConfigReader {
    fun read(extensionKey: String): Map<String, String>
}

interface VBookConfigStore : VBookConfigReader {
    fun write(extensionKey: String, values: Map<String, String>)
    fun clear(extensionKey: String)
}

data class VBookConfigSnapshot(
    val extensionKey: String,
    val values: VBookConfigValues,
)

/**
 * Config is keyed by stable repository/package identity, not artifact/version.
 * Package update and rollback therefore preserve user choices.
 */
class VBookConfigService(
    private val store: VBookConfigStore,
) {
    fun load(extensionKey: String, manifest: VBookExtensionManifest): VBookConfigSnapshot {
        require(extensionKey.isNotBlank()) { "VBOOK_CONFIG_EXTENSION_KEY_REQUIRED" }
        val persisted = sanitize(manifest, store.read(extensionKey))
        return VBookConfigSnapshot(extensionKey, VBookConfigValues.resolve(manifest, persisted))
    }

    fun save(
        extensionKey: String,
        manifest: VBookExtensionManifest,
        changes: Map<String, String>,
    ): VBookConfigSnapshot {
        require(extensionKey.isNotBlank()) { "VBOOK_CONFIG_EXTENSION_KEY_REQUIRED" }
        val existing = sanitize(manifest, store.read(extensionKey)).toMutableMap()
        changes.forEach { (key, raw) ->
            require(key in allowedKeys(manifest)) { "VBOOK_CONFIG_KEY_UNKNOWN:$key" }
            existing[key] = validateValue(manifest, key, raw)
        }
        val sanitized = sanitize(manifest, existing)
        store.write(extensionKey, sanitized)
        return VBookConfigSnapshot(extensionKey, VBookConfigValues.resolve(manifest, sanitized))
    }

    fun reset(extensionKey: String, manifest: VBookExtensionManifest): VBookConfigSnapshot {
        store.clear(extensionKey)
        return VBookConfigSnapshot(extensionKey, VBookConfigValues.resolve(manifest))
    }

    private fun sanitize(manifest: VBookExtensionManifest, values: Map<String, String>): Map<String, String> =
        values.entries.mapNotNull { (key, value) ->
            if (key !in allowedKeys(manifest)) null
            else runCatching { key to validateValue(manifest, key, value) }.getOrNull()
        }.toMap(LinkedHashMap())

    private fun validateValue(manifest: VBookExtensionManifest, key: String, raw: String): String {
        require(raw.length <= MAX_VALUE_LENGTH) { "VBOOK_CONFIG_VALUE_TOO_LONG:$key" }
        return when (key) {
            VBookConfigValues.THREAD_NUM -> raw.toIntOrNull()?.coerceIn(1, 8)?.toString()
                ?: error("VBOOK_CONFIG_THREAD_NUM_INVALID")
            VBookConfigValues.TIMEOUT -> raw.toLongOrNull()?.coerceIn(100L, 120_000L)?.toString()
                ?: error("VBOOK_CONFIG_TIMEOUT_INVALID")
            VBookConfigValues.DELAY -> raw.toLongOrNull()?.coerceIn(0L, 120_000L)?.toString()
                ?: error("VBOOK_CONFIG_DELAY_INVALID")
            VBookConfigValues.IGNORE -> when (raw.trim().lowercase()) {
                "true", "1" -> "true"
                "false", "0" -> "false"
                else -> error("VBOOK_CONFIG_IGNORE_INVALID")
            }
            else -> {
                val spec = manifest.config[key] ?: error("VBOOK_CONFIG_KEY_UNKNOWN:$key")
                when (spec.mode) {
                    VBookConfigMode.TOGGLE -> when (raw.trim().lowercase()) {
                        "true", "1" -> "true"
                        "false", "0" -> "false"
                        else -> error("VBOOK_CONFIG_TOGGLE_INVALID:$key")
                    }
                    VBookConfigMode.INPUT -> when (spec.format) {
                        VBookConfigFormat.NUMBER -> {
                            require(raw.trim().toDoubleOrNull() != null) { "VBOOK_CONFIG_NUMBER_INVALID:$key" }
                            raw.trim()
                        }
                        else -> raw
                    }
                    VBookConfigMode.SELECT -> raw // vBook option values may be scalar or serialized multi-select data.
                    VBookConfigMode.UNKNOWN -> raw
                }
            }
        }
    }

    private fun allowedKeys(manifest: VBookExtensionManifest): Set<String> =
        manifest.config.keys + VBookConfigValues.BUILT_IN_KEYS

    companion object {
        private const val MAX_VALUE_LENGTH = 64 * 1024
    }
}

class InMemoryVBookConfigStore : VBookConfigStore {
    private val values = linkedMapOf<String, Map<String, String>>()
    override fun read(extensionKey: String): Map<String, String> = values[extensionKey].orEmpty().toMap()
    override fun write(extensionKey: String, values: Map<String, String>) {
        this.values[extensionKey] = values.toMap()
    }
    override fun clear(extensionKey: String) {
        values.remove(extensionKey)
    }
}
