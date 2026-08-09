#!/usr/bin/env python3
from pathlib import Path


def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

p = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt')
s = p.read_text()
s = rep(s, 'import androidx.compose.ui.unit.sp\n', 'import androidx.compose.ui.unit.sp\nimport androidx.work.WorkInfo\nimport androidx.work.WorkManager\n', 'work imports')
s = rep(s, 'import kotlin.math.pow\n', 'import kotlin.math.pow\nimport java.util.UUID\n', 'uuid import')

# State near music dialog state.
anchor = '    var musicBulkErrors by remember { mutableStateOf<List<String>>(emptyList()) }\n'
state = '''    var musicBulkErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var showMusicNormalizationProgress by remember { mutableStateOf(false) }
    var musicNormalizationWorkIds by remember { mutableStateOf<List<UUID>>(emptyList()) }
    var musicNormalizationDone by remember { mutableIntStateOf(0) }
    var musicNormalizationFailed by remember { mutableIntStateOf(0) }
    var musicNormalizationCancelled by remember { mutableIntStateOf(0) }
    var musicNormalizationTarget by remember { mutableStateOf(-24f) }
    var musicNormalizationRunToken by remember { mutableIntStateOf(0) }
'''
s = rep(s, anchor, state, 'normalization state')

old_button = '''                ReaderMenuButton("CHUẨN HÓA TOÀN BỘ KHO NHẠC") {
                    state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id) }
                    onMessage("Đã đưa toàn bộ kho nhạc vào hàng đợi chuẩn hóa.")
                }
'''
new_button = '''                ReaderMenuButton("CHUẨN HÓA TOÀN BỘ KHO NHẠC") {
                    val tracks = state.sceneMusicTracks
                    if (tracks.isEmpty()) {
                        onMessage("Kho nhạc đang trống.")
                    } else {
                        musicNormalizationTarget = musicTargetLufs
                        musicNormalizationDone = 0
                        musicNormalizationFailed = 0
                        musicNormalizationCancelled = 0
                        musicNormalizationRunToken += 1
                        val runToken = musicNormalizationRunToken
                        val workIds = tracks.map { track ->
                            SceneMusicAnalysisWorker.enqueue(context, track.id, musicTargetLufs)
                        }
                        musicNormalizationWorkIds = workIds
                        showMusicNormalizationProgress = true
                        scope.launch {
                            val workManager = WorkManager.getInstance(context.applicationContext)
                            while (showMusicNormalizationProgress && runToken == musicNormalizationRunToken) {
                                val infos = withContext(Dispatchers.IO) {
                                    workIds.mapNotNull { id -> runCatching { workManager.getWorkInfoById(id).get() }.getOrNull() }
                                }
                                musicNormalizationDone = infos.count { it.state == WorkInfo.State.SUCCEEDED }
                                musicNormalizationFailed = infos.count { it.state == WorkInfo.State.FAILED }
                                musicNormalizationCancelled = infos.count { it.state == WorkInfo.State.CANCELLED }
                                if (infos.size == workIds.size && infos.all { it.state.isFinished }) break
                                delay(300)
                            }
                        }
                    }
                }
'''
s = rep(s, old_button, new_button, 'normalize all uses draft target')

# Saving settings can recalc in the background but must explicitly pass the saved draft target.
s = s.replace(
    '                    state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id) }\n',
    '                    state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id, musicTargetLufs) }\n',
    1,
)

# Insert progress dialog before library dialog.
marker = '    if (showMusicLibrary) {\n'
progress = '''    if (showMusicNormalizationProgress) {
        val total = musicNormalizationWorkIds.size
        val finished = musicNormalizationDone + musicNormalizationFailed + musicNormalizationCancelled
        val running = (total - finished).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = {},
            title = { Text("CHUẨN HÓA KHO NHẠC") },
            text = {
                Column {
                    Text("Mục tiêu: %.0f LUFS".format(musicNormalizationTarget), fontWeight = FontWeight.SemiBold)
                    Text("Hoàn tất: $finished / $total")
                    Text("Thành công: $musicNormalizationDone")
                    if (musicNormalizationFailed > 0) Text("Lỗi: $musicNormalizationFailed")
                    if (musicNormalizationCancelled > 0) Text("Đã hủy: $musicNormalizationCancelled")
                    if (running > 0) Text("Đang xử lý: $running")
                    Text(
                        "Các bài đã có loudness và peak chỉ được tính lại gain, không giải mã lại.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                if (finished >= total && total > 0) {
                    TextButton(onClick = {
                        showMusicNormalizationProgress = false
                        musicNormalizationWorkIds = emptyList()
                        onMessage("Chuẩn hóa xong: $musicNormalizationDone thành công, $musicNormalizationFailed lỗi.")
                    }) { Text("ĐÓNG") }
                }
            },
            dismissButton = {
                if (finished < total) {
                    TextButton(onClick = {
                        musicNormalizationRunToken += 1
                        musicNormalizationWorkIds.forEach { SceneMusicAnalysisWorker.cancel(context, it) }
                        showMusicNormalizationProgress = false
                        onMessage("Đã hủy hàng đợi chuẩn hóa nhạc.")
                    }) { Text("HỦY") }
                }
            },
        )
    }

'''
s = rep(s, marker, progress + marker, 'normalization progress dialog')
p.write_text(s)

# Gate phase-4 semantics.
Path('scripts/check_music_normalization_flow_parity.py').write_text('''#!/usr/bin/env python3
from pathlib import Path
worker = Path("app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt").read_text()
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
for token in ["KEY_TARGET_LUFS", "targetLufs: Float? = null", "return request.id", "fun cancel(context: Context, workId: UUID)"]:
    if token not in worker: raise SystemExit("MUSIC_NORMALIZE_FLOW worker missing: " + token)
for token in ["SceneMusicAnalysisWorker.enqueue(context, track.id, musicTargetLufs)", "CHUẨN HÓA KHO NHẠC", "WorkInfo.State.SUCCEEDED", "WorkInfo.State.FAILED", "WorkInfo.State.CANCELLED", "getWorkInfoById(id).get()", "SceneMusicAnalysisWorker.cancel(context, it)", "Mục tiêu: %.0f LUFS", "không giải mã lại"]:
    if token not in reader: raise SystemExit("MUSIC_NORMALIZE_FLOW reader missing: " + token)
print("MUSIC_NORMALIZATION_FLOW_PARITY=PASS")
''')

p = Path('scripts/m0_gate.sh')
s = p.read_text()
marker = '  scripts/check_music_playback_parity.py\n'
if 'check_music_normalization_flow_parity.py' not in s:
    s = rep(s, marker, marker + '  scripts/check_music_normalization_flow_parity.py\n', 'm0 phase4 gate')
p.write_text(s)
