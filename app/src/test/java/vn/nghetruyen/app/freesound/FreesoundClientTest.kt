package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun managerSearchUsesDurationSortPaginationAndNeverPlacesTokenInUrl() {
        val url = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest(
                query = " sword clash ",
                category = FreesoundCategory.SFX,
                duration = FreesoundDuration.RECOMMENDED,
                sort = FreesoundSort.SHORTEST,
                page = 3,
                pageSize = 20,
            ),
        )

        assertEquals("sword clash", url.queryParameter("query"))
        assertEquals("duration:[0.1 TO 15]", url.queryParameter("filter"))
        assertEquals("duration_asc", url.queryParameter("sort"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("page_size"))
        assertNull(url.queryParameter("token"))

        val fields = url.queryParameter("fields")!!
        assertTrue(fields.contains("name"))
        assertTrue(fields.contains("description"))
        assertTrue(fields.contains("duration"))
        assertTrue(fields.contains("previews"))
        assertTrue(fields.contains("username"))
    assertTrue(fields.contains("license"))
    assertTrue(fields.contains("url"))
    }

    @Test
    fun recommendedDurationMatchesEachAudioManager() {
        val music = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest("fantasy", category = FreesoundCategory.MUSIC),
        )
        val ambience = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest("forest", category = FreesoundCategory.AMBIENCE),
        )
        val sfx = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest("sword", category = FreesoundCategory.SFX),
        )

        assertEquals("duration:[30 TO 900]", music.queryParameter("filter"))
        assertEquals("duration:[10 TO 300]", ambience.queryParameter("filter"))
        assertEquals("duration:[0.1 TO 15]", sfx.queryParameter("filter"))
    }

    @Test
    fun explicitDurationFilterOverridesManagerRecommendation() {
        val url = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest(
                query = "rain",
                category = FreesoundCategory.AMBIENCE,
                duration = FreesoundDuration.SHORT,
            ),
        )
        assertEquals("duration:[0 TO 15]", url.queryParameter("filter"))
    }

    @Test
    fun allDurationDoesNotAddFilter() {
        val url = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest(
                query = "rain",
                category = FreesoundCategory.AMBIENCE,
                duration = FreesoundDuration.ALL,
            ),
        )
        assertNull(url.queryParameter("filter"))
    }

    @Test
    fun requestNormalizationClampsPageAndPageSize() {
        val normalized = FreesoundSearchRequest(
            query = " thunder ",
            page = -8,
            pageSize = 999,
        ).normalized()

        assertEquals("thunder", normalized.query)
        assertEquals(1, normalized.page)
        assertEquals(FreesoundSearchRequest.MAX_PAGE_SIZE, normalized.pageSize)
    }

    @Test
    fun searchResponseParsesOnlyNeededFieldsAndPrefersOggPreview() {
        val payload = """
            {
              "count": 41,
              "next": "https://freesound.org/apiv2/search/?page=2",
              "previous": null,
              "results": [
                {
                  "id": 123,
                  "name": "Thunder Strike.wav",
                  "description": "A loud close thunder strike.",
                  "duration": 8.75,
                "username": "fieldrecorder",
                "license": "Creative Commons 0",
                "url": "https://freesound.org/people/fieldrecorder/sounds/123/",
                  "previews": {
                    "preview-hq-mp3": "https://cdn.freesound.org/previews/123/123-hq.mp3",
                    "preview-hq-ogg": "https://cdn.freesound.org/previews/123/123-hq.ogg"
                  }
                },
                {
                  "id": -1,
                  "name": "invalid"
                }
              ]
            }
        """.trimIndent()

        val page = FreesoundClient.parseSearchPage(
            payload,
            FreesoundSearchRequest(query = "thunder", page = 1, pageSize = 20),
        )

        assertEquals(41, page.count)
        assertEquals(1, page.page)
        assertEquals(20, page.pageSize)
        assertTrue(page.hasNext)
        assertFalse(page.hasPrevious)
        assertEquals(1, page.results.size)

        val sound = page.results.single()
        assertEquals(123, sound.id)
        assertEquals("Thunder Strike.wav", sound.name)
        assertEquals("A loud close thunder strike.", sound.description)
        assertEquals(8.75, sound.durationSeconds, 0.001)
        assertEquals("https://cdn.freesound.org/previews/123/123-hq.mp3", sound.preferredPreviewUrl)
    assertEquals("fieldrecorder", sound.username)
    assertEquals("Creative Commons 0", sound.license)
    assertEquals("https://freesound.org/people/fieldrecorder/sounds/123/", sound.webUrl)
    }

    @Test
    fun mp3PreviewIsUsedWhenOggIsMissing() {
        val sound = FreesoundSound(
            id = 1,
            name = "preview",
            description = "",
            durationSeconds = 1.0,
            previewHqMp3 = "https://cdn.freesound.org/a.mp3",
            previewHqOgg = null,
        )

        assertEquals("https://cdn.freesound.org/a.mp3", sound.preferredPreviewUrl)
    }

    @Test
    fun unsafePreviewUrlsAreIgnored() {
        val payload = """
            {
              "count": 1,
              "next": null,
              "previous": null,
              "results": [
                {
                  "id": 55,
                  "name": "Unsafe preview",
                  "description": "test",
                  "duration": 1.0,
                  "previews": {
                    "preview-hq-mp3": "http://example.invalid/audio.mp3"
                  }
                }
              ]
            }
        """.trimIndent()

        val sound = FreesoundClient.parseSearchPage(
            payload,
            FreesoundSearchRequest(query = "test"),
        ).results.single()

        assertNull(sound.preferredPreviewUrl)
    }
}
