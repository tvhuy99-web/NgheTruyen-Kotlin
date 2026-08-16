package vn.nghetruyen.source.api








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
        "hooks" to setOf("emit", "poll"),
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

    fun encode(command: SourceHostCommand): JsonValue.Obj {
        validate(command)
        return JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str(COMMAND_KIND),
            "version" to JsonValue.Num(API_VERSION.toDouble(), API_VERSION.toString()),
            "domain" to JsonValue.Str(command.domain),
            "action" to JsonValue.Str(command.action),
            "payload" to command.payload,
        ))
    }

    fun encode(event: SourceHostEvent): JsonValue.Obj {
        validate(event)
        return JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str(EVENT_KIND),
            "version" to JsonValue.Num(API_VERSION.toDouble(), API_VERSION.toString()),
            "name" to JsonValue.Str(event.name),
            "payload" to event.payload,
        ))
    }

    fun parseCommand(value: JsonValue): SourceHostCommand {
        val obj = value as? JsonValue.Obj ?: error("SOURCE_HOST_COMMAND_OBJECT_REQUIRED")
        require(string(obj, "kind") == COMMAND_KIND) { "SOURCE_HOST_COMMAND_KIND_INVALID" }
        require(int(obj, "version") == API_VERSION) { "SOURCE_HOST_COMMAND_VERSION_UNSUPPORTED" }
        return command(
            domain = string(obj, "domain") ?: error("SOURCE_HOST_COMMAND_DOMAIN_REQUIRED"),
            action = string(obj, "action") ?: error("SOURCE_HOST_COMMAND_ACTION_REQUIRED"),
            payload = payloadObject(obj, "SOURCE_HOST_COMMAND_PAYLOAD_OBJECT_REQUIRED"),
        )
    }

    fun parseEvent(value: JsonValue): SourceHostEvent {
        val obj = value as? JsonValue.Obj ?: error("SOURCE_HOST_EVENT_OBJECT_REQUIRED")
        require(string(obj, "kind") == EVENT_KIND) { "SOURCE_HOST_EVENT_KIND_INVALID" }
        require(int(obj, "version") == API_VERSION) { "SOURCE_HOST_EVENT_VERSION_UNSUPPORTED" }
        return event(
            name = string(obj, "name") ?: error("SOURCE_HOST_EVENT_NAME_REQUIRED"),
            payload = payloadObject(obj, "SOURCE_HOST_EVENT_PAYLOAD_OBJECT_REQUIRED"),
        )
    }

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

    private fun payloadObject(obj: JsonValue.Obj, errorCode: String): JsonValue.Obj {
        val payload = obj.values["payload"] ?: return JsonValue.Obj()
        return payload as? JsonValue.Obj ?: error(errorCode)
    }

    private fun string(obj: JsonValue.Obj, key: String): String? =
        (obj.values[key] as? JsonValue.Str)?.value

    private fun int(obj: JsonValue.Obj, key: String): Int? =
        (obj.values[key] as? JsonValue.Num)?.raw?.toIntOrNull()
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
