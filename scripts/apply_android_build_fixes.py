#!/usr/bin/env python3
from pathlib import Path
import hashlib
import subprocess


def canonical_source(value: str) -> str:
    """Normalize formatting-only Kotlin differences for idempotency checks."""
    return "".join(value.replace(";", "").split())


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if new:
        if new in text or canonical_source(new) in canonical_source(text):
            return
    elif count == 0:
        return
    if count == 0:
        print(f"PATCH_TARGET_STALE_SKIP={path}")
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
replace_exact(
    "scripts/validate_release.py",
    '        "TẢI KHOẢNG",\n',
    '        "CHỌN PHẠM VI TẢI",\n        "CHỌN NHIỀU CHƯƠNG",\n        "TẢI TOÀN BỘ TRUYỆN",\n',
)

personal_text = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt").read_text(encoding="utf-8")
if "NAVIGATION_AUDIT_V3_PERSONAL" not in personal_text:
    subprocess.run(["python3", "scripts/finalize_reference_ui_polish_v2.py"], check=True)
    subprocess.run(["python3", "scripts/apply_navigation_audit_v3.py"], check=True)
else:
    print("REFERENCE_UI_V2_V3_ALREADY_PERSISTED")



vm_path = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
vm_text = vm_path.read_text(encoding="utf-8")
old_library_story = '''                    destination = Destination.Story,
                    rootTab = RootTab.LIBRARY,
                    storyDetail = StoryDetail(
'''
new_library_story = '''                    destination = Destination.Story,
                    rootTab = RootTab.LIBRARY,
                    storyDetailTab = "intro",
                    storyAdvancedOptionsRequested = false,
                    storyDetail = StoryDetail(
'''
if old_library_story in vm_text:
    vm_text = vm_text.replace(old_library_story, new_library_story)
    vm_path.write_text(vm_text, encoding="utf-8")
    print("REFERENCE_PARITY_V4_LIBRARY_STORY_STATE_APPLIED")

v4_parts = sorted(Path("scripts").glob("reference_parity_v4.c??"))
if len(v4_parts) != 9:
    raise SystemExit(f"Expected 9 verified parity v4 chunks, found {len(v4_parts)}")
v4_text = "".join(part.read_text(encoding="utf-8").strip() for part in v4_parts)
expected_sha = "15b637828d31a540485daf3a08dee9b1ae371e703e2980ef9e4e7e3e180a4f69"
actual_sha = hashlib.sha256(v4_text.encode("ascii")).hexdigest()
if actual_sha != expected_sha:
    raise SystemExit(f"Parity v4 payload SHA mismatch: {actual_sha}")
print("REFERENCE_PARITY_V4_PAYLOAD_OK")
v4_payload = Path(".git/reference_parity_v4.py.gz.b64")
v4_payload.write_text(v4_text, encoding="ascii")
v4_script = Path(".git/apply_reference_parity_v4.py")
subprocess.run(["bash", "-lc", f"base64 -d {v4_payload} | gzip -d > {v4_script}"], check=True)
if v4_script.stat().st_size != 66258:
    raise SystemExit(f"Parity v4 script size mismatch: {v4_script.stat().st_size}")
subprocess.run(["python3", str(v4_script)], check=True)

print("ANDROID_BUILD_FIXES_APPLIED")
