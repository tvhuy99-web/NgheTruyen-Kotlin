package vn.nghetruyen.source.vbook

import com.nghetruyen.source.compat.CompatValue
import com.nghetruyen.source.compat.CompatibilityCase
import com.nghetruyen.source.compat.CompatibilityResult
import com.nghetruyen.source.compat.CompatibilitySnapshot
import com.nghetruyen.source.compat.SemanticCompatibilityComparator
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceEcosystem
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

 
data class VBookOfficialTestCapture(
    val caseId: String,
    val artifactId: String,
    val profile: VBookContractProfile,
    val script: String,
    val args: List<String>,
    val responseJson: String,
)

object VBookDifferentialFixtures {
    fun caseFromOfficialCapture(capture: VBookOfficialTestCapture): CompatibilityCase {
        val response = JsonCodec.parse(capture.responseJson, maxDepth = 96, maxNodes = 300_000) as? JsonValue.Obj
            ?: error("VBOOK_REFERENCE_RESPONSE_OBJECT_REQUIRED")
        val apiCode = response.int("code") ?: error("VBOOK_REFERENCE_API_CODE_REQUIRED")
        require(apiCode == 200) {
            "VBOOK_REFERENCE_TEST_FAILED:$apiCode:${response.string("message") ?: response.string("log").orEmpty()}"
        }
        val nested = response["data"] ?: JsonValue.Null
        val envelope = VBookResponseEnvelopeParser.parse(nested, capture.profile)
        return CompatibilityCase(
            caseId = capture.caseId,
            artifactId = capture.artifactId,
            ecosystem = SourceEcosystem.VBOOK,
            profile = SourceCompatibilityProfile(
                SourceEcosystem.VBOOK,
                if (capture.profile == VBookContractProfile.CURRENT_JS) "current-js" else "legacy-js",
            ),
            action = capture.script,
            input = capture.args,
            expected = CompatibilitySnapshot(
                statusCode = apiCode,
                data = envelope.data.toCompat(),
                continuation = envelope.continuation.token,
            ),
        )
    }

    fun actualSnapshot(result: VBookCompatibilityRuntime.ExecutionResult): CompatibilitySnapshot =
        CompatibilitySnapshot(
            statusCode = 200,
            data = result.data.toCompat(),
            continuation = result.continuation.token,
        )

    fun compare(
        case: CompatibilityCase,
        actual: VBookCompatibilityRuntime.ExecutionResult,
    ): CompatibilityResult = SemanticCompatibilityComparator().compare(case.caseId, case.expected, actualSnapshot(actual))

    private fun JsonValue.toCompat(): CompatValue = when (this) {
        JsonValue.Null -> CompatValue.Null
        is JsonValue.Bool -> CompatValue.Bool(value)
        is JsonValue.Num -> CompatValue.NumberValue(raw)
        is JsonValue.Str -> CompatValue.StringValue(value)
        is JsonValue.Arr -> CompatValue.ArrayValue(values.map { it.toCompat() })
        is JsonValue.Obj -> CompatValue.ObjectValue(values.mapValues { (_, value) -> value.toCompat() })
    }
}
