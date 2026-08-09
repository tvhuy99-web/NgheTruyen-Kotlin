package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** Offline corpus analyzer. Acquisition is handled by scripts/fetch_vbook_corpus.py. */
fun main(args: Array<String>) {
    val root = Path.of(args.getOrNull(0) ?: "build/vbook-corpus/packages")
    require(root.exists() && root.isDirectory()) { "VBOOK_CORPUS_DIRECTORY_MISSING:$root" }
    val audits = Files.list(root).use { stream ->
        stream.iterator().asSequence()
            .filter(Files::isDirectory)
            .sortedBy(Path::toString)
            .map(::auditDirectory)
            .toList()
    }
    val report = VBookCorpusAnalyzer.aggregate(audits)
    val json = report.toJson(audits)
    val encoded = JsonCodec.stringify(json)
    args.getOrNull(1)?.let { output ->
        val path = Path.of(output)
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, encoded)
    } ?: println(encoded)
}

private fun auditDirectory(dir: Path): VBookExtensionAudit {
    val plugin = dir.resolve("plugin.json")
    require(Files.isRegularFile(plugin)) { "VBOOK_PLUGIN_JSON_MISSING:$dir" }
    val src = dir.resolve("src")
    val scripts = if (Files.isDirectory(src)) {
        Files.walk(src).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".js", true) }
                .associate { file ->
                    "src/" + src.relativize(file).toString().replace('\\', '/') to file.readText()
                }
        }
    } else emptyMap()
    return VBookCorpusAnalyzer.audit(dir.name, plugin.readText(), scripts)
}

private fun VBookCorpusReport.toJson(audits: List<VBookExtensionAudit>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
    "schema" to JsonValue.Num(2.0, "2"),
    "extensionCount" to number(extensionCount),
    "profiles" to JsonValue.Obj(profiles.entries.associateTo(linkedMapOf()) { it.key.name to number(it.value) }),
    "contentTypes" to JsonValue.Obj(contentTypes.entries.associateTo(linkedMapOf()) { it.key.name to number(it.value) }),
    "features" to JsonValue.Arr(features.map { row ->
        val support = VBookEngineFeatureMatrix.support(row.feature)
        JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(row.feature.name),
            "count" to number(row.extensionCount),
            "implementation" to JsonValue.Str(support.implementation.name),
            "note" to JsonValue.Str(support.note),
            "extensions" to JsonValue.Arr(row.extensionIds.map(JsonValue::Str)),
        ))
    }),
    "blockingFeatures" to JsonValue.Arr(
        VBookEngineFeatureMatrix.matrix(this).blockingFeatures.map { JsonValue.Str(it.feature.name) },
    ),
    "extensions" to JsonValue.Arr(audits.sortedBy(VBookExtensionAudit::id).map { audit ->
        val blocking = audit.features.filter { feature ->
            if (feature == VBookFeature.METADATA_ENCRYPT) return@filter false
            VBookEngineFeatureMatrix.support(feature).implementation in setOf(
                VBookFeatureImplementationLevel.PARTIAL,
                VBookFeatureImplementationLevel.EXPLICITLY_UNSUPPORTED,
                VBookFeatureImplementationLevel.PACKAGE_LAYER_PENDING,
            )
        }
        JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(audit.id),
            "profile" to JsonValue.Str(audit.detection.profile.name),
            "currentScore" to number(audit.detection.currentScore),
            "legacyScore" to number(audit.detection.legacyScore),
            "contentType" to JsonValue.Str(audit.manifest.metadata.type.name),
            "missingRequiredScripts" to JsonValue.Arr(audit.missingRequiredScripts.sorted().map(JsonValue::Str)),
            "missingDynamicScripts" to JsonValue.Arr(audit.missingReferencedScripts.sorted().map(JsonValue::Str)),
            "features" to JsonValue.Arr(audit.features.map(Enum<*>::name).sorted().map(JsonValue::Str)),
            "blockingFeatures" to JsonValue.Arr(blocking.map(Enum<*>::name).sorted().map(JsonValue::Str)),
        ))
    }),
))

private fun number(value: Int): JsonValue.Num = JsonValue.Num(value.toDouble(), value.toString())
