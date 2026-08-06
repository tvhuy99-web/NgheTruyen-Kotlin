package vn.nghetruyen.source.packagekit

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceManifest

object SourceManifestWriter {
    fun write(manifest: SourceManifest): ByteArray = JsonCodec.stringify(toJson(manifest)).toByteArray(Charsets.UTF_8)

    fun toJson(manifest: SourceManifest): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "schemaVersion" to num(manifest.schemaVersion),
        "id" to JsonValue.Str(manifest.id),
        "name" to JsonValue.Str(manifest.name),
        "description" to JsonValue.Str(manifest.description),
        "author" to JsonValue.Str(manifest.author),
        "version" to JsonValue.Str(manifest.version.toString()),
        "apiVersion" to num(manifest.apiVersion),
        "minAppVersion" to manifest.minAppVersion?.let { JsonValue.Str(it.toString()) }.orNull(),
        "maxAppVersion" to manifest.maxAppVersion?.let { JsonValue.Str(it.toString()) }.orNull(),
        "locale" to JsonValue.Str(manifest.locale),
        "contentType" to JsonValue.Str(manifest.contentType.name),
        "adult" to JsonValue.Bool(manifest.adult),
        "runtime" to JsonValue.Obj(linkedMapOf(
            "mode" to JsonValue.Str(manifest.runtime.mode.name),
            "entry" to manifest.runtime.entry?.let(JsonValue::Str).orNull(),
            "instructionBudget" to num(manifest.runtime.instructionBudget),
            "memoryBudgetBytes" to num(manifest.runtime.memoryBudgetBytes),
            "actionTimeoutMs" to num(manifest.runtime.actionTimeoutMs),
        )),
        "urlPatterns" to strings(manifest.urlPatterns),
        "origins" to strings(manifest.origins.sorted()),
        "redirectOrigins" to strings(manifest.redirectOrigins.sorted()),
        "capabilities" to JsonValue.Obj(linkedMapOf(
            "network" to manifest.capabilities.network?.let { network -> JsonValue.Obj(linkedMapOf(
                "methods" to strings(network.methods.sorted()),
                "maxResponseBytes" to num(network.maxResponseBytes),
                "maxRequestBytes" to num(network.maxRequestBytes),
                "requestsPerMinute" to num(network.requestsPerMinute),
                "maxConcurrent" to num(network.maxConcurrent),
            )) }.orNull(),
            "cookies" to JsonValue.Str(manifest.capabilities.cookies.name),
            "browser" to JsonValue.Obj(linkedMapOf(
                "navigate" to JsonValue.Bool(manifest.capabilities.browser.navigate),
                "domSnapshot" to JsonValue.Bool(manifest.capabilities.browser.domSnapshot),
                "click" to JsonValue.Bool(manifest.capabilities.browser.click),
                "input" to JsonValue.Bool(manifest.capabilities.browser.input),
                "requestMetadata" to JsonValue.Bool(manifest.capabilities.browser.requestMetadata),
                "serviceWorkerCapture" to JsonValue.Bool(manifest.capabilities.browser.serviceWorkerCapture),
                "pageJavaScript" to JsonValue.Bool(manifest.capabilities.browser.pageJavaScript),
            )),
            "storageBytes" to num(manifest.capabilities.storageBytes),
            "crypto" to strings(manifest.capabilities.crypto.map { it.name }.sorted()),
            "websocket" to JsonValue.Obj(linkedMapOf(
                "enabled" to JsonValue.Bool(manifest.capabilities.websocket.enabled),
                "maxMessageBytes" to num(manifest.capabilities.websocket.maxMessageBytes),
                "maxLifetimeMs" to num(manifest.capabilities.websocket.maxLifetimeMs),
            )),
        )),
        "actions" to JsonValue.Obj(LinkedHashMap(manifest.actions.entries.associate { (name, action) ->
            name.manifestKey to JsonValue.Obj(linkedMapOf(
                "entry" to JsonValue.Str(action.entry),
                "timeoutMs" to action.timeoutMs?.let(::num).orNull(),
                "maxOutputBytes" to num(action.maxOutputBytes),
            ))
        })),
        "privacy" to JsonValue.Obj(linkedMapOf(
            "sendsContentToThirdParty" to JsonValue.Bool(manifest.privacy.sendsContentToThirdParty),
            "thirdParties" to strings(manifest.privacy.thirdParties),
            "note" to JsonValue.Str(manifest.privacy.note),
        )),
        "fixtures" to JsonValue.Arr(manifest.fixtures.map { fixture -> JsonValue.Obj(linkedMapOf(
            "name" to JsonValue.Str(fixture.name),
            "action" to JsonValue.Str(fixture.action.name),
            "input" to JsonValue.Str(fixture.input),
            "fixture" to fixture.fixture?.let(JsonValue::Str).orNull(),
            "expected" to JsonValue.Str(fixture.expected),
        )) }),
    ))

    private fun num(value: Number): JsonValue.Num = JsonValue.Num(value.toDouble(), value.toString())
    private fun strings(values: Collection<String>): JsonValue.Arr = JsonValue.Arr(values.map(JsonValue::Str))
    private fun JsonValue?.orNull(): JsonValue = this ?: JsonValue.Null
}
