package vn.nghetruyen.source.vbook

import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceCompatibilityState
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceFailure
import com.nghetruyen.source.platform.SourceFailureCode
import com.nghetruyen.source.sandbox.JsSyntaxValidator

data class VBookCandidate(
    val artifactId: String,
    val pluginJson: String,
    /** Keys may be either `name.js` or `src/name.js`; the validator normalizes them. */
    val scripts: Map<String, String>,
)

data class VBookCandidateValidation(
    val candidate: VBookCandidate,
    val audit: VBookExtensionAudit?,
    val state: SourceCompatibilityState,
    val profile: SourceCompatibilityProfile?,
    val failures: List<SourceFailure>,
    val warnings: List<String>,
) {
    val activatable: Boolean get() = failures.isEmpty() && state == SourceCompatibilityState.SUPPORTED
}

fun interface VBookCompileProbe {
    fun validate(scriptPath: String, source: String): String?

    companion object {
        val RHINO = VBookCompileProbe { path, source ->
            JsSyntaxValidator.validate(source, path).takeUnless { it.valid }?.let { result ->
                buildString {
                    append(result.message ?: "syntax error")
                    result.line?.let { append(" at ").append(it) }
                    result.column?.let { append(':').append(it) }
                }
            }
        }
        val NONE = VBookCompileProbe { _, _ -> null }
    }
}

class VBookCandidateValidator(
    private val compileProbe: VBookCompileProbe = VBookCompileProbe.RHINO,
) {
    fun validate(candidate: VBookCandidate): VBookCandidateValidation {
        val parsed = runCatching {
            VBookCorpusAnalyzer.audit(candidate.artifactId, candidate.pluginJson, candidate.scripts)
        }.getOrElse { error ->
            return VBookCandidateValidation(
                candidate = candidate,
                audit = null,
                state = SourceCompatibilityState.UNSUPPORTED,
                profile = null,
                failures = listOf(SourceFailure(
                    SourceFailureCode.ARTIFACT_INVALID,
                    error.message ?: "VBOOK_PLUGIN_INVALID",
                    sourceId = candidate.artifactId,
                )),
                warnings = emptyList(),
            )
        }

        val failures = mutableListOf<SourceFailure>()
        val warnings = mutableListOf<String>()
        if (parsed.detection.profile == VBookContractProfile.UNKNOWN) {
            failures += SourceFailure(
                SourceFailureCode.VBOOK_CONTRACT_UNSUPPORTED,
                "VBOOK_CONTRACT_PROFILE_AMBIGUOUS",
                sourceId = candidate.artifactId,
                details = mapOf(
                    "currentScore" to parsed.detection.currentScore.toString(),
                    "legacyScore" to parsed.detection.legacyScore.toString(),
                ),
            )
        }
        if (parsed.missingRequiredScripts.isNotEmpty()) {
            failures += SourceFailure(
                SourceFailureCode.VBOOK_SCRIPT_MISSING,
                "VBOOK_REQUIRED_SCRIPT_MISSING:${parsed.missingRequiredScripts.sorted().joinToString()}",
                sourceId = candidate.artifactId,
            )
        }
        if (parsed.missingReferencedScripts.isNotEmpty()) {
            failures += SourceFailure(
                SourceFailureCode.VBOOK_SCRIPT_MISSING,
                "VBOOK_DYNAMIC_SCRIPT_MISSING:${parsed.missingReferencedScripts.sorted().joinToString()}",
                sourceId = candidate.artifactId,
            )
        }
        val normalizedScripts = candidate.scripts.entries.associate { (path, source) -> VBookPaths.normalizeScriptPath(path) to source }
        parsed.manifest.allDeclaredScriptPaths().filterNot(normalizedScripts::containsKey).forEach { missing ->
            failures += SourceFailure(
                SourceFailureCode.VBOOK_SCRIPT_MISSING,
                "VBOOK_DECLARED_SCRIPT_MISSING:$missing",
                sourceId = candidate.artifactId,
                details = mapOf("script" to missing),
            )
        }
        normalizedScripts.forEach { (path, source) ->
            compileProbe.validate(path, source)?.let { detail ->
                failures += SourceFailure(
                    SourceFailureCode.VBOOK_RESPONSE_INVALID,
                    "VBOOK_SCRIPT_COMPILE_FAILED:$path:$detail",
                    sourceId = candidate.artifactId,
                    action = path,
                )
            }
        }
        val forbidden = parsed.features.filter { feature -> feature.name.startsWith("JS_FORBIDDEN_") }
        if (forbidden.isNotEmpty()) {
            failures += SourceFailure(
                SourceFailureCode.VBOOK_HOST_API_UNSUPPORTED,
                "VBOOK_RHINO_SYNTAX_UNSUPPORTED:${forbidden.sortedBy(Enum<*>::name).joinToString { it.name }}",
                sourceId = candidate.artifactId,
            )
        }
        if (parsed.manifest.metadata.encrypt) {
            // `encrypt` is package/build metadata. Plain source trees are valid inputs, while encrypted ZIP
            // payload decoding must be proven separately before claiming package-level parity.
            warnings += "VBOOK_ENCRYPTED_DISTRIBUTION_REQUIRES_PACKAGE_DECODER_PROOF"
        }
        if (VBookFeature.LEGACY_HTTP_SOURCE in parsed.features) {
            warnings += "VBOOK_LEGACY_HTTP_REQUIRES_EXPLICIT_CLEARTEXT_POLICY"
        }
        if (parsed.unknownScriptRoles.isNotEmpty()) {
            warnings += "VBOOK_UNKNOWN_SCRIPT_ROLES:${parsed.unknownScriptRoles.sorted().joinToString()}"
        }

        val profile = parsed.detection.profile.takeUnless { it == VBookContractProfile.UNKNOWN }?.let {
            SourceCompatibilityProfile(SourceEcosystem.VBOOK, if (it == VBookContractProfile.CURRENT_JS) "current-js" else "legacy-js")
        }
        return VBookCandidateValidation(
            candidate = candidate,
            audit = parsed,
            state = if (failures.isEmpty()) SourceCompatibilityState.SUPPORTED else SourceCompatibilityState.UNSUPPORTED,
            profile = profile,
            failures = failures,
            warnings = warnings,
        )
    }
}
