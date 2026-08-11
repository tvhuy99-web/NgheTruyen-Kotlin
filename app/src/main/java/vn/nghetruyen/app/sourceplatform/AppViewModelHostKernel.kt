package vn.nghetruyen.app.sourceplatform

import android.app.Application
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.playback.ReaderPlaybackService
import vn.nghetruyen.app.ui.AppViewModel
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceHostKernelBus
import vn.nghetruyen.source.api.SourceHostKernelDispatcher
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import java.lang.ref.WeakReference

/**
 * NgheTruyen-owned host bindings for extension kernel v2.
 *
 * The extension side only sends SourceHostCommand JSON values. This adapter intentionally owns all
 * Android/ViewModel interaction so no Activity, Context, Service or repository instance crosses the
 * extension boundary. The dispatcher keeps only a WeakReference to the UI session; the process bus
 * therefore cannot keep a dead Activity/ViewModel alive.
 */
object ExtensionHostKernelInstaller {
    fun install(viewModel: AppViewModel): SourceHostKernelDispatcher {
        val app = viewModel.getApplication<Application>()
        val hostRef = WeakReference(viewModel)
        fun host(): AppViewModel? = hostRef.get()

        val dispatcher = SourceHostKernelDispatcher()
            .register("reader", "refresh") { _, _, traceId ->
                ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_REFRESH)
                accepted(traceId)
            }
            .register("reader", "nextChapter") { _, _, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                host.nextChapter()
                accepted(traceId)
            }
            .register("reader", "previousChapter") { _, _, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                host.previousChapter()
                accepted(traceId)
            }
            .register("reader", "moveParagraph") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val delta = payload.intValue("delta")?.coerceIn(-10_000, 10_000)
                    ?: return@register invalid(traceId, "SOURCE_HOST_READER_DELTA_REQUIRED")
                host.moveParagraph(delta)
                accepted(traceId)
            }
            .register("reader", "setMode") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val raw = payload.stringValue("mode")?.trim().orEmpty()
                val mode = enumValues<ReaderMode>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: return@register invalid(traceId, "SOURCE_HOST_READER_MODE_INVALID:$raw")
                host.setReaderMode(mode)
                accepted(traceId)
            }
            .register("reader", "openChapter") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val chapterId = payload.stringValue("chapterId").orEmpty()
                val url = payload.stringValue("url").orEmpty()
                val chapter = host.state.value.storyDetail?.chapters.orEmpty().firstOrNull { candidate ->
                    (chapterId.isNotBlank() && candidate.id == chapterId) ||
                        (url.isNotBlank() && candidate.url == url)
                } ?: return@register invalid(traceId, "SOURCE_HOST_READER_CHAPTER_NOT_FOUND")
                host.openChapter(chapter)
                accepted(traceId)
            }
            .register("library", "follow") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val storyId = payload.stringValue("storyId")?.trim().orEmpty()
                val detail = host.state.value.storyDetail
                    ?: return@register invalid(traceId, "SOURCE_HOST_LIBRARY_STORY_CONTEXT_REQUIRED")
                if (storyId.isBlank() || detail.story.id != storyId) {
                    return@register invalid(traceId, "SOURCE_HOST_LIBRARY_STORY_CONTEXT_MISMATCH")
                }
                if (host.state.value.following.none { it.storyId == storyId }) host.toggleFollowing()
                accepted(traceId)
            }
            .register("library", "unfollow") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val storyId = payload.stringValue("storyId")?.trim().orEmpty()
                if (storyId.isBlank()) return@register invalid(traceId, "SOURCE_HOST_LIBRARY_STORY_ID_REQUIRED")
                host.unfollowStory(storyId)
                accepted(traceId)
            }
            .register("library", "bookmark") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val content = host.state.value.chapterContent
                    ?: return@register invalid(traceId, "SOURCE_HOST_LIBRARY_CHAPTER_CONTEXT_REQUIRED")
                val requestedChapterId = payload.stringValue("chapterId")?.trim().orEmpty()
                if (requestedChapterId.isNotBlank() && requestedChapterId != content.chapter.id) {
                    return@register invalid(traceId, "SOURCE_HOST_LIBRARY_CHAPTER_CONTEXT_MISMATCH")
                }
                val requestedParagraph = payload.intValue("paragraphIndex")
                if (requestedParagraph != null && requestedParagraph != host.state.value.playback.paragraphIndex) {
                    return@register invalid(traceId, "SOURCE_HOST_LIBRARY_PARAGRAPH_CONTEXT_MISMATCH")
                }
                host.bookmarkCurrent()
                accepted(traceId)
            }
            .register("library", "unbookmark") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val bookmarkId = payload.stringValue("bookmarkId")?.trim().orEmpty()
                if (bookmarkId.isBlank()) return@register invalid(traceId, "SOURCE_HOST_LIBRARY_BOOKMARK_ID_REQUIRED")
                host.deleteBookmark(bookmarkId)
                accepted(traceId)
            }
            .register("tts", "play") { _, _, traceId ->
                ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_PLAY)
                accepted(traceId)
            }
            .register("tts", "pause") { _, _, traceId ->
                ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_PAUSE)
                accepted(traceId)
            }
            .register("tts", "stop") { _, _, traceId ->
                ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_STOP)
                accepted(traceId)
            }
            .register("tts", "toggle") { _, _, traceId ->
                ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_TOGGLE)
                accepted(traceId)
            }
            .register("tts", "setRate") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val rate = payload.floatValue("rate")?.coerceIn(0.25f, 4.0f)
                    ?: return@register invalid(traceId, "SOURCE_HOST_TTS_RATE_REQUIRED")
                host.setTtsRate(rate)
                ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_REFRESH)
                accepted(traceId)
            }
            .register("tts", "setPitch") { _, payload, traceId ->
                val host = host() ?: return@register uiUnavailable(traceId)
                val pitch = payload.floatValue("pitch")?.coerceIn(0.25f, 4.0f)
                    ?: return@register invalid(traceId, "SOURCE_HOST_TTS_PITCH_REQUIRED")
                host.setTtsPitch(pitch)
                ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_REFRESH)
                accepted(traceId)
            }
        SourceHostKernelBus.install(dispatcher)
        return dispatcher
    }
}

private fun accepted(traceId: String): SourcePlatformResult<JsonValue> = SourcePlatformResult.Success(
    JsonValue.Obj(linkedMapOf(
        "accepted" to JsonValue.Bool(true),
        "traceId" to JsonValue.Str(traceId),
    )),
)

private fun uiUnavailable(traceId: String): SourcePlatformResult<JsonValue> =
    invalid(traceId, "SOURCE_HOST_UI_SESSION_UNAVAILABLE")

private fun invalid(traceId: String, message: String): SourcePlatformResult<JsonValue> =
    SourcePlatformResult.Failure(
        SourcePlatformFailure(
            code = SourceErrorCode.INTERNAL_ERROR,
            message = message,
            traceId = traceId,
        ),
    )

private fun JsonValue.Obj.stringValue(name: String): String? = (values[name] as? JsonValue.Str)?.value
private fun JsonValue.Obj.intValue(name: String): Int? = (values[name] as? JsonValue.Num)?.raw?.toIntOrNull()
private fun JsonValue.Obj.floatValue(name: String): Float? = (values[name] as? JsonValue.Num)?.raw?.toFloatOrNull()
