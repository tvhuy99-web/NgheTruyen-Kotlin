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
    fun sfxSearchUsesDurationFilterSortAndPaginationWithoutTokenInUrl() {
        val url = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest(
                query = " sword clash ",
                category = FreesoundCategory.SFX,
                sort = FreesoundSort.MOST_DOWNLOADED,
                page = 3,
                pageSize = 20,
            ),
        )

        assertEquals("sword clash", url.queryParameter("query"))
        assertEquals("duration:[0.1 TO 15]", url.queryParameter("filter"))
        assertEquals("downloads_desc", url.queryParameter("sort"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("page_size"))
        assertNull(url.queryParameter("token"))
        assertTrue(url.queryParameter("fields")!!.contains("previews"))
    }

    @Test
    fun categoryDurationFiltersMatchPlaybackRoles() {
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
    fun allCategoryDoesNotAddDurationFilter() {
        val url = FreesoundClient.buildSearchUrl(
            FreesoundSearchRequest(
                query = "rain",
                category = FreesoundCategory.ALL,
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
    fun searchResponseParsesMetadataAndPrefersHigherQualityOggPreview() {
        val payload = """
            {
              "count": 41,
              "next": "https://freesound.org/apiv2/search/?page=2",
              "previous": null,
              "results": [
                {
                  "id": 123,
                  "name": "Thunder Strike.wav",
                  "username": "fieldrecorder",
                  "license": "https://creativecommons.org/publicdomain/zero/1.0/",
                  "duration": 8.75,
                  "tags": ["thunder", "storm"],
                  "previews": {
                    "preview-hq-mp3": "https://cdn.freesound.org/previews/123/123-hq.mp3",
                    "preview-hq-ogg": "https://cdn.freesound.org/previews/123/123-hq.ogg"
                  },
                  "avg_rating": 4.8,
                  "num_ratings": 27,
                  "num_downloads": 9012,
                  "url": "https://freesound.org/s/123/",
                  "type": "wav",
                  "channels": 2,
                  "samplerate": 48000
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
        assertEquals("fieldrecorder", sound.username)
        assertEquals(8.75, sound.durationSeconds, 0.001)
        assertEquals(listOf("thunder", "storm"), sound.tags)
        assertEquals("https://cdn.freesound.org/previews/123/123-hq.ogg", sound.preferredPreviewUrl)
        assertEquals(4.8, sound.avgRating, 0.001)
        assertEquals(27, sound.numRatings)
        assertEquals(9012, sound.numDownloads)
        assertEquals("wav", sound.fileType)
        assertEquals(2, sound.channels)
        assertEquals(48000, sound.sampleRate)
    }

    @Test
    fun mp3PreviewIsUsedWhenOggIsMissing() {
        val sound = FreesoundSound(
            id = 1,
            name = "preview",
            username = "u",
            license = "CC0",
            durationSeconds = 1.0,
            tags = emptyList(),
            previewHqMp3 = "https://cdn.freesound.org/a.mp3",
            previewHqOgg = null,
            avgRating = 0.0,
            numRatings = 0,
            numDownloads = 0,
            webUrl = null,
            fileType = "mp3",
            channels = 1,
            sampleRate = 44100,
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
