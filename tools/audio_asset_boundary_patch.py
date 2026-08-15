from pathlib import Path

changes = {
    Path("app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"): [
        (
            "import vn.nghetruyen.app.core.common.AppResult\n",
            "import vn.nghetruyen.app.core.common.AppResult\nimport vn.nghetruyen.app.audio.AudioAssetClassifier\nimport vn.nghetruyen.app.audio.AudioAssetKind\n",
        ),
        (
            "        val tracks = if (music) library.listEnabledSceneMusicTracks() else emptyList()\n",
            "        val tracks = if (music) {\n            library.listEnabledSceneMusicTracks()\n                .filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }\n        } else emptyList()\n",
        ),
    ],
    Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"): [
        (
            "import vn.nghetruyen.app.audio.Pcm16WaveConverter\n",
            "import vn.nghetruyen.app.audio.Pcm16WaveConverter\nimport vn.nghetruyen.app.audio.AudioAssetClassifier\nimport vn.nghetruyen.app.audio.AudioAssetKind\n",
        ),
        (
            "            container.libraryRepository.listEnabledSceneMusicTracks()\n",
            "            container.libraryRepository.listEnabledSceneMusicTracks()\n                .filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }\n",
        ),
    ],
}

for path, replacements in changes.items():
    text = path.read_text(encoding="utf-8")
    for old, new in replacements:
        if new in text:
            continue
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: expected exactly one anchor, got {count}: {old!r}")
        text = text.replace(old, new)
    path.write_text(text, encoding="utf-8")
