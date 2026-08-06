package vn.nghetruyen.app.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSessionStoreTest {
    @Test fun mergesAndExpiresCookiesDeterministically() {
        val store = InMemorySourceSessionStore()
        store.replaceCookieHeader("stv", "PHPSESSID=old; cookieenabled=true")
        store.mergeSetCookieHeaders("stv", listOf("PHPSESSID=new; Path=/; Secure", "_gac=abc; Path=/"))
        assertEquals("PHPSESSID=new; cookieenabled=true; _gac=abc", store.cookieHeader("stv"))
        store.mergeSetCookieHeaders("stv", listOf("_gac=; Max-Age=0; Path=/"))
        assertEquals("PHPSESSID=new; cookieenabled=true", store.cookieHeader("stv"))
        store.clear("stv")
        assertNull(store.cookieHeader("stv"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCookieHeaders() {
        val store = InMemorySourceSessionStore()
        store.replaceCookieHeader("stv", "x=" + "a".repeat(40 * 1024))
    }

}
