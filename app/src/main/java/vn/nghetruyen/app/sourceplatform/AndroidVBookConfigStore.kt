package vn.nghetruyen.app.sourceplatform

import android.content.Context
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.vbook.VBookConfigStore
import java.security.MessageDigest


class AndroidVBookConfigStore(context: Context) : VBookConfigStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(extensionKey: String): Map<String, String> {
        val json = preferences.getString(prefKey(extensionKey), null) ?: return emptyMap()
        return runCatching { VBookConfigJson.decode(json) }
            .onFailure { preferences.edit().remove(prefKey(extensionKey)).apply() }
            .getOrDefault(emptyMap())
    }

    @Synchronized
    override fun write(extensionKey: String, values: Map<String, String>) {
        require(extensionKey.isNotBlank()) { "VBOOK_CONFIG_EXTENSION_KEY_REQUIRED" }
        preferences.edit().putString(prefKey(extensionKey), VBookConfigJson.encode(values)).apply()
    }

    @Synchronized
    override fun clear(extensionKey: String) {
        preferences.edit().remove(prefKey(extensionKey)).apply()
    }

    private fun prefKey(extensionKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(extensionKey.toByteArray(Charsets.UTF_8))
        return "config." + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val PREFERENCES = "vbook_config_v2"
    }
}

internal object VBookConfigJson {
    private const val MAX_ENTRIES = 512
    private const val MAX_VALUE_LENGTH = 64 * 1024
    private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    fun encode(values: Map<String, String>): String {
        require(values.size <= MAX_ENTRIES) { "VBOOK_CONFIG_TOO_MANY_ENTRIES" }
        val json = JsonCodec.stringify(JsonValue.Obj(values.toSortedMap().mapValuesTo(linkedMapOf()) { (_, value) ->
            require(value.length <= MAX_VALUE_LENGTH) { "VBOOK_CONFIG_VALUE_TOO_LONG" }
            JsonValue.Str(value)
        }))
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "VBOOK_CONFIG_PAYLOAD_TOO_LARGE" }
        return json
    }

    fun decode(json: String): Map<String, String> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "VBOOK_CONFIG_PAYLOAD_TOO_LARGE" }
        val root = JsonCodec.parse(json, maxDepth = 8, maxNodes = MAX_ENTRIES * 4) as? JsonValue.Obj
            ?: error("VBOOK_CONFIG_OBJECT_REQUIRED")
        require(root.values.size <= MAX_ENTRIES) { "VBOOK_CONFIG_TOO_MANY_ENTRIES" }
        return root.values.mapNotNull { (key, value) ->
            (value as? JsonValue.Str)?.value?.takeIf { it.length <= MAX_VALUE_LENGTH }?.let { key to it }
        }.toMap(LinkedHashMap())
    }
}
