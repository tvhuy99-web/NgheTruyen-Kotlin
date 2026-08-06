package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourcePermissionDiff

data class SourcePackUiInfo(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val installedVersions: List<String>,
    val canRollback: Boolean,
    val signerKeyId: String,
    val runtimeMode: String,
    val commentCapability: String,
    val commentFixtureCount: Int,
)

data class SourceInstallPreview(
    val sourceId: String,
    val name: String,
    val version: String,
    val signerKeyId: String,
    val permissionDiff: SourcePermissionDiff,
    val permissionSummary: List<String>,
    val fixtureCount: Int,
)

data class SourceRepositoryUiInfo(
    val id: String,
    val name: String,
    val url: String,
    val generatedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val packageCount: Int,
    val signerKeyId: String,
)

data class SourceRepositoryPackageUiInfo(
    val repositoryId: String,
    val sourceId: String,
    val name: String,
    val version: String,
    val installedVersion: String?,
    val description: String,
    val changelog: String,
    val packageBytes: Int,
    val status: String,
    val canInstall: Boolean,
)

data class SourceDiagnosticUi(
    val timestampEpochMs: Long,
    val traceId: String,
    val sourceId: String,
    val category: String,
    val name: String,
    val severity: String,
    val durationMs: Long?,
    val detail: String,
)


data class SourceTrustKeyUi(
    val keyId: String,
    val algorithm: String,
    val fingerprint: String,
    val builtin: Boolean,
)

data class SourceTraceUi(
    val traceId: String,
    val sourceId: String,
    val eventCount: Int,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val failed: Boolean,
)

data class SourceSelectorInspectionUi(
    val selector: String,
    val matchCount: Int,
    val samples: List<String>,
)
