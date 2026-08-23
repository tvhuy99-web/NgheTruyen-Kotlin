from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


matcher = "app/src/main/java/vn/nghetruyen/app/freesound/Mode3LibraryAssetMatcher.kt"
replace_once(
    matcher,
    '''    private data class LocalText(\n        val fingerprint: Mode3OpenDescriptionVector.Fingerprint,\n    ) {\n        val isPresent: Boolean get() = fingerprint.hasContent\n    }''',
    '''    private data class LocalText(\n        val text: String,\n        val fingerprint: Mode3OpenDescriptionVector.Fingerprint,\n    ) {\n        val isPresent: Boolean get() = text.isNotBlank()\n    }''',
    "LocalText stores natural text",
)
replace_once(
    matcher,
    '''    fun initialize(context: Context) {\n        appContext = context.applicationContext\n    }''',
    '''    fun initialize(context: Context) {\n        appContext = context.applicationContext\n        Mode3E5SemanticEngine.initialize(context.applicationContext)\n    }\n\n    fun prewarmSemanticIndex(tracks: List<SceneMusicTrackEntity>) {\n        val passages = tracks.asSequence()\n            .filter { it.enabled }\n            .map { indexTrackCached(it).first.sections }\n            .flatMap(Sections::semanticPassages)\n            .toList()\n        Mode3E5SemanticEngine.requestPrewarm(passages)\n    }''',
    "matcher initialize E5",
)
replace_once(
    matcher,
    '''        val ranked = eligible.asSequence()\n            .mapNotNull { indexed -> score(profile, indexed, indexed.track, nowMillis) }''',
    '''        val semanticPassages = eligible.asSequence()\n            .flatMap { it.sections.semanticPassages() }\n            .distinct()\n            .toList()\n        val useE5 = semanticPassages.isNotEmpty() && Mode3E5SemanticEngine.allPassagesCached(semanticPassages)\n        if (!useE5 && semanticPassages.isNotEmpty()) Mode3E5SemanticEngine.requestPrewarm(semanticPassages)\n\n        val ranked = eligible.asSequence()\n            .mapNotNull { indexed -> score(profile, indexed, indexed.track, nowMillis, useE5) }''',
    "evaluation backend selection",
)
replace_once(
    matcher,
    '''        return score(profile, indexTrackCached(track).first, track, nowMillis)?.takeIf(Match::accepted)''',
    '''        val indexed = indexTrackCached(track).first\n        val passages = indexed.sections.semanticPassages().toList()\n        val useE5 = passages.isNotEmpty() && Mode3E5SemanticEngine.allPassagesCached(passages)\n        if (!useE5) Mode3E5SemanticEngine.requestPrewarm(passages)\n        return score(profile, indexed, track, nowMillis, useE5)?.takeIf(Match::accepted)''',
    "strongMatch backend selection",
)
replace_once(
    matcher,
    '''        val fit = (\n            commonSemanticFit(\n                kind = need.kind,\n                lexicalCoverage = lexical,\n                coreCoverage = coreCoverage,\n                eventCoverage = eventCoverage,\n                sourceCoverage = sourceCoverage,\n                hasSourceRequirement = required.any(AUDIBLE_SOURCE_CONCEPTS::contains),\n                contextScore = conceptContext,\n                hasStructuredContext = profile.hintAware && required.isNotEmpty(),\n            ) -\n                titleIdentityConflict * REMOTE_TITLE_IDENTITY_PENALTY -\n                metadataConflict * REMOTE_METADATA_CONFLICT_PENALTY\n        ).coerceIn(0.0, 1.0)\n\n        val qualified = lexicalQualified &&\n            titleIdentityConflict < HARD_REMOTE_TITLE_IDENTITY_CONFLICT &&\n            fit >= minimumSelectionFit(need.kind)''',
    '''        val legacyFit = (\n            commonSemanticFit(\n                kind = need.kind,\n                lexicalCoverage = lexical,\n                coreCoverage = coreCoverage,\n                eventCoverage = eventCoverage,\n                sourceCoverage = sourceCoverage,\n                hasSourceRequirement = required.any(AUDIBLE_SOURCE_CONCEPTS::contains),\n                contextScore = conceptContext,\n                hasStructuredContext = profile.hintAware && required.isNotEmpty(),\n            ) -\n                titleIdentityConflict * REMOTE_TITLE_IDENTITY_PENALTY -\n                metadataConflict * REMOTE_METADATA_CONFLICT_PENALTY\n        ).coerceIn(0.0, 1.0)\n        val semanticNeed = profile.hints.joinToString(" ") { "${it.shadeText} ${it.useText}" }.trim()\n            .ifBlank { need.query }\n        val e5Fit = Mode3E5SemanticEngine.similarityOrNull(\n            queryText = semanticNeed,\n            passageText = "$titleText $metadataText",\n            allowPassageInference = true,\n        )\n        val fit = if (e5Fit != null) {\n            (legacyFit * REMOTE_LEGACY_WEIGHT + e5Fit * REMOTE_E5_WEIGHT).coerceIn(0.0, 1.0)\n        } else legacyFit\n        val semanticQualified = e5Fit != null && e5Fit >= REMOTE_E5_MIN_FIT\n        val qualified = (lexicalQualified || semanticQualified) &&\n            titleIdentityConflict < HARD_REMOTE_TITLE_IDENTITY_CONFLICT &&\n            fit >= minimumSelectionFit(need.kind)''',
    "remote E5 rerank",
)
replace_once(
    matcher,
    '''    private fun score(\n        profile: NeedProfile,\n        indexed: IndexedTrack,\n        currentTrack: SceneMusicTrackEntity,\n        nowMillis: Long,\n    ): Match? {''',
    '''    private fun score(\n        profile: NeedProfile,\n        indexed: IndexedTrack,\n        currentTrack: SceneMusicTrackEntity,\n        nowMillis: Long,\n        useE5: Boolean,\n    ): Match? {''',
    "score backend argument",
)
for old, new, label in [
    ("localSimilarity(hint.use, indexed.sections.use)", "localSimilarity(hint.use, indexed.sections.use, useE5)", "use direct"),
    ("localSimilarity(hint.shade, indexed.sections.use)", "localSimilarity(hint.shade, indexed.sections.use, useE5)", "shade to use"),
    ("localSimilarity(hint.use, indexed.sections.shade)", "localSimilarity(hint.use, indexed.sections.shade, useE5)", "use to shade"),
    ("localSimilarity(hint.positive, indexed.sections.positive)", "localSimilarity(hint.positive, indexed.sections.positive, useE5)", "positive direct"),
    ("localSimilarity(expectedShade, indexed.sections.shade)", "localSimilarity(expectedShade, indexed.sections.shade, useE5)", "expected shade"),
    ("localSimilarity(hint.positive, indexed.sections.avoid)", "localSimilarity(hint.positive, indexed.sections.avoid, useE5)", "candidate avoid positive"),
    ("localSimilarity(hint.use, indexed.sections.avoid)", "localSimilarity(hint.use, indexed.sections.avoid, useE5)", "candidate avoid use"),
    ("localSimilarity(hint.shade, indexed.sections.avoid)", "localSimilarity(hint.shade, indexed.sections.avoid, useE5)", "candidate avoid shade"),
]:
    text = Path(matcher).read_text(encoding="utf-8")
    if old in text:
        Path(matcher).write_text(text.replace(old, new), encoding="utf-8")
    elif new not in text:
        raise SystemExit(f"{label}: missing expression")
replace_once(
    matcher,
    '''                    localSimilarity(indexed.sections.positive, hint.avoid),\n                    max(\n                        localSimilarity(indexed.sections.use, hint.avoid),\n                        localSimilarity(indexed.sections.shade, hint.avoid) * 0.80,''',
    '''                    localSimilarity(hint.avoid, indexed.sections.positive, useE5),\n                    max(\n                        localSimilarity(hint.avoid, indexed.sections.use, useE5),\n                        localSimilarity(hint.avoid, indexed.sections.shade, useE5) * 0.80,''',
    "avoid query direction",
)
replace_once(
    matcher,
    '''            val allText = localNormalize(value)\n            val all = localText(allText)''',
    '''            val allText = semanticText(value)\n            val all = localText(allText)''',
    "raw section keeps accents",
)
replace_once(
    matcher,
    '''            return localNormalize(value.substring(contentStart, end))''',
    '''            return semanticText(value.substring(contentStart, end))''',
    "section slice keeps accents",
)
replace_once(
    matcher,
    '''        val allText = localNormalize(value)''',
    '''        val allText = semanticText(value)''',
    "structured all keeps accents",
)
replace_once(
    matcher,
    '''            val use = localNormalize(value)''',
    '''            val use = semanticText(value)''',
    "raw hint keeps accents",
)
replace_once(
    matcher,
    '''            return localNormalize(value.substring(contentStart, end))''',
    '''            return semanticText(value.substring(contentStart, end))''',
    "hint slice keeps accents",
)
replace_once(
    matcher,
    '''    private fun localSimilarity(first: LocalText, second: LocalText): Double =\n        Mode3OpenDescriptionVector.cosine(first.fingerprint, second.fingerprint)\n\n    private fun localText(value: String): LocalText =\n        LocalText(Mode3OpenDescriptionVector.build(value))''',
    '''    private fun localSimilarity(first: LocalText, second: LocalText, useE5: Boolean): Double {\n        if (!first.isPresent || !second.isPresent) return 0.0\n        if (useE5) {\n            Mode3E5SemanticEngine.similarityOrNull(first.text, second.text)?.let { return it }\n        }\n        return Mode3OpenDescriptionVector.cosine(first.fingerprint, second.fingerprint)\n    }\n\n    private fun localText(value: String): LocalText {\n        val text = semanticText(value)\n        return LocalText(text, Mode3OpenDescriptionVector.build(text))\n    }\n\n    private fun semanticText(value: String): String = value.replace(Regex("\\\\s+"), " ").trim()''',
    "E5 local similarity",
)
replace_once(
    matcher,
    '''    private fun firstMarker(lower: String, vararg markers: String): Int = markers''',
    '''    private fun Sections.semanticPassages(): Sequence<String> = sequenceOf(\n        use.text,\n        shade.text,\n        positive.text,\n        avoid.text,\n    ).filter(String::isNotBlank)\n\n    private fun firstMarker(lower: String, vararg markers: String): Int = markers''',
    "section passage list",
)
replace_once(
    matcher,
    '''                "localSemanticPolicy" to "DESCRIPTION_VECTOR_OPEN_VOCABULARY",''',
    '''                "localSemanticPolicy" to Mode3E5SemanticEngine.backendName(),''',
    "dynamic semantic diagnostic",
)
replace_once(
    matcher,
    '''    private const val REMOTE_METADATA_CONFLICT_PENALTY = 0.12''',
    '''    private const val REMOTE_METADATA_CONFLICT_PENALTY = 0.12\n    private const val REMOTE_LEGACY_WEIGHT = 0.38\n    private const val REMOTE_E5_WEIGHT = 0.62\n    private const val REMOTE_E5_MIN_FIT = 0.50''',
    "remote E5 weights",
)
replace_once(
    matcher,
    '''    private val EMPTY_LOCAL_TEXT = LocalText(Mode3OpenDescriptionVector.build(""))''',
    '''    private val EMPTY_LOCAL_TEXT = LocalText("", Mode3OpenDescriptionVector.build(""))''',
    "empty local text",
)


main = "app/src/main/java/vn/nghetruyen/app/MainActivity.kt"
replace_once(
    main,
    '''import vn.nghetruyen.app.following.FollowingUpdateWorker''',
    '''import vn.nghetruyen.app.following.FollowingUpdateWorker\nimport vn.nghetruyen.app.freesound.Mode3E5SemanticEngine\nimport vn.nghetruyen.app.freesound.Mode3LibraryAssetMatcher''',
    "MainActivity semantic imports",
)
replace_once(
    main,
    '''class MainActivity : ComponentActivity() {\n    private val viewModel: AppViewModel by viewModels()''',
    '''class MainActivity : ComponentActivity() {\n    private val viewModel: AppViewModel by viewModels()\n    private var semanticModelPromptShown = false''',
    "MainActivity prompt flag",
)
replace_once(
    main,
    '''        installExtensionHostKernel(viewModel)\n        handleFollowingIntent(intent)\n        setContent {''',
    '''        installExtensionHostKernel(viewModel)\n        handleFollowingIntent(intent)\n        prepareSemanticModel()\n        setContent {''',
    "startup semantic preparation",
)
replace_once(
    main,
    '''    private fun handleBackupRestoreSelection(uri: Uri) {''',
    '''    private fun prepareSemanticModel() {\n        val status = Mode3E5SemanticEngine.status()\n        if (status.installed) {\n            lifecycleScope.launch { prewarmSemanticLibrary() }\n            return\n        }\n        if (semanticModelPromptShown || isFinishing) return\n        semanticModelPromptShown = true\n        AlertDialog.Builder(this)\n            .setTitle("TẢI MÔ HÌNH TÌM KIẾM NGỮ NGHĨA")\n            .setMessage(\n                "Multilingual E5 Small INT8, khoảng 124 MB. " +\n                    "Mô hình được tải một lần và lưu riêng trong dữ liệu ứng dụng; " +\n                    "cập nhật APK sau này không cần tải lại nếu phiên bản mô hình không đổi.",\n            )\n            .setPositiveButton("TẢI XUỐNG") { _, _ -> downloadSemanticModel() }\n            .setNegativeButton("ĐỂ SAU", null)\n            .show()\n    }\n\n    private fun downloadSemanticModel() {\n        val dialog = AlertDialog.Builder(this)\n            .setTitle("ĐANG TẢI MÔ HÌNH NGỮ NGHĨA")\n            .setMessage("Đang chuẩn bị tải…")\n            .setCancelable(false)\n            .create()\n        dialog.show()\n        lifecycleScope.launch {\n            val result = Mode3E5SemanticEngine.install { progress ->\n                val downloadedMb = progress.downloadedBytes / (1024.0 * 1024.0)\n                val totalMb = progress.totalBytes / (1024.0 * 1024.0)\n                val percent = (progress.fraction * 100.0).toInt().coerceIn(0, 100)\n                runOnUiThread {\n                    if (dialog.isShowing) {\n                        dialog.setMessage(\n                            "${progress.currentFile}\\n$percent% • %.1f / %.1f MB".format(\n                                Locale.ROOT, downloadedMb, totalMb,\n                            ),\n                        )\n                    }\n                }\n            }\n            dialog.dismiss()\n            result.onSuccess {\n                AlertDialog.Builder(this@MainActivity)\n                    .setTitle("ĐÃ CÀI MÔ HÌNH NGỮ NGHĨA")\n                    .setMessage(\n                        "Multilingual E5 Small đã sẵn sàng. Ứng dụng sẽ lập chỉ mục mô tả âm thanh ở nền; " +\n                            "những lần cập nhật APK sau không cần tải lại mô hình này.",\n                    )\n                    .setPositiveButton("OK", null)\n                    .show()\n                prewarmSemanticLibrary()\n            }.onFailure { error ->\n                AlertDialog.Builder(this@MainActivity)\n                    .setTitle("KHÔNG TẢI ĐƯỢC MÔ HÌNH")\n                    .setMessage(error.message ?: "Lỗi không xác định. Matcher nhẹ vẫn tiếp tục hoạt động.")\n                    .setPositiveButton("THỬ LẠI") { _, _ -> downloadSemanticModel() }\n                    .setNegativeButton("ĐỂ SAU", null)\n                    .show()\n            }\n        }\n    }\n\n    private suspend fun prewarmSemanticLibrary() {\n        val container = (application as NgheTruyenApplication).container\n        val tracks = container.database.sceneMusicTrackDao().listAll()\n        Mode3LibraryAssetMatcher.prewarmSemanticIndex(tracks)\n    }\n\n    private fun handleBackupRestoreSelection(uri: Uri) {''',
    "MainActivity semantic model workflow",
)


settings = "app/src/main/java/vn/nghetruyen/app/ui/components/FreesoundSettingsCard.kt"
replace_once(
    settings,
    '''import vn.nghetruyen.app.freesound.FreesoundCredentialStore''',
    '''import vn.nghetruyen.app.freesound.FreesoundCredentialStore\nimport vn.nghetruyen.app.freesound.Mode3E5SemanticEngine\nimport vn.nghetruyen.app.freesound.Mode3LibraryAssetMatcher''',
    "settings semantic imports",
)
replace_once(
    settings,
    '''    var statusMessage by remember { mutableStateOf<String?>(null) }''',
    '''    var statusMessage by remember { mutableStateOf<String?>(null) }\n    var semanticStatus by remember { mutableStateOf(Mode3E5SemanticEngine.status()) }\n    var semanticBusy by remember { mutableStateOf(false) }\n    var semanticProgress by remember { mutableStateOf<String?>(null) }''',
    "settings semantic state",
)
replace_once(
    settings,
    '''            statusMessage?.let { message ->\n                Text(message, style = MaterialTheme.typography.bodySmall)\n            }''',
    '''            statusMessage?.let { message ->\n                Text(message, style = MaterialTheme.typography.bodySmall)\n            }\n\n            Text("MÔ HÌNH TÌM KIẾM NGỮ NGHĨA", fontWeight = FontWeight.SemiBold)\n            Text(\n                when {\n                    semanticBusy -> "Trạng thái: Đang xử lý"\n                    semanticStatus.ready -> "Trạng thái: Multilingual E5 Small INT8 đã sẵn sàng"\n                    semanticStatus.installed -> "Trạng thái: Đã tải, đang khởi tạo"\n                    else -> "Trạng thái: Chưa tải • khoảng 124 MB"\n                },\n                style = MaterialTheme.typography.bodyMedium,\n            )\n            Text(\n                "Mô hình được lưu riêng khỏi APK và dùng lại qua các lần cập nhật ứng dụng. " +\n                    "Nếu chưa có mô hình, Mode 3 tự dùng matcher nhẹ dự phòng.",\n                style = MaterialTheme.typography.bodySmall,\n            )\n            semanticProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }\n            if (!semanticStatus.installed) {\n                Button(\n                    onClick = {\n                        semanticBusy = true\n                        semanticProgress = "Đang bắt đầu tải…"\n                        scope.launch {\n                            val result = Mode3E5SemanticEngine.install { progress ->\n                                val percent = (progress.fraction * 100.0).toInt().coerceIn(0, 100)\n                                semanticProgress = "$percent% • ${progress.currentFile}"\n                            }\n                            semanticBusy = false\n                            semanticStatus = Mode3E5SemanticEngine.status()\n                            result.onSuccess {\n                                semanticProgress = "Đã tải xong. Đang lập chỉ mục mô tả âm thanh ở nền."\n                                val tracks = application.container.database.sceneMusicTrackDao().listAll()\n                                Mode3LibraryAssetMatcher.prewarmSemanticIndex(tracks)\n                            }.onFailure { semanticProgress = it.message ?: "Không tải được mô hình." }\n                        }\n                    },\n                    enabled = !semanticBusy,\n                    modifier = Modifier.fillMaxWidth(),\n                ) { Text("TẢI MÔ HÌNH NGỮ NGHĨA") }\n            } else {\n                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    Button(\n                        onClick = {\n                            scope.launch {\n                                semanticBusy = true\n                                semanticProgress = "Đang lập chỉ mục các mô tả còn thiếu…"\n                                val tracks = application.container.database.sceneMusicTrackDao().listAll()\n                                Mode3LibraryAssetMatcher.prewarmSemanticIndex(tracks)\n                                semanticBusy = false\n                                semanticStatus = Mode3E5SemanticEngine.status()\n                                semanticProgress = "Đã yêu cầu lập chỉ mục ở nền."\n                            }\n                        },\n                        enabled = !semanticBusy,\n                        modifier = Modifier.weight(1f),\n                    ) { Text("LẬP CHỈ MỤC") }\n                    TextButton(\n                        onClick = {\n                            semanticBusy = true\n                            val deleted = Mode3E5SemanticEngine.deleteModel()\n                            semanticBusy = false\n                            semanticStatus = Mode3E5SemanticEngine.status()\n                            semanticProgress = if (deleted) "Đã xóa mô hình; âm thanh trong thư viện không bị xóa." else "Không xóa được mô hình."\n                        },\n                        enabled = !semanticBusy,\n                        modifier = Modifier.weight(1f),\n                    ) { Text("XÓA MÔ HÌNH") }\n                }\n            }''',
    "settings semantic controls",
)

print("Mode3 E5 integration patch prepared")
