package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceManifest

/** vBook contracts are intentionally independent from NgheTruyen SourceManifest. */
enum class VBookContractProfile {
    LEGACY_JS,
    CURRENT_JS,
    UNKNOWN,
}

enum class VBookContentType {
    NOVEL,
    CHINESE_NOVEL,
    COMIC,
    VIDEO,
    AUDIO,
    TTS,
    TRANSLATE,
    UNKNOWN;

    val isStorySource: Boolean
        get() = this in setOf(NOVEL, CHINESE_NOVEL, COMIC, VIDEO, AUDIO)

    companion object {
        fun from(raw: String?): VBookContentType = when (raw?.trim()?.lowercase()) {
            "novel" -> NOVEL
            "chinese_novel", "chinese-novel" -> CHINESE_NOVEL
            "comic" -> COMIC
            "video" -> VIDEO
            "audio" -> AUDIO
            "tts" -> TTS
            "translate", "translation" -> TRANSLATE
            else -> UNKNOWN
        }
    }
}

enum class VBookScriptRole(val manifestKey: String, val aliases: Set<String> = emptySet()) {
    HOME("home"),
    EXPLORE("explore"),
    GENRE("genre"),
    SEARCH("search"),
    DETAIL("detail"),
    TOC("toc"),
    CHAP("chap"),
    TRACK("track"),
    PAGE("page"),
    VOICE("voice"),
    TTS("tts"),
    LANGUAGE("language"),
    TRANSLATE("translate"),
    COMMENT("comment"),
    SUGGEST("suggest", setOf("suggests"));

    companion object {
        fun from(key: String): VBookScriptRole? = entries.firstOrNull { it.manifestKey == key || key in it.aliases }
    }
}

enum class VBookConfigMode { INPUT, SELECT, TOGGLE, UNKNOWN }
enum class VBookConfigFormat { TEXT, NUMBER, SINGLE, MULTIPLE, MULTILINE, UNKNOWN }

data class VBookMetadata(
    val name: String,
    val author: String,
    val version: Int,
    val source: String,
    val description: String,
    val locale: String,
    val regexp: String,
    val type: VBookContentType,
    val rawType: String?,
    val nsfw: Boolean,
    val hasNsfwField: Boolean,
    val encrypt: Boolean,
    val language: String?,
    val legacyTag: String?,
    val unknown: Map<String, JsonValue>,
)

data class VBookConfigField(
    val key: String,
    val title: String,
    val subtitle: String,
    val defaultValue: String,
    val values: List<String>,
    val mode: VBookConfigMode,
    val format: VBookConfigFormat,
    val legacyPrimitive: Boolean,
    val sensitive: Boolean,
    val raw: JsonValue,
)

data class VBookExtensionManifest(
    val metadata: VBookMetadata,
    val scripts: Map<String, String>,
    val config: Map<String, VBookConfigField>,
    val unknownTopLevel: Map<String, JsonValue>,
) {
    fun script(role: VBookScriptRole): String? = scripts[role.manifestKey]
        ?: role.aliases.firstNotNullOfOrNull(scripts::get)

    fun allDeclaredScriptPaths(): Set<String> = scripts.values
        .map(VBookPaths::normalizeScriptPath)
        .toSet()
}

data class VBookContractDetection(
    val profile: VBookContractProfile,
    val currentScore: Int,
    val legacyScore: Int,
    val reasons: List<String>,
) {
    val confidence: Int
        get() = kotlin.math.abs(currentScore - legacyScore)
}

object VBookManifestParser {
    private val metadataKnown = setOf(
        "name", "author", "version", "source", "description", "locale", "regexp", "type",
        "nsfw", "encrypt", "language", "tag",
    )
    private val topKnown = setOf("metadata", "script", "config")

    fun parse(json: String): VBookExtensionManifest {
        val root = JsonCodec.parse(json, maxDepth = 64, maxNodes = 50_000) as? JsonValue.Obj
            ?: error("VBOOK_PLUGIN_ROOT_OBJECT_REQUIRED")
        val metadata = root.obj("metadata") ?: error("VBOOK_PLUGIN_METADATA_REQUIRED")
        val scripts = root.obj("script")?.values.orEmpty().mapNotNull { (key, value) ->
            val raw = (value as? JsonValue.Str)?.value?.trim().orEmpty()
            if (raw.isBlank()) null else key to VBookPaths.normalizeScriptPath(raw)
        }.toMap(LinkedHashMap())
        require(scripts.size <= 128) { "VBOOK_TOO_MANY_SCRIPTS" }

        val config = root.obj("config")?.values.orEmpty().entries.take(512).associate { (key, value) ->
            key to parseConfig(key, value)
        }
        val rawType = metadata.string("type")
        val hasNsfwField = "nsfw" in metadata.values
        val nsfw = metadata.bool("nsfw") ?: metadata.string("tag")?.equals("nsfw", ignoreCase = true) == true
        return VBookExtensionManifest(
            metadata = VBookMetadata(
                name = metadata.string("name").orEmpty(),
                author = metadata.string("author").orEmpty(),
                version = metadata.int("version") ?: 0,
                source = metadata.string("source").orEmpty(),
                description = metadata.string("description").orEmpty(),
                locale = metadata.string("locale").orEmpty(),
                regexp = metadata.string("regexp").orEmpty(),
                type = VBookContentType.from(rawType),
                rawType = rawType,
                nsfw = nsfw,
                hasNsfwField = hasNsfwField,
                encrypt = metadata.bool("encrypt") ?: false,
                language = metadata.string("language"),
                legacyTag = metadata.string("tag"),
                unknown = metadata.values.filterKeys { it !in metadataKnown },
            ),
            scripts = scripts,
            config = config,
            unknownTopLevel = root.values.filterKeys { it !in topKnown },
        )
    }

    private fun parseConfig(key: String, raw: JsonValue): VBookConfigField {
        val obj = raw as? JsonValue.Obj
        if (obj == null) {
            return VBookConfigField(
                key = key,
                title = key,
                subtitle = "",
                defaultValue = scalar(raw),
                values = emptyList(),
                mode = VBookConfigMode.UNKNOWN,
                format = VBookConfigFormat.UNKNOWN,
                legacyPrimitive = true,
                sensitive = isSensitiveConfig(key, key, "", null, raw),
                raw = raw,
            )
        }
        val mode = when (obj.string("mode")?.lowercase()) {
            "input" -> VBookConfigMode.INPUT
            "select" -> VBookConfigMode.SELECT
            "toggle" -> VBookConfigMode.TOGGLE
            null -> if ("groups" in obj.values) VBookConfigMode.UNKNOWN else VBookConfigMode.INPUT
            else -> VBookConfigMode.UNKNOWN
        }
        val format = when (obj.string("format")?.lowercase()) {
            "text" -> VBookConfigFormat.TEXT
            "number" -> VBookConfigFormat.NUMBER
            "single" -> VBookConfigFormat.SINGLE
            "multiple" -> VBookConfigFormat.MULTIPLE
            "multiline" -> VBookConfigFormat.MULTILINE
            null -> when (mode) {
                VBookConfigMode.INPUT -> VBookConfigFormat.TEXT
                VBookConfigMode.SELECT, VBookConfigMode.TOGGLE -> VBookConfigFormat.SINGLE
                VBookConfigMode.UNKNOWN -> VBookConfigFormat.UNKNOWN
            }
            else -> VBookConfigFormat.UNKNOWN
        }
        val values = obj.array("values")?.values.orEmpty().map(::scalar)
        return VBookConfigField(
            key = key,
            title = obj.string("title") ?: key,
            subtitle = obj.string("subtitle").orEmpty(),
            defaultValue = obj["default"]?.let(::scalar).orEmpty(),
            values = values,
            mode = mode,
            format = format,
            legacyPrimitive = false,
            sensitive = isSensitiveConfig(key, obj.string("title") ?: key, obj.string("subtitle").orEmpty(), obj, raw),
            raw = raw,
        )
    }

    private fun isSensitiveConfig(
        key: String,
        title: String,
        subtitle: String,
        descriptor: JsonValue.Obj?,
        raw: JsonValue,
    ): Boolean {
        if (descriptor?.bool("secret") == true || descriptor?.bool("secure") == true || descriptor?.bool("sensitive") == true) {
            return true
        }
        val inputType = descriptor?.string("type")?.lowercase().orEmpty()
        if (inputType in setOf("password", "secret", "token")) return true
        val normalized = listOf(key, title, subtitle).joinToString(" ")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
        val compact = normalized.replace(" ", "")
        return SENSITIVE_CONFIG_WORDS.any { word -> Regex("(?:^| )${Regex.escape(word)}(?: |${'$'})").containsMatchIn(normalized) } ||
            SENSITIVE_CONFIG_COMPACT_WORDS.any(compact::contains) ||
            (raw is JsonValue.Obj && raw.string("format")?.equals("password", ignoreCase = true) == true)
    }

    private val SENSITIVE_CONFIG_WORDS = setOf(
        "password", "passwd", "passphrase", "token", "secret", "cookie", "authorization",
        "credential", "credentials", "apikey", "sessionid", "session", "bearer", "username",
        "login", "email", "account",
    )
    private val SENSITIVE_CONFIG_COMPACT_WORDS = setOf(
        "apikey", "accesstoken", "authtoken", "clientsecret", "sessionid", "username",
    )

    private fun scalar(value: JsonValue): String = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        JsonValue.Null -> ""
        else -> JsonCodec.stringify(value)
    }
}

object VBookContractDetector {
    fun detect(manifest: VBookExtensionManifest, scripts: Map<String, String> = emptyMap()): VBookContractDetection {
        var current = 0
        var legacy = 0
        val reasons = mutableListOf<String>()
        val metadata = manifest.metadata

        if (metadata.language != null) {
            legacy += 5
            reasons += "metadata.language is a legacy signal"
        }
        if (metadata.legacyTag != null) {
            legacy += 3
            reasons += "metadata.tag is a legacy signal"
        }
        if (manifest.config.values.any(VBookConfigField::legacyPrimitive)) {
            legacy += 4
            reasons += "primitive config entries are a legacy signal"
        }
        if (metadata.hasNsfwField) {
            current += 2
            reasons += "metadata.nsfw field is a current schema signal"
        }
        if (manifest.config.values.any { !it.legacyPrimitive && it.mode != VBookConfigMode.UNKNOWN }) {
            current += 5
            reasons += "descriptor config entries are a current signal"
        }
        if (manifest.scripts.containsKey("explore")) {
            current += 4
            reasons += "explore script is a current signal"
        }
        val currentOnlyScripts = setOf("track", "page", "voice", "tts", "language", "translate")
        val currentOnlyCount = manifest.scripts.keys.count { it in currentOnlyScripts }
        if (currentOnlyCount > 0) {
            current += 3 + currentOnlyCount
            reasons += "current-only script roles detected: $currentOnlyCount"
        }
        if (metadata.type in setOf(VBookContentType.VIDEO, VBookContentType.AUDIO, VBookContentType.TTS, VBookContentType.TRANSLATE)) {
            current += 5
            reasons += "current-only content type ${metadata.type}"
        }

        val joinedScripts = scripts.values.joinToString("\n", transform = VBookJavaScriptLexicalMask::executable)
        if (Regex("\\bcode\\s*[:=]\\s*(200|403)\\b").containsMatchIn(joinedScripts)) {
            legacy += 2
            reasons += "flat 200/403 response code usage detected"
        }

        val profile = when {
            // The documented current contract is the canonical baseline. Signal-free minimal packages
            // are valid current extensions; legacy must be positively identified by historical fields
            // or response/config syntax instead of making current authors add artificial markers.
            current == 0 && legacy == 0 -> VBookContractProfile.CURRENT_JS
            current >= legacy + 2 -> VBookContractProfile.CURRENT_JS
            legacy >= current + 2 -> VBookContractProfile.LEGACY_JS
            else -> VBookContractProfile.UNKNOWN
        }
        if (current == 0 && legacy == 0) reasons += "no legacy signal; defaulting to canonical current contract"
        return VBookContractDetection(profile, current, legacy, reasons.distinct())
    }
}

object VBookRequiredScripts {
    fun missing(manifest: VBookExtensionManifest, profile: VBookContractProfile): Set<String> {
        if (profile == VBookContractProfile.LEGACY_JS) {
            val required = if (manifest.metadata.type.isStorySource) setOf("search", "detail", "toc", "chap") else emptySet()
            return required - manifest.scripts.keys
        }
        val required = when (manifest.metadata.type) {
            VBookContentType.NOVEL, VBookContentType.CHINESE_NOVEL -> setOf("search", "detail", "toc", "chap")
            // Both page and chap are optional in the documented current comic contract. If neither
            // exists, the host renders the raw chapter URL as a single page.
            VBookContentType.COMIC -> setOf("search", "detail", "toc")
            VBookContentType.VIDEO, VBookContentType.AUDIO -> setOf("search", "detail", "toc", "track")
            VBookContentType.TTS -> setOf("voice", "tts")
            VBookContentType.TRANSLATE -> setOf("language", "translate")
            VBookContentType.UNKNOWN -> emptySet()
        }
        return required - manifest.scripts.keys
    }
}

object VBookPaths {
    fun normalizeScriptPath(raw: String): String {
        val clean = raw.trim().replace('\\', '/').removePrefix("/")
        val path = if (clean.startsWith("src/")) clean else "src/$clean"
        SourceManifest.requireSafeRelativePath(path)
        require(path.endsWith(".js", ignoreCase = true) || path.endsWith(".mjs", ignoreCase = true)) {
            "VBOOK_SCRIPT_EXTENSION_REQUIRED"
        }
        return path
    }

    fun packageRelativeScript(path: String): String = normalizeScriptPath(path).removePrefix("src/")
}
