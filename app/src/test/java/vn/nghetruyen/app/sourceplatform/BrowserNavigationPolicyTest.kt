package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import java.net.InetAddress
import java.net.UnknownHostException

class BrowserNavigationPolicyTest {
    private val publicAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

    @Test
    fun approvedHostRedirectUsesCacheWithoutResolvingOnCallbackThread() {
        var resolverCalls = 0
        val policy = BrowserNavigationPolicy {
            resolverCalls += 1
            if (resolverCalls > 1) error("DNS_MUST_NOT_RUN_IN_WEBVIEW_CALLBACK")
            listOf(publicAddress)
        }

        val initial = policy.preflightInitial(manifest(), "https://my.qidian.com/author/9639927/#csrf")
            as BrowserNavigationPolicy.Decision.Allowed
        val callback = policy.evaluateRedirect(
            manifest(),
            "https://my.qidian.com/author/9639927/#waf-state",
            setOf(initial.host),
        ) as BrowserNavigationPolicy.Decision.Allowed

        assertEquals(1, resolverCalls)
        assertEquals("session-cache", callback.resolutionSource)
        assertTrue(callback.shape.hasFragment)
        assertFalse(callback.transportUrl.contains('#'))
    }

    @Test
    fun fragmentIsExcludedFromTransportPolicyButPreservedInUrlShape() {
        val policy = BrowserNavigationPolicy { listOf(publicAddress) }

        val decision = policy.preflightInitial(
            manifest(),
            "https://my.qidian.com/author/9639927/?from=source#csrf-token",
        ) as BrowserNavigationPolicy.Decision.Allowed

        assertEquals("https://my.qidian.com/author/9639927/?from=source", decision.transportUrl)
        assertTrue(decision.shape.hasQuery)
        assertTrue(decision.shape.hasFragment)
    }

    @Test
    fun unseenCrossHostRedirectRequiresWorkerDnsBeforeItCanBeApproved() {
        var resolverCalls = 0
        val policy = BrowserNavigationPolicy {
            resolverCalls += 1
            listOf(publicAddress)
        }
        val initial = policy.preflightInitial(manifest(), "https://my.qidian.com/author/9639927/")
            as BrowserNavigationPolicy.Decision.Allowed

        val callback = policy.evaluateRedirect(
            manifest(),
            "https://m.qidian.com/author/9639927/",
            setOf(initial.host),
        )
        assertTrue(callback is BrowserNavigationPolicy.Decision.NeedsDns)
        assertEquals(1, resolverCalls)

        val deferred = policy.preflightRedirect(manifest(), "https://m.qidian.com/author/9639927/")
            as BrowserNavigationPolicy.Decision.Allowed
        assertEquals("m.qidian.com", deferred.host)
        assertEquals(2, resolverCalls)
    }

    @Test
    fun dnsFailureKeepsStructuredPolicyCodeAndCauseType() {
        val policy = BrowserNavigationPolicy { throw UnknownHostException("m.qidian.com") }

        val denied = policy.preflightRedirect(manifest(), "https://m.qidian.com/author/9639927/")
            as BrowserNavigationPolicy.Decision.Denied

        assertEquals("SOURCE_NETWORK_DNS_FAILED", denied.code)
        assertEquals(UnknownHostException::class.java.name, denied.causeType)
        assertTrue(denied.decisionThread.isNotBlank())
    }

    @Test
    fun privateDnsAnswerRemainsBlocked() {
        val policy = BrowserNavigationPolicy { listOf(InetAddress.getByName("127.0.0.1")) }

        val denied = policy.preflightInitial(manifest(), "https://my.qidian.com/author/9639927/")
            as BrowserNavigationPolicy.Decision.Denied

        assertEquals("SOURCE_NETWORK_PRIVATE_ADDRESS", denied.code)
    }

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "test.vbook.browser-policy",
        name = "Browser navigation policy fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://source.example"),
        capabilities = SourceCapabilities(
            network = SourceNetworkCapability(publicInternet = true, allowCleartext = true),
        ),
        actions = emptyMap(),
    )
}
