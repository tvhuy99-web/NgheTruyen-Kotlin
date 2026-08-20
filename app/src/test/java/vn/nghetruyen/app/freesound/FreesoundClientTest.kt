package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundClientTest {
    @Test
    fun successCodesAreAccepted() {
        val result = FreesoundClient.resultForHttpCode(200)
        assertTrue(result.success)
        assertEquals(200, result.httpCode)
    }

    @Test
    fun authenticationFailuresAreReportedWithoutCredentialDetails() {
        listOf(401, 403).forEach { code ->
            val result = FreesoundClient.resultForHttpCode(code)
            assertFalse(result.success)
            assertEquals(code, result.httpCode)
            assertTrue(result.message.contains("Khóa API Freesound"))
        }
    }

    @Test
    fun rateLimitIsReported() {
        val result = FreesoundClient.resultForHttpCode(429)
        assertFalse(result.success)
        assertEquals(429, result.httpCode)
        assertTrue(result.message.contains("giới hạn"))
    }

    @Test
    fun unexpectedHttpErrorsAreReported() {
        val result = FreesoundClient.resultForHttpCode(503)
        assertFalse(result.success)
        assertEquals(503, result.httpCode)
        assertTrue(result.message.contains("503"))
    }
}
