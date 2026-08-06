package vn.nghetruyen.source.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI

class SourceNetworkPolicyTest {
    @Test fun wildcardDoesNotMatchApex() {
        assertTrue(SourceOriginPolicy.matchesOrigin(URI("https://api.example.org/a"), "https://*.example.org"))
        assertFalse(SourceOriginPolicy.matchesOrigin(URI("https://example.org/a"), "https://*.example.org"))
    }

    @Test fun blocksPrivateAndDocumentationAddresses() {
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("127.0.0.1")))
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("192.168.1.4")))
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("203.0.113.2")))
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("2001:db8::1")))
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("::ffff:127.0.0.1")))
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName("1.1.1.1")))
    }
}
