package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context

class VBookLegacyConfigPreludeTest {
    @Test
    fun legacyConfigIsExposedAsBareGlobalIdentifier() {
        val prelude = VBookConfigPrelude.build(
            VBookContractProfile.LEGACY_JS,
            VBookConfigValues(
                linkedMapOf(
                    "STVHOST" to "https://example.com",
                    "DOMAIN" to "https://mirror.example.com",
                    "thread_num" to "3",
                    "timeout" to "30000",
                    "delay" to "0",
                    "ignore" to "false",
                ),
            ),
        )

        assertTrue(prelude.contains("var STVHOST = \"https://example.com\";"))
        assertTrue(prelude.contains("var DOMAIN = \"https://mirror.example.com\";"))
        assertFalse(prelude.contains("thread_num"))
        assertFalse(prelude.contains("var timeout"))

        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, prelude, "legacy-config-prelude", 1, null)
            val value = Context.toString(
                cx.evaluateString(scope, "STVHOST", "legacy-config-global", 1, null),
            )
            assertEquals("https://example.com", value)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun legacyInvalidConfigKeysDoNotBreakOtherwiseCompatibleExtensions() {
        val prelude = VBookConfigPrelude.build(
            VBookContractProfile.LEGACY_JS,
            VBookConfigValues(
                mapOf(
                    "STVHOST" to "https://example.com",
                    "not-a-js-identifier" to "ignored",
                ),
            ),
        )

        assertTrue(prelude.contains("var STVHOST = \"https://example.com\";"))
        assertFalse(prelude.contains("not-a-js-identifier"))
    }
}
