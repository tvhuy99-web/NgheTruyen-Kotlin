#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count == 0 and new in text:
        return
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrence(s), found {count}: {old!r}")
    file.write_text(text.replace(old, new), encoding="utf-8")


replace_exact(
    "app/src/main/java/vn/nghetruyen/app/MainActivity.kt",
    "            )(::finishAudioExport)",
    "            ) { uri -> finishAudioExport(uri) }",
    expected=4,
)

replace_exact(
    "app/src/main/java/vn/nghetruyen/app/diagnostics/PerformanceDiagnostics.kt",
    "            pssKiB = Debug.getPss(),",
    "            pssKiB = Debug.getPss().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),",
)

replace_exact(
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt",
    """        val compatible = (entry.minAppVersion == null || CURRENT_APP_VERSION >= entry.minAppVersion) &&
            (entry.maxAppVersion == null || CURRENT_APP_VERSION <= entry.maxAppVersion)
""",
    """        val minAppVersion = entry.minAppVersion
        val maxAppVersion = entry.maxAppVersion
        val compatible = (minAppVersion == null || CURRENT_APP_VERSION >= minAppVersion) &&
            (maxAppVersion == null || CURRENT_APP_VERSION <= maxAppVersion)
""",
)

replace_exact(
    "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt",
    "        urlField = EditText(this).apply { setSingleLine(true); text = initialUrl; hint = \"URL HTTPS thuộc nguồn\" }",
    "        urlField = EditText(this).apply { setSingleLine(true); setText(initialUrl); hint = \"URL HTTPS thuộc nguồn\" }",
)
replace_exact(
    "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt",
    "                override fun onProgressChanged(view: WebView?, newProgress: Int) { progress.progress = newProgress }",
    "                override fun onProgressChanged(view: WebView?, newProgress: Int) { this@SourceDiagnosticBrowserActivity.progress.progress = newProgress }",
)
replace_exact(
    "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt",
    "            urlField.text = url",
    "            urlField.setText(url)",
    expected=2,
)

replace_exact(
    "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
    "            DownloadJobEntity(id, storyId, source, restoredState, completed, total, error, updated)",
    """            DownloadJobEntity(
                id = id,
                storyId = storyId,
                sourceId = source,
                state = restoredState,
                completedChapters = completed,
                totalChapters = total,
                errorMessage = error,
                updatedAt = updated,
            )""",
)

replace_exact(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "import vn.nghetruyen.app.audio.AudioExportScope\n",
    "import vn.nghetruyen.app.audio.AudioExportScope\nimport vn.nghetruyen.app.audio.SceneMusicAnalysisWorker\n",
)

replace_exact(
    "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt",
    "import androidx.compose.foundation.layout.weight\n",
    "",
)
replace_exact(
    "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt",
    "                        onSearchAllSourcesChange = viewModel::setSearchAllSources,\n",
    "                        onSearchAllSourcesChange = viewModel::setSearchAllSources,\n                        onSortModeChange = viewModel::setSearchSortMode,\n",
)

for screen in (
    "LibraryScreen.kt",
    "PersonalScreen.kt",
    "ReaderScreen.kt",
    "StoryDetailScreen.kt",
):
    replace_exact(
        f"app/src/main/java/vn/nghetruyen/app/ui/screens/{screen}",
        "import androidx.compose.foundation.layout.weight\n",
        "",
    )

print("ANDROID_BUILD_FIXES_APPLIED")
