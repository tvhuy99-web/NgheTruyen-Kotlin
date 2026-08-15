package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

class VBookNativeHookBridgeInputCodecTest {
    @Test
    fun packedAdapterContractPreservesValueArgsAndContext() {
        val storyUrl = "https://sangtacviet.com/truyen/qidian/1/1049557954/"
        val packed = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "value" to JsonValue.Str(storyUrl),
            "args" to JsonValue.Obj(linkedMapOf("kind" to JsonValue.Str("toc"))),
            "context" to JsonValue.Obj(linkedMapOf(
                "input" to JsonValue.Str(storyUrl),
                "current_url" to JsonValue.Str(storyUrl),
            )),
        )))
        var fallbackCalled = false

        val decoded = VBookNativeHookBridgeInputCodec.resolve(packed) {
            fallbackCalled = true
            "{}"
        }
        val root = JsonCodec.parse(decoded.json) as JsonValue.Obj

        assertEquals(VBookNativeHookBridgeInputCodec.MODE_PACKED, decoded.mode)
        assertFalse(fallbackCalled)
        assertEquals(storyUrl, (root["value"] as JsonValue.Str).value)
        assertEquals("toc", ((root["args"] as JsonValue.Obj)["kind"] as JsonValue.Str).value)
        assertEquals(storyUrl, ((root["context"] as JsonValue.Obj)["input"] as JsonValue.Str).value)
    }

    @Test
    fun legacyDirectShapeRemainsSupported() {
        val legacy = "{\"value\":\"legacy\",\"args\":{},\"context\":{}}"
        var fallbackCalled = false

        val decoded = VBookNativeHookBridgeInputCodec.resolve(null) {
            fallbackCalled = true
            legacy
        }
        val root = JsonCodec.parse(decoded.json) as JsonValue.Obj

        assertEquals(VBookNativeHookBridgeInputCodec.MODE_LEGACY_DIRECT, decoded.mode)
        assertTrue(fallbackCalled)
        assertEquals("legacy", (root["value"] as JsonValue.Str).value)
    }

    @Test
    fun malformedPackedInputFailsInsteadOfSilentlyDroppingValue() {
        var fallbackCalled = false
        val failure = assertThrows(IllegalArgumentException::class.java) {
            VBookNativeHookBridgeInputCodec.resolve("{broken") {
                fallbackCalled = true
                "{}"
            }
        }

        assertFalse(fallbackCalled)
        assertTrue(failure.message.orEmpty().contains("NATIVE_LUA_HOOK_INPUT_INVALID"))
    }
}
