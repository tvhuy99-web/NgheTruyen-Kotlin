package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

internal data class VBookNativeHookBridgeInput(
    val json: String,
    val mode: String,
)

/**
 * Decodes the Native Source API 2 bridge contract without losing the payload value.
 *
 * Current adapters send `{name, input: JSON.stringify({value,args,context})}`. Older callers may
 * still send `{name,value,args,context}` directly, so that shape remains a compatibility fallback.
 * When packed input is present it is authoritative: malformed packed data must fail visibly instead
 * of falling back and silently discarding `value`, which is what broke story -> TOC on STV.
 */
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
