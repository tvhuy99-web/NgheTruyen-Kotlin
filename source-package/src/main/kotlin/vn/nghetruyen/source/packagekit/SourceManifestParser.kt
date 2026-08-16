package vn.nghetruyen.source.packagekit

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceBrowserCapability
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceFixtureSpec
import vn.nghetruyen.source.api.SourceFullAuthorityPolicy
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourcePrivacyDisclosure
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.api.SourceUiActionContext
import vn.nghetruyen.source.api.SourceUiActionSpec
import vn.nghetruyen.source.api.SourceWebSocketCapability

object SourceManifestParser {
    fun parse(raw: ByteArray): SourceManifest {
        require(raw.size <= 1024 * 1024) { "SOURCE_MANIFEST_TOO_LARGE" }
        val text = raw.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(raw)) { "SOURCE_MANIFEST_NOT_UTF8" }
        val root = JsonCodec.parse(text) as? JsonValue.Obj ?: error("SOURCE_MANIFEST_NOT_OBJECT")
        root.requireOnly(ROOT_KEYS, "manifest")
        val declaredManifest = SourceManifest(
            schemaVersion = root.requiredInt("schemaVersion"),
            id = root.requiredString("id"),
            name = root.requiredString("name"),
            description = root.string("description").orEmpty(),
            author = root.string("author").orEmpty(),
            version = SemanticVersion.parse(root.requiredString("version")),
            apiVersion = root.requiredInt("apiVersion"),
            minAppVersion = root.string("minAppVersion")?.let(SemanticVersion::parse),
            maxAppVersion = root.string("maxAppVersion")?.let(SemanticVersion::parse),
            locale = root.string("locale") ?: "vi-VN",
            contentType = enumValue(root.string("contentType") ?: "NOVEL", "contentType"),
            adult = root.bool("adult") ?: false,
            runtime = parseRuntime(root.requiredObj("runtime")),
            urlPatterns = root.stringList("urlPatterns"),
            origins = root.stringList("origins").toSet(),
            redirectOrigins = root.stringList("redirectOrigins").toSet(),
            capabilities = parseCapabilities(root.obj("capabilities") ?: JsonValue.Obj()),
            actions = parseActions(root.requiredObj("actions")),
            uiActions = parseUiActions(root.array("uiActions")),
            privacy = parsePrivacy(root.obj("privacy")),
            fixtures = parseFixtures(root.array("fixtures")),
        )
        
        
        
        declaredManifest.validate()

        
        
        
        
        return SourceFullAuthorityPolicy.apply(declaredManifest)
    }

    private fun parseRuntime(value: JsonValue.Obj): SourceRuntimePolicy {
        value.requireOnly(RUNTIME_KEYS, "runtime")
        return SourceRuntimePolicy(
            mode = enumValue(value.requiredString("mode"), "runtime.mode"),
            entry = value.string("entry"),
            instructionBudget = value.int("instructionBudget") ?: 200_000,
            memoryBudgetBytes = value.int("memoryBudgetBytes") ?: 16 * 1024 * 1024,
            actionTimeoutMs = value.long("actionTimeoutMs") ?: 30_000,
        )
    }

    private fun parseCapabilities(value: JsonValue.Obj): SourceCapabilities {
        value.requireOnly(CAPABILITY_KEYS, "capabilities")
        val network = value.obj("network")?.let { item ->
            item.requireOnly(NETWORK_KEYS, "capabilities.network")
            SourceNetworkCapability(
                methods = item.stringList("methods").map(String::uppercase).toSet().ifEmpty { setOf("GET") },
                maxResponseBytes = item.int("maxResponseBytes") ?: 4 * 1024 * 1024,
                maxRequestBytes = item.int("maxRequestBytes") ?: 0,
                requestsPerMinute = item.int("requestsPerMinute") ?: 60,
                maxConcurrent = item.int("maxConcurrent") ?: 2,
                publicInternet = item.bool("publicInternet") ?: false,
                allowCleartext = item.bool("allowCleartext") ?: false,
            )
        }
        val browser = value.obj("browser")?.let { item ->
            item.requireOnly(BROWSER_KEYS, "capabilities.browser")
            SourceBrowserCapability(
                navigate = item.bool("navigate") ?: false,
                domSnapshot = item.bool("domSnapshot") ?: false,
                click = item.bool("click") ?: false,
                input = item.bool("input") ?: false,
                requestMetadata = item.bool("requestMetadata") ?: false,
                serviceWorkerCapture = item.bool("serviceWorkerCapture") ?: false,
                pageJavaScript = item.bool("pageJavaScript") ?: false,
            )
        } ?: SourceBrowserCapability()
        val websocket = value.obj("websocket")?.let { item ->
            item.requireOnly(WEBSOCKET_KEYS, "capabilities.websocket")
            SourceWebSocketCapability(
                enabled = item.bool("enabled") ?: false,
                maxMessageBytes = item.int("maxMessageBytes") ?: 64 * 1024,
                maxLifetimeMs = item.long("maxLifetimeMs") ?: 60_000,
            )
        } ?: SourceWebSocketCapability()
        return SourceCapabilities(
            network = network,
            cookies = value.string("cookies")?.let { enumValue<SourceCookieMode>(it, "capabilities.cookies") }
                ?: SourceCookieMode.NONE,
            browser = browser,
            storageBytes = value.int("storageBytes") ?: 0,
            crypto = value.stringList("crypto").map { enumValue<SourceCryptoCapability>(it, "capabilities.crypto") }.toSet(),
            websocket = websocket,
        )
    }

    private fun parseActions(value: JsonValue.Obj): Map<SourceActionName, SourceActionSpec> = buildMap {
        value.requireOnly(SourceActionName.entries.map { it.manifestKey }.toSet(), "actions")
        SourceActionName.entries.forEach { action ->
            val raw = value.obj(action.manifestKey) ?: return@forEach
            raw.requireOnly(ACTION_KEYS, "actions.${action.manifestKey}")
            put(
                action,
                SourceActionSpec(
                    entry = raw.requiredString("entry"),
                    timeoutMs = raw.long("timeoutMs"),
                    maxOutputBytes = raw.int("maxOutputBytes") ?: 1024 * 1024,
                ),
            )
        }
    }

    private fun parseUiActions(value: JsonValue.Arr?): List<SourceUiActionSpec> = value?.values.orEmpty().map { item ->
        val obj = item as? JsonValue.Obj ?: error("SOURCE_UI_ACTION_INVALID")
        obj.requireOnly(UI_ACTION_KEYS, "uiActions")
        SourceUiActionSpec(
            id = obj.requiredString("id"),
            label = obj.requiredString("label"),
            contexts = obj.stringList("contexts").map { enumValue<SourceUiActionContext>(it, "uiActions.contexts") }.toSet(),
            group = obj.string("group").orEmpty(),
            order = obj.int("order") ?: 0,
        )
    }

    private fun parsePrivacy(value: JsonValue.Obj?): SourcePrivacyDisclosure {
        value?.requireOnly(PRIVACY_KEYS, "privacy")
        return SourcePrivacyDisclosure(
            sendsContentToThirdParty = value?.bool("sendsContentToThirdParty") ?: false,
            thirdParties = value?.stringList("thirdParties").orEmpty(),
            note = value?.string("note").orEmpty(),
        )
    }

    private fun parseFixtures(value: JsonValue.Arr?): List<SourceFixtureSpec> = value?.values.orEmpty().map { item ->
        val obj = item as? JsonValue.Obj ?: error("SOURCE_FIXTURE_INVALID")
        obj.requireOnly(FIXTURE_KEYS, "fixtures")
        SourceFixtureSpec(
            name = obj.requiredString("name"),
            action = enumValue(obj.requiredString("action"), "fixture.action"),
            input = obj.requiredString("input"),
            fixture = obj.string("fixture"),
            expected = obj.requiredString("expected"),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == raw.uppercase() } ?: error("SOURCE_ENUM_INVALID:$field:$raw")

    private fun JsonValue.Obj.requiredString(name: String): String = string(name)?.takeIf(String::isNotBlank)
        ?: error("SOURCE_FIELD_REQUIRED:$name")

    private fun JsonValue.Obj.requiredInt(name: String): Int = int(name) ?: error("SOURCE_FIELD_REQUIRED:$name")
    private fun JsonValue.Obj.requiredObj(name: String): JsonValue.Obj = obj(name) ?: error("SOURCE_FIELD_REQUIRED:$name")

    private fun JsonValue.Obj.stringList(name: String): List<String> = array(name)?.values.orEmpty().map { value ->
        (value as? JsonValue.Str)?.value ?: error("SOURCE_ARRAY_STRING_REQUIRED:$name")
    }

    private fun JsonValue.Obj.requireOnly(allowed: Set<String>, scope: String) {
        val unknown = values.keys - allowed
        require(unknown.isEmpty()) { "SOURCE_UNKNOWN_FIELD:$scope:${unknown.joinToString()}" }
    }

    private val ROOT_KEYS = setOf(
        "schemaVersion", "id", "name", "description", "author", "version", "apiVersion",
        "minAppVersion", "maxAppVersion", "locale", "contentType", "adult", "runtime",
        "urlPatterns", "origins", "redirectOrigins", "capabilities", "actions", "uiActions", "privacy", "fixtures",
    )
    private val RUNTIME_KEYS = setOf("mode", "entry", "instructionBudget", "memoryBudgetBytes", "actionTimeoutMs")
    private val CAPABILITY_KEYS = setOf("network", "cookies", "browser", "storageBytes", "crypto", "websocket")
    private val NETWORK_KEYS = setOf(
        "methods", "maxResponseBytes", "maxRequestBytes", "requestsPerMinute", "maxConcurrent",
        "publicInternet", "allowCleartext",
    )
    private val BROWSER_KEYS = setOf("navigate", "domSnapshot", "click", "input", "requestMetadata", "serviceWorkerCapture", "pageJavaScript")
    private val WEBSOCKET_KEYS = setOf("enabled", "maxMessageBytes", "maxLifetimeMs")
    private val ACTION_KEYS = setOf("entry", "timeoutMs", "maxOutputBytes")
    private val UI_ACTION_KEYS = setOf("id", "label", "contexts", "group", "order")
    private val PRIVACY_KEYS = setOf("sendsContentToThirdParty", "thirdParties", "note")
    private val FIXTURE_KEYS = setOf("name", "action", "input", "fixture", "expected")
}