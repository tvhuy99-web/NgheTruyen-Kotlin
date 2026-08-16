package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

internal data class VBookNativeHookBridgeInput(
    val json: String,
    val mode: String,
)









internal object VBookNativeHookBridgeInputCodec {
    fun resolve(
        packedInput: String?,
        legacyDirectInput: () -> String,
    ): VBookNativeHookBridgeInput {
        val (raw, mode) = if (!packedInput.isNullOrBlank()) {
            packedInput to MODE_PACKED
        } else {
            legacyDirectInput() to MODE_LEGACY_DIRECT
        }
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_INPUT_BYTES) { "NATIVE_LUA_HOOK_INPUT_TOO_LARGE:$bytes" }
        val parsed = runCatching {
            JsonCodec.parse(raw, maxDepth = 96, maxNodes = 100_000)
        }.getOrElse { error ->
            throw IllegalArgumentException("NATIVE_LUA_HOOK_INPUT_INVALID:${error.message}", error)
        }
        require(parsed is JsonValue.Obj) { "NATIVE_LUA_HOOK_INPUT_OBJECT_REQUIRED" }
        return VBookNativeHookBridgeInput(
            json = JsonCodec.stringify(parsed),
            mode = mode,
        )
    }

    const val MODE_PACKED = "packed-input"
    const val MODE_LEGACY_DIRECT = "legacy-direct"
    private const val MAX_INPUT_BYTES = 512 * 1024
}
