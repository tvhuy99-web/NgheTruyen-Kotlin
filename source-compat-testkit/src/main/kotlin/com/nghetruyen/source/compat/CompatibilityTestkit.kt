package com.nghetruyen.source.compat

import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceCompatibilityState
import com.nghetruyen.source.platform.SourceEcosystem

/** Stable JSON-shaped snapshot format used by corpus and differential tests. */
sealed interface CompatValue {
    object Null : CompatValue
    data class Bool(val value: Boolean) : CompatValue
    data class NumberValue(val value: String) : CompatValue
    data class StringValue(val value: String) : CompatValue
    data class ArrayValue(val values: List<CompatValue>) : CompatValue
    data class ObjectValue(val values: Map<String, CompatValue>) : CompatValue
}

data class CompatibilityRequestSnapshot(
    val url: String? = null,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class CompatibilitySnapshot(
    val statusCode: Int? = null,
    val data: CompatValue = CompatValue.Null,
    val continuation: String? = null,
    val request: CompatibilityRequestSnapshot? = null,
    val cookies: Map<String, String> = emptyMap(),
    val variables: Map<String, String> = emptyMap(),
)

data class CompatibilityCase(
    val caseId: String,
    val artifactId: String,
    val ecosystem: SourceEcosystem,
    val profile: SourceCompatibilityProfile?,
    val action: String,
    val input: List<String>,
    val expected: CompatibilitySnapshot,
    val requiredFeatures: Set<String> = emptySet(),
) {
    init {
        require(caseId.isNotBlank())
        require(artifactId.isNotBlank())
        require(action.isNotBlank())
        require(profile == null || profile.ecosystem == ecosystem)
    }
}

enum class CompatibilityDifferenceKind {
    TYPE_MISMATCH,
    VALUE_MISMATCH,
    MISSING_VALUE,
    UNEXPECTED_VALUE,
    ARRAY_LENGTH_MISMATCH,
}

data class CompatibilityDifference(
    val path: String,
    val kind: CompatibilityDifferenceKind,
    val expected: String?,
    val actual: String?,
)

data class CompatibilityCompareOptions(
    val ignoredPaths: Set<String> = emptySet(),
    val unorderedArrayPaths: Set<String> = emptySet(),
)

enum class CompatibilityVerdict {
    PASS,
    FAIL,
    UNSUPPORTED,
    UPSTREAM_UNAVAILABLE,
    INVALID_FIXTURE,
}

data class CompatibilityResult(
    val caseId: String,
    val verdict: CompatibilityVerdict,
    val differences: List<CompatibilityDifference> = emptyList(),
    val unsupportedFeatures: Set<String> = emptySet(),
    val message: String? = null,
)

class SemanticCompatibilityComparator(
    private val options: CompatibilityCompareOptions = CompatibilityCompareOptions(),
) {
    fun compare(caseId: String, expected: CompatibilitySnapshot, actual: CompatibilitySnapshot): CompatibilityResult {
        val differences = mutableListOf<CompatibilityDifference>()
        compareScalar("$.statusCode", expected.statusCode?.toString(), actual.statusCode?.toString(), differences)
        compareValue("$.data", expected.data, actual.data, differences)
        compareScalar("$.continuation", expected.continuation, actual.continuation, differences)
        compareRequest(expected.request, actual.request, differences)
        compareStringMap("$.cookies", expected.cookies, actual.cookies, differences)
        compareStringMap("$.variables", expected.variables, actual.variables, differences)
        return CompatibilityResult(
            caseId = caseId,
            verdict = if (differences.isEmpty()) CompatibilityVerdict.PASS else CompatibilityVerdict.FAIL,
            differences = differences,
        )
    }

    private fun compareRequest(
        expected: CompatibilityRequestSnapshot?,
        actual: CompatibilityRequestSnapshot?,
        differences: MutableList<CompatibilityDifference>,
    ) {
        if (isIgnored("$.request")) return
        if (expected == null || actual == null) {
            if (expected != actual) {
                differences += CompatibilityDifference(
                    "$.request",
                    if (expected == null) CompatibilityDifferenceKind.UNEXPECTED_VALUE else CompatibilityDifferenceKind.MISSING_VALUE,
                    expected?.toString(),
                    actual?.toString(),
                )
            }
            return
        }
        compareScalar("$.request.url", expected.url, actual.url, differences)
        compareScalar("$.request.method", expected.method, actual.method, differences)
        compareStringMap("$.request.headers", expected.headers, actual.headers, differences)
        compareScalar("$.request.body", expected.body, actual.body, differences)
    }

    private fun compareStringMap(
        path: String,
        expected: Map<String, String>,
        actual: Map<String, String>,
        differences: MutableList<CompatibilityDifference>,
    ) {
        if (isIgnored(path)) return
        val keys = (expected.keys + actual.keys).toSortedSet()
        keys.forEach { key -> compareScalar("$path.$key", expected[key], actual[key], differences) }
    }

    private fun compareScalar(
        path: String,
        expected: String?,
        actual: String?,
        differences: MutableList<CompatibilityDifference>,
    ) {
        if (isIgnored(path)) return
        if (expected == actual) return
        val kind = when {
            expected == null -> CompatibilityDifferenceKind.UNEXPECTED_VALUE
            actual == null -> CompatibilityDifferenceKind.MISSING_VALUE
            else -> CompatibilityDifferenceKind.VALUE_MISMATCH
        }
        differences += CompatibilityDifference(path, kind, expected, actual)
    }

    private fun compareValue(
        path: String,
        expected: CompatValue,
        actual: CompatValue,
        differences: MutableList<CompatibilityDifference>,
    ) {
        if (isIgnored(path)) return
        if (expected::class != actual::class) {
            differences += CompatibilityDifference(
                path,
                CompatibilityDifferenceKind.TYPE_MISMATCH,
                expected.typeName(),
                actual.typeName(),
            )
            return
        }
        when {
            expected is CompatValue.Null && actual is CompatValue.Null -> Unit
            expected is CompatValue.Bool && actual is CompatValue.Bool ->
                compareScalar(path, expected.value.toString(), actual.value.toString(), differences)
            expected is CompatValue.NumberValue && actual is CompatValue.NumberValue ->
                compareScalar(path, expected.value, actual.value, differences)
            expected is CompatValue.StringValue && actual is CompatValue.StringValue ->
                compareScalar(path, expected.value, actual.value, differences)
            expected is CompatValue.ArrayValue && actual is CompatValue.ArrayValue ->
                compareArray(path, expected.values, actual.values, differences)
            expected is CompatValue.ObjectValue && actual is CompatValue.ObjectValue ->
                compareObject(path, expected.values, actual.values, differences)
        }
    }

    private fun compareArray(
        path: String,
        expected: List<CompatValue>,
        actual: List<CompatValue>,
        differences: MutableList<CompatibilityDifference>,
    ) {
        if (path in options.unorderedArrayPaths) {
            val expectedCanonical = expected.map { it.canonical() }.sorted()
            val actualCanonical = actual.map { it.canonical() }.sorted()
            if (expectedCanonical != actualCanonical) {
                differences += CompatibilityDifference(
                    path,
                    CompatibilityDifferenceKind.VALUE_MISMATCH,
                    expectedCanonical.toString(),
                    actualCanonical.toString(),
                )
            }
            return
        }
        if (expected.size != actual.size) {
            differences += CompatibilityDifference(
                path,
                CompatibilityDifferenceKind.ARRAY_LENGTH_MISMATCH,
                expected.size.toString(),
                actual.size.toString(),
            )
        }
        repeat(minOf(expected.size, actual.size)) { index ->
            compareValue("$path[$index]", expected[index], actual[index], differences)
        }
    }

    private fun compareObject(
        path: String,
        expected: Map<String, CompatValue>,
        actual: Map<String, CompatValue>,
        differences: MutableList<CompatibilityDifference>,
    ) {
        val keys = (expected.keys + actual.keys).toSortedSet()
        keys.forEach { key ->
            val childPath = "$path.$key"
            if (isIgnored(childPath)) return@forEach
            val left = expected[key]
            val right = actual[key]
            when {
                left == null -> differences += CompatibilityDifference(
                    childPath,
                    CompatibilityDifferenceKind.UNEXPECTED_VALUE,
                    null,
                    right?.canonical(),
                )
                right == null -> differences += CompatibilityDifference(
                    childPath,
                    CompatibilityDifferenceKind.MISSING_VALUE,
                    left.canonical(),
                    null,
                )
                else -> compareValue(childPath, left, right, differences)
            }
        }
    }

    private fun isIgnored(path: String): Boolean = path in options.ignoredPaths
}

data class CompatibilityFeatureStatus(
    val featureId: String,
    val state: SourceCompatibilityState,
    val passingCases: Int = 0,
    val failingCases: Int = 0,
    val evidence: Set<String> = emptySet(),
)

data class CompatibilityMatrix(
    val ecosystem: SourceEcosystem,
    val referenceVersion: String,
    val features: List<CompatibilityFeatureStatus>,
) {
    init {
        require(referenceVersion.isNotBlank())
        require(features.map { it.featureId }.distinct().size == features.size) {
            "Feature IDs must be unique"
        }
    }

    fun unsupportedFeatures(): Set<String> = features
        .filter { it.state == SourceCompatibilityState.UNSUPPORTED || it.state == SourceCompatibilityState.PARTIAL }
        .mapTo(linkedSetOf()) { it.featureId }
}

/**
 * Guardrail used by CI to keep compatibility engines site-agnostic.
 * It intentionally scans only explicit URL literals to avoid matching package names.
 */
object SourceSpecificityGuard {
    private val urlLiteral = Regex("(?i)https?://([a-z0-9.-]+)")

    fun findUnexpectedHosts(sourceText: String, allowedHosts: Set<String> = emptySet()): Set<String> {
        val normalizedAllowed = allowedHosts.map(String::lowercase).toSet()
        return urlLiteral.findAll(sourceText)
            .map { it.groupValues[1].lowercase().trimEnd('.') }
            .filter { it !in normalizedAllowed }
            .toSortedSet()
    }
}

private fun CompatValue.typeName(): String = when (this) {
    CompatValue.Null -> "null"
    is CompatValue.Bool -> "boolean"
    is CompatValue.NumberValue -> "number"
    is CompatValue.StringValue -> "string"
    is CompatValue.ArrayValue -> "array"
    is CompatValue.ObjectValue -> "object"
}

private fun CompatValue.canonical(): String = when (this) {
    CompatValue.Null -> "null"
    is CompatValue.Bool -> value.toString()
    is CompatValue.NumberValue -> value
    is CompatValue.StringValue -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    is CompatValue.ArrayValue -> values.joinToString(prefix = "[", postfix = "]") { it.canonical() }
    is CompatValue.ObjectValue -> values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"$key\":${value.canonical()}"
    }
}
