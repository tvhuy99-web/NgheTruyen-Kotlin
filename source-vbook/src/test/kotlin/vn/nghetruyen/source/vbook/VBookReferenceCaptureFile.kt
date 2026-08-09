package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

data class VBookReferenceCaptureCase(
    val id: String,
    val artifactId: String,
    val profile: VBookContractProfile,
    val features: Set<VBookFeature>,
    val script: String,
    val args: List<String>,
    val sourceHashes: Map<String, String>,
    val response: JsonValue.Obj,
) {
    fun officialCapture(): VBookOfficialTestCapture = VBookOfficialTestCapture(
        caseId = id,
        artifactId = artifactId,
        profile = profile,
        script = script,
        args = args,
        responseJson = JsonCodec.stringify(response),
    )
}

data class VBookReferenceCaptureFile(
    val referenceServer: String,
    val capturedAtEpochMs: Long,
    val planSha256: String,
    val cases: List<VBookReferenceCaptureCase>,
)

object VBookReferenceCaptureParser {
    private val sha256 = Regex("[0-9a-f]{64}")

    fun parse(json: String): VBookReferenceCaptureFile {
        val root = JsonCodec.parse(json, maxDepth = 96, maxNodes = 500_000) as? JsonValue.Obj
            ?: error("VBOOK_REFERENCE_CAPTURE_OBJECT_REQUIRED")
        require(root.int("schema") == 1) { "VBOOK_REFERENCE_CAPTURE_SCHEMA_UNSUPPORTED" }
        val server = root.string("referenceServer")?.takeIf(String::isNotBlank)
            ?: error("VBOOK_REFERENCE_SERVER_REQUIRED")
        val planHash = root.string("planSha256")?.takeIf(sha256::matches)
            ?: error("VBOOK_REFERENCE_PLAN_HASH_INVALID")
        val cases = root.array("cases")?.values.orEmpty().map { raw ->
            val obj = raw as? JsonValue.Obj ?: error("VBOOK_REFERENCE_CASE_OBJECT_REQUIRED")
            val profile = parseProfile(obj.string("profile"))
            val sourceHashes = obj.obj("sourceHashes")?.values.orEmpty().mapValues { (_, value) ->
                (value as? JsonValue.Str)?.value?.takeIf(sha256::matches)
                    ?: error("VBOOK_REFERENCE_SOURCE_HASH_INVALID")
            }
            VBookReferenceCaptureCase(
                id = obj.string("id")?.takeIf(String::isNotBlank) ?: error("VBOOK_REFERENCE_CASE_ID_REQUIRED"),
                artifactId = obj.string("artifactId")?.takeIf(String::isNotBlank) ?: error("VBOOK_REFERENCE_ARTIFACT_ID_REQUIRED"),
                profile = profile,
                features = obj.array("features")?.values.orEmpty().mapTo(linkedSetOf()) { value ->
                    val name = (value as? JsonValue.Str)?.value ?: error("VBOOK_REFERENCE_FEATURE_INVALID")
                    VBookFeature.entries.firstOrNull { it.name == name } ?: error("VBOOK_REFERENCE_FEATURE_UNKNOWN:$name")
                },
                script = obj.string("script")?.takeIf(String::isNotBlank) ?: error("VBOOK_REFERENCE_SCRIPT_REQUIRED"),
                args = obj.array("args")?.values.orEmpty().map { value ->
                    (value as? JsonValue.Str)?.value ?: error("VBOOK_REFERENCE_ARG_STRING_REQUIRED")
                },
                sourceHashes = sourceHashes,
                response = obj.obj("response") ?: error("VBOOK_REFERENCE_RESPONSE_REQUIRED"),
            )
        }
        require(cases.map(VBookReferenceCaptureCase::id).distinct().size == cases.size) { "VBOOK_REFERENCE_CASE_ID_DUPLICATE" }
        return VBookReferenceCaptureFile(
            referenceServer = server,
            capturedAtEpochMs = root.long("capturedAtEpochMs") ?: 0L,
            planSha256 = planHash,
            cases = cases,
        )
    }

    private fun parseProfile(raw: String?): VBookContractProfile = when (raw?.trim()?.lowercase()) {
        "current_js", "current-js", "current" -> VBookContractProfile.CURRENT_JS
        "legacy_js", "legacy-js", "legacy" -> VBookContractProfile.LEGACY_JS
        else -> error("VBOOK_REFERENCE_PROFILE_INVALID:${raw.orEmpty()}")
    }
}
