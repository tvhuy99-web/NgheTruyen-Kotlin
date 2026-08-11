package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookAppKernelPreludeTest {
    @Test
    fun exposesSingleFullInAppAuthoritySurface() {
        val script = VBookAppKernelPrelude.build()
        listOf(
            "global.App",
            "apiVersion: 2",
            "FULL_IN_APP",
            "browser: browserApi",
            "network: networkApi",
            "storage: storageApi",
            "cookies:",
            "crypto:",
            "websocket:",
            "graphics:",
            "translation:",
            "script:",
            "ui: uiApi",
            "reader: readerApi",
            "library: libraryApi",
            "tts: ttsApi",
            "hooks: hooksApi",
            "lifecycle: lifecycleApi",
            "hostCommandContract: true",
            "hostCommandExecution: true",
            "intent: hostCommandIntent",
            "execute: executeHostCommand",
        ).forEach { token -> assertTrue("missing $token", token in script) }
    }

    @Test
    fun exposesStableSerializableHostCommandEnvelopeAndBridge() {
        val script = VBookAppKernelPrelude.build()
        listOf(
            "nghetruyen.host-command",
            "domain: String(domain || '')",
            "action: String(action || '')",
            "global.__bridge('host_command', command)",
            "APP_HOST_COMMAND_BRIDGE_UNAVAILABLE",
            "nextChapter",
            "moveParagraph",
            "follow",
            "bookmark",
            "setRate",
            "reader.chapterChanged",
        ).forEach { token -> assertTrue("missing $token", token in script) }
    }

    @Test
    fun uiHelpersMatchExistingUiActionResultContract() {
        val script = VBookAppKernelPrelude.build()
        assertTrue("message: input.message" in script)
        assertTrue("openUrl: input.openUrl" in script)
        assertTrue("refresh: !!input.refresh" in script)
        assertTrue("notify: function(message)" in script)
        assertTrue("refresh: function(message)" in script)
    }

    @Test
    fun keepsOsAndHostSecretBoundaryExplicit() {
        val script = VBookAppKernelPrelude.build()
        assertTrue("rawAndroid: false" in script)
        assertTrue("hostSecrets: false" in script)
        assertFalse("addJavascriptInterface" in script)
        assertFalse("Class.forName" in script)
        assertFalse("Runtime.getRuntime" in script)
        assertFalse("android.content" in script)
    }
}
