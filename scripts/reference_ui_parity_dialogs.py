#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# AppViewModel: real actions behind the two destructive settings, only invoked
# after the reference-style confirmation dialogs in PersonalScreen.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
t = read(path)
t = replace_once(
    t,
    'import android.app.Application\n',
    'import android.app.ActivityManager\nimport android.app.Application\n',
    "activity manager import",
)
marker = '    fun removeOfflineStory(storyId: String) {\n'
if marker not in t:
    raise SystemExit("missing removeOfflineStory")
insert = '''    fun clearAllDownloadedStories() {
        val storyIds = state.value.downloadedStories
            .filter { it.sourceId != "offline" }
            .map { it.id }
            .distinct()
        if (storyIds.isEmpty()) {
            showMessage("Không có truyện đã tải để xóa.")
            return
        }
        storyIds.forEach(::removeOfflineStory)
        showMessage("Đã bắt đầu xóa ${storyIds.size} truyện đã tải. Tiến độ đọc, lịch sử và dấu trang được giữ lại.")
    }

    fun factoryResetApplication() {
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_STOP)
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        if (manager?.clearApplicationUserData() != true) {
            showMessage("Không thể đặt lại dữ liệu ứng dụng trên thiết bị này.")
        }
    }

'''
t = t.replace(marker, insert + marker, 1)
write(path, t)

# ---------------------------------------------------------------------------
# App wiring.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"
t = read(path)
t = replace_once(
    t,
    '                        onRestoreBackup = onRestoreBackup,\n',
    '                        onRestoreBackup = onRestoreBackup,\n'
    '                        onClearDownloadedStories = viewModel::clearAllDownloadedStories,\n'
    '                        onFactoryResetApplication = viewModel::factoryResetApplication,\n',
    "settings destructive wiring",
)
write(path, t)

# ---------------------------------------------------------------------------
# PersonalScreen: Settings becomes a real dialog. Other settings and destructive
# actions become nested dialogs/confirmations instead of fake navigation pages.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
t = read(path)
t = replace_once(t, 'import androidx.compose.foundation.layout.fillMaxWidth\n', 'import androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.heightIn\n', "heightIn import")
t = replace_once(t, 'import androidx.compose.material3.Button\n', 'import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Button\n', "alert import")
t = replace_once(t, 'import androidx.compose.material3.Text\n', 'import androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\n', "textbutton import")
t = replace_once(
    t,
    '    onRestoreBackup: () -> Unit,\n',
    '    onRestoreBackup: () -> Unit,\n'
    '    onClearDownloadedStories: () -> Unit,\n'
    '    onFactoryResetApplication: () -> Unit,\n',
    "personal destructive callbacks",
)
t = replace_once(
    t,
    '    var personalPage by remember { mutableStateOf("home") }\n',
    '''    var personalPage by remember { mutableStateOf("home") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showOtherSettingsDialog by remember { mutableStateOf(false) }
    var showBackupLogDialog by remember { mutableStateOf(false) }
    var showClearDownloadsDialog by remember { mutableStateOf(false) }
    var showFactoryResetFirst by remember { mutableStateOf(false) }
    var showFactoryResetFinal by remember { mutableStateOf(false) }
''',
    "personal dialog state",
)
t = replace_once(
    t,
    '''    BackHandler(enabled = personalPage != "home") {
        personalPage = parentPage(personalPage)
    }
''',
    '''    fun returnToSettings() {
        personalPage = "home"
        showSettingsDialog = true
    }

    BackHandler(enabled = personalPage != "home") {
        if (personalPage.startsWith("settings_")) returnToSettings()
        else personalPage = parentPage(personalPage)
    }
''',
    "settings back behavior",
)
t = replace_once(
    t,
    '''            onSelect = { personalPage = it },
        )
        "settings_home" -> ReferenceSettingsHomePage(
            diagnosticsMode = state.diagnosticsMode,
            onDiagnosticsModeChange = onDiagnosticsModeChange,
            onBack = { personalPage = "home" },
            onSelect = { personalPage = it },
            onExportBackup = onExportBackup,
            onRestoreBackup = onRestoreBackup,
        )
''',
    '''            onSelect = { target ->
                if (target == "settings_home") showSettingsDialog = true
                else personalPage = target
            },
        )
''',
    "home opens settings dialog",
)
# Every settings subpage now returns to the dialog, not to a fake settings page.
t = t.replace('{ personalPage = "settings_home" }', '{ returnToSettings() }')

# Add the dialog stack immediately after the main navigation when block.
marker = '''    }
}

@Composable
private fun ReferenceSettingsHomePage(
'''
if marker not in t:
    raise SystemExit("missing PersonalScreen closing marker")
dialogs = '''    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("CÀI ĐẶT ỨNG DỤNG") },
            text = {
                ReferenceSettingsHomePage(
                    diagnosticsMode = state.diagnosticsMode,
                    onDiagnosticsModeChange = onDiagnosticsModeChange,
                    onSelect = { target ->
                        showSettingsDialog = false
                        when (target) {
                            "settings_other" -> showOtherSettingsDialog = true
                            "settings_backup_log" -> showBackupLogDialog = true
                            "settings_clear_downloads" -> showClearDownloadsDialog = true
                            "settings_factory_reset" -> showFactoryResetFirst = true
                            else -> personalPage = target
                        }
                    },
                    onExportBackup = onExportBackup,
                    onRestoreBackup = onRestoreBackup,
                )
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showOtherSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showOtherSettingsDialog = false; showSettingsDialog = true },
            title = { Text("CÀI ĐẶT KHÁC") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Đọc liên tục khi có cuộc gọi hoặc tin nhắn", Modifier.weight(1f))
                        Switch(
                            checked = state.audioInterruptionMode == AudioInterruptionMode.CONTINUE_DUCKED,
                            onCheckedChange = { enabled ->
                                onInterruptionModeChange(
                                    if (enabled) AudioInterruptionMode.CONTINUE_DUCKED else AudioInterruptionMode.PAUSE,
                                )
                            },
                        )
                    }
                    Text(
                        "Mặc định tắt. Khi bật, ứng dụng cố gắng tiếp tục đọc ở mức âm lượng giảm khi có gián đoạn âm thanh tạm thời.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showOtherSettingsDialog = false; showSettingsDialog = true }) { Text("ĐÓNG") }
            },
        )
    }

    if (showBackupLogDialog) {
        AlertDialog(
            onDismissRequest = { showBackupLogDialog = false; showSettingsDialog = true },
            title = { Text("NHẬT KÝ SAO LƯU VÀ KHÔI PHỤC") },
            text = {
                Text("Bản Kotlin hiện chưa lưu lịch sử sao lưu/khôi phục riêng. Các thao tác sao lưu và khôi phục vẫn dùng bộ quản lý dữ liệu hiện có.")
            },
            confirmButton = {
                TextButton(onClick = { showBackupLogDialog = false; showSettingsDialog = true }) { Text("ĐÓNG") }
            },
        )
    }

    if (showClearDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showClearDownloadsDialog = false; showSettingsDialog = true },
            title = { Text("XÓA TRUYỆN ĐÃ TẢI") },
            text = {
                Text("Xóa toàn bộ nội dung truyện đã tải khỏi thiết bị? Tiến độ đọc, lịch sử và dấu trang vẫn được giữ lại.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDownloadsDialog = false
                    onClearDownloadedStories()
                    showSettingsDialog = true
                }) { Text("XÓA") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDownloadsDialog = false; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }

    if (showFactoryResetFirst) {
        AlertDialog(
            onDismissRequest = { showFactoryResetFirst = false; showSettingsDialog = true },
            title = { Text("ĐẶT LẠI ỨNG DỤNG NHƯ MỚI") },
            text = {
                Text("Thao tác này sẽ xóa toàn bộ dữ liệu và cài đặt của ứng dụng, gồm tiến độ đọc, dấu trang, truyện đã tải, từ điển, cấu hình AI và tiện ích. Bạn có muốn tiếp tục?")
            },
            confirmButton = {
                TextButton(onClick = { showFactoryResetFirst = false; showFactoryResetFinal = true }) { Text("TIẾP TỤC") }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryResetFirst = false; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }

    if (showFactoryResetFinal) {
        AlertDialog(
            onDismissRequest = { showFactoryResetFinal = false; showSettingsDialog = true },
            title = { Text("XÁC NHẬN LẦN CUỐI") },
            text = { Text("Dữ liệu sau khi xóa không thể khôi phục nếu bạn chưa sao lưu. Đặt lại ứng dụng ngay?") },
            confirmButton = {
                TextButton(onClick = {
                    showFactoryResetFinal = false
                    onFactoryResetApplication()
                }) { Text("ĐẶT LẠI NGAY") }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryResetFinal = false; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }
}

@Composable
private fun ReferenceSettingsHomePage(
'''
t = t.replace(marker, dialogs, 1)

# Refactor the settings content so the AlertDialog owns the title/back action.
t = replace_once(t, '    onBack: () -> Unit,\n', '', "remove settings back callback")
t = replace_once(
    t,
    '''    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        ReferenceActionButton(
            text = "QUAY LẠI CÁ NHÂN",
            onClick = onBack,
            normalColor = ReferenceGray,
            accessibilityLabel = "Quay lại cá nhân",
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
        ScreenHeading("CÀI ĐẶT ỨNG DỤNG")
''',
    '''    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
''',
    "settings dialog content shell",
)
write(path, t)

# ---------------------------------------------------------------------------
# Reader action row: keep all three diagnostic/AI actions in their stable slots.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
t = read(path)
t = replace_once(
    t,
    '''                if (!textMode) {
                    ReaderButton(
                        "PHÂN VAI AI",
                        onVoiceCast,
                        Modifier.weight(1f),
                        enabled = !state.aiBusy,
                        normalColor = Color(0xFFAF52DE),
                        accessibilityLabel = "Phân vai giọng đọc bằng AI",
                    )
                }
''',
    '''                ReaderButton(
                    "PHÂN VAI AI",
                    onVoiceCast,
                    Modifier.weight(1f),
                    enabled = !state.aiBusy,
                    normalColor = Color(0xFFAF52DE),
                    accessibilityLabel = "Phân vai giọng đọc bằng AI",
                )
''',
    "reader stable voice cast action",
)
write(path, t)

# ---------------------------------------------------------------------------
# Login browser: adopt the same navigation hierarchy as the diagnostic browser.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt"
t = read(path)
t = replace_once(t, 'import android.graphics.Color\n', 'import android.content.Intent\nimport android.graphics.Color\n', "login intent import")
t = replace_once(t, 'import android.view.ViewGroup\n', 'import android.view.View\nimport android.view.ViewGroup\n', "login view import")
t = replace_once(t, 'import android.widget.Button\n', 'import android.widget.Button\nimport android.widget.EditText\n', "login edittext import")
old = '''        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val done = Button(this).apply {
            text = "LƯU PHIÊN VÀ ĐÓNG"
            setOnClickListener {
                captureSession()
                setResult(RESULT_OK)
                finish()
            }
        }
        val clear = Button(this).apply {
            text = "XÓA PHIÊN"
            setOnClickListener {
                clearSessionCookies()
                status.text = "Đã xóa phiên của nguồn này."
            }
        }
        actions.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

'''
new = '''        val browserOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val otherOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val addressField = EditText(this).apply {
            setSingleLine(true)
            setText(loginUrl)
            hint = "URL HTTPS thuộc nguồn"
        }
        var desktopCompat = false

        fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        fun actionRow(vararg buttons: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        val navigation = actionRow(
            actionButton("QUAY LẠI") { if (webView.canGoBack()) webView.goBack() },
            actionButton("TIẾN TỚI") { if (webView.canGoForward()) webView.goForward() },
            actionButton("TÙY CHỌN") {
                browserOptions.visibility = if (browserOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (browserOptions.visibility == View.GONE) otherOptions.visibility = View.GONE
            },
        )
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addressField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionButton("ĐI TỚI") {
                val target = addressField.text.toString().trim()
                if (isAllowed(target)) webView.loadUrl(target)
                else status.text = "URL phải dùng HTTPS và thuộc miền của nguồn."
            })
        }
        browserOptions.addView(actionButton("LÀM MỚI") { webView.reload() })
        browserOptions.addView(actionButton("TÙY CHỌN KHÁC") {
            otherOptions.visibility = if (otherOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        browserOptions.addView(actionButton("XÓA DỮ LIỆU ĐĂNG NHẬP CỦA TRANG") {
            clearSessionCookies()
            status.text = "Đã xóa dữ liệu đăng nhập của nguồn này."
        })
        browserOptions.addView(actionButton("ĐÓNG TRÌNH DUYỆT") {
            captureSession()
            setResult(RESULT_OK)
            finish()
        })
        otherOptions.addView(actionButton("CHẾ ĐỘ TƯƠNG THÍCH CHROME") {
            desktopCompat = !desktopCompat
            webView.settings.userAgentString = if (desktopCompat) {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            } else {
                WebSettings.getDefaultUserAgent(this@SourceLoginActivity)
            }
            webView.reload()
        })
        otherOptions.addView(actionButton("CHẨN ĐOÁN TRÌNH DUYỆT") {
            val target = webView.url?.takeIf(::isAllowed) ?: loginUrl
            startActivity(Intent(this, SourceDiagnosticBrowserActivity::class.java).apply {
                putExtra(SourceDiagnosticBrowserActivity.EXTRA_SOURCE_ID, sourceId)
                putExtra(SourceDiagnosticBrowserActivity.EXTRA_INITIAL_URL, target)
                putExtra(SourceDiagnosticBrowserActivity.EXTRA_ALLOWED_HOSTS, allowedHosts.toTypedArray())
            })
        })
        otherOptions.addView(actionButton("MỞ BẰNG TRÌNH DUYỆT HỆ THỐNG") {
            val target = webView.url?.takeIf(::isAllowed) ?: loginUrl
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        })

'''
t = replace_once(t, old, new, "login browser hierarchy")
t = replace_once(
    t,
    '''                override fun onPageFinished(view: WebView, url: String) {
                    if (isAllowed(url)) captureSession()
''',
    '''                override fun onPageFinished(view: WebView, url: String) {
                    addressField.setText(url)
                    if (isAllowed(url)) captureSession()
''',
    "login address update",
)
t = replace_once(
    t,
    '''        root.addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(actions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
''',
    '''        root.addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(navigation, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(addressRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(browserOptions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(otherOptions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
''',
    "login hierarchy attach",
)
write(path, t)

print("settings/dialog/login parity patch applied")
