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
        ).forEach { token -> assertTrue("missing $token", token in script) }
    }

    @Test
    fun keepsOsAndHostSecretBoundaryExplicit() {
        val script = VBookAppKernelPrelude.build()
        assertTrue("rawAndroid: false" in script)
        assertTrue("hostSecrets: false" in script)
        assertFalse("addJavascriptInterface" in script)
        assertFalse("Class.forName" in script)
        assertFalse("Runtime.getRuntime" in script)
    }
}
