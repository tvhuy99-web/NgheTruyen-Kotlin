package vn.nghetruyen.app.sourceplatform

import android.app.Application
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.playback.ReaderPlaybackService
import vn.nghetruyen.app.ui.AppViewModel
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceHostKernelDispatcher
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult

/**
 * NgheTruyen-owned host bindings for extension kernel v2.
 *
 * The extension side only sends SourceHostCommand JSON values. This adapter intentionally owns all
 * Android/ViewModel interaction so no Activity, Context, Service or repository instance crosses the
 * extension boundary.
 */
fun AppViewModel.createExtensionHostKernel(): SourceHostKernelDispatcher {
    val app = getApplication<Application>()
    return SourceHostKernelDispatcher()
        .register("reader", "refresh") { _, _, traceId ->
            ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_REFRESH)
            accepted(traceId)
        }
        .register("reader", "nextChapter") { _, _, traceId ->
            nextChapter()
            accepted(traceId)
        }
        .register("reader", "previousChapter") { _, _, traceId ->
            previousChapter()
            accepted(traceId)
        }
        .register("reader", "moveParagraph") { _, payload, traceId ->
            val delta = payload.intValue("delta")?.coerceIn(-10_000, 10_000)
                ?: return@register invalid(traceId, "SOURCE_HOST_READER_DELTA_REQUIRED")
            moveParagraph(delta)
            accepted(traceId)
        }
        .register("reader", "setMode") { _, payload, traceId ->
            val raw = payload.stringValue("mode")?.trim().orEmpty()
            val mode = enumValues<ReaderMode>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return@register invalid(traceId, "SOURCE_HOST_READER_MODE_INVALID:$raw")
            setReaderMode(mode)
            accepted(traceId)
        }
        .register("reader", "openChapter") { _, payload, traceId ->
            val chapterId = payload.stringValue("chapterId").orEmpty()
            val url = payload.stringValue("url").orEmpty()
            val chapter = state.value.storyDetail?.chapters.orEmpty().firstOrNull { candidate ->
                (chapterId.isNotBlank() && candidate.id == chapterId) ||
                    (url.isNotBlank() && candidate.url == url)
            } ?: return@register invalid(traceId, "SOURCE_HOST_READER_CHAPTER_NOT_FOUND")
            openChapter(chapter)
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
            val rate = payload.floatValue("rate")?.coerceIn(0.25f, 4.0f)
                ?: return@register invalid(traceId, "SOURCE_HOST_TTS_RATE_REQUIRED")
            setTtsRate(rate)
            ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_REFRESH)
            accepted(traceId)
        }
        .register("tts", "setPitch") { _, payload, traceId ->
            val pitch = payload.floatValue("pitch")?.coerceIn(0.25f, 4.0f)
                ?: return@register invalid(traceId, "SOURCE_HOST_TTS_PITCH_REQUIRED")
            setTtsPitch(pitch)
            ReaderPlaybackService.command(app, ReaderPlaybackService.ACTION_REFRESH)
            accepted(traceId)
        }
}

private fun accepted(traceId: String): SourcePlatformResult<JsonValue> = SourcePlatformResult.Success(
    JsonValue.Obj(linkedMapOf(
        "accepted" to JsonValue.Bool(true),
        "traceId" to JsonValue.Str(traceId),
    )),
)

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
