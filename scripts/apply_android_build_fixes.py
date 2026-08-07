#!/usr/bin/env python3
from pathlib import Path
import subprocess


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if new:
        if new in text:
            return
    elif count == 0:
        return
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrence(s), found {count}: {old!r}")
    file.write_text(text.replace(old, new), encoding="utf-8")


def apply_reference_ui_patch() -> None:
    common = Path("app/src/main/java/vn/nghetruyen/app/ui/components/Common.kt")
    explore = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt")
    if (
        "fun ReferenceActionButton(" in common.read_text(encoding="utf-8")
        and "ReferenceActionButton(" in explore.read_text(encoding="utf-8")
    ):
        print("REFERENCE_UI_V34_ALREADY_APPLIED")
        return

    parts = sorted(Path("scripts").glob("reference_ui_v34.part*.patch"))
    if not parts:
        raise SystemExit("Missing reference UI patch parts")
    patch = Path(".git/reference_ui_v34.patch")
    patch.write_bytes(b"".join(part.read_bytes() for part in parts))
    subprocess.run(["git", "apply", "--check", str(patch)], check=True)
    subprocess.run(["git", "apply", str(patch)], check=True)
    print("REFERENCE_UI_V34_APPLIED")


apply_reference_ui_patch()

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

replace_exact(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt",
    '        ExploreMode.CATEGORY -> state.activeCategory.ifBlank { "DANH SÁCH TRUYỆN" }.uppercase()\n',
    '        ExploreMode.CATEGORY -> state.activeCategory.orEmpty().ifBlank { "DANH SÁCH TRUYỆN" }.uppercase()\n',
)

replace_exact(
    "scripts/validate_release.py",
    '        "CHỈ PHÂN VAI",\n        "CHỈ NHẠC CẢNH",\n',
    '        "PHÂN VAI AI",\n        "NHẠC CẢNH",\n',
)

subprocess.run(["python3", "scripts/apply_reference_ui_polish_v2.py"], check=True)
print("ANDROID_BUILD_FIXES_APPLIED")
