package vn.nghetruyen.source.api

/**
 * Stable wire contract between an installed extension and NgheTruyen-owned host surfaces.
 *
 * This is not a permission list. Installed extensions already run under FULL_IN_APP authority.
 * The contract only gives host-facing commands/events stable names so JavaScript, Lua and future
 * runtimes can target the same NgheTruyen API without receiving Android/Java implementation objects.
 */
object SourceHostKernelContract {
    const val API_VERSION = 2
    const val COMMAND_KIND = "nghetruyen.host-command"
    const val EVENT_KIND = "nghetruyen.host-event"
    const val MAX_PAYLOAD_BYTES = 256 * 1024

    val commandDomains: Set<String> = setOf("ui", "reader", "library", "tts", "hooks")

    val commandActions: Map<String, Set<String>> = mapOf(
        "ui" to setOf("notify", "open", "refresh", "navigate"),
        "reader" to setOf(
            "refresh", "nextChapter", "previousChapter", "moveParagraph",
            "setMode", "setTextMode", "openChapter",
        ),
        "library" to setOf("follow", "unfollow", "bookmark", "unbookmark", "note", "removeNote"),
        "tts" to setOf("play", "pause", "stop", "toggle", "setRate", "setPitch", "setVoice"),
        "hooks" to setOf("emit"),
    )

    val lifecycleEvents: Set<String> = setOf(
        "app.start", "app.resume", "app.pause",
        "explore.enter", "story.enter", "reader.enter", "reader.leave",
        "reader.chapterChanged", "playback.changed", "library.changed",
    )

    fun command(domain: String, action: String, payload: JsonValue.Obj = JsonValue.Obj()): SourceHostCommand =
        SourceHostCommand(domain, action, payload).also(::validate)

    fun event(name: String, payload: JsonValue.Obj = JsonValue.Obj()): SourceHostEvent =
        SourceHostEvent(name, payload).also(::validate)

    fun validate(command: SourceHostCommand) {
        require(command.domain in commandDomains) { "SOURCE_HOST_COMMAND_DOMAIN_INVALID:${command.domain}" }
        require(command.action in commandActions.getValue(command.domain)) {
            "SOURCE_HOST_COMMAND_ACTION_INVALID:${command.domain}:${command.action}"
        }
        require(JsonCodec.stringify(command.payload).toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "SOURCE_HOST_COMMAND_PAYLOAD_TOO_LARGE"
        }
    }

    fun validate(event: SourceHostEvent) {
        require(event.name in lifecycleEvents) { "SOURCE_HOST_EVENT_INVALID:${event.name}" }
        require(JsonCodec.stringify(event.payload).toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "SOURCE_HOST_EVENT_PAYLOAD_TOO_LARGE"
        }
    }
}

data class SourceHostCommand(
    val domain: String,
    val action: String,
    val payload: JsonValue.Obj = JsonValue.Obj(),
)

data class SourceHostEvent(
    val name: String,
    val payload: JsonValue.Obj = JsonValue.Obj(),
)
