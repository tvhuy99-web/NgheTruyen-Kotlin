#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

# Explore: dynamic source action strip derived from source capabilities/session state.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt"
t = read(path)
t = replace_once(
    t,
    '    onLoadMore: () -> Unit,\n    onStoryClick: (StorySummary) -> Unit,\n',
    '    onLoadMore: () -> Unit,\n    onStoryClick: (StorySummary) -> Unit,\n    onOpenSourceLogin: (String) -> Unit,\n    onCheckSource: (String) -> Unit,\n',
    "explore callbacks",
)
marker = '''            DropdownMenu(
                expanded = sourceMenuOpen,
                onDismissRequest = { sourceMenuOpen = false },
            ) {
                state.sources.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            val status = if (source.health == SourceHealth.READY) "" else " • ${source.health.name}"
                            Text(source.displayName + status)
                        },
                        onClick = {
                            sourceMenuOpen = false
                            onSourceSelected(source.id)
                        },
                    )
                }
            }

'''
insert = marker + '''            if (!state.searchAllSources && selectedSource != null &&
                (selectedSource.loginUrl != null || selectedSource.health != SourceHealth.READY)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (selectedSource.loginUrl != null) {
                        ReferenceActionButton(
                            text = if (selectedSource.id in state.sourceSessions) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP NGUỒN",
                            onClick = { onOpenSourceLogin(selectedSource.id) },
                            normalColor = ReferenceDivider,
                            normalContentColor = ReferenceText,
                            minHeight = 48.dp,
                            modifier = Modifier.weight(1f).padding(1.dp),
                        )
                    }
                    ReferenceActionButton(
                        text = if (selectedSource.id in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA NGUỒN",
                        onClick = { onCheckSource(selectedSource.id) },
                        enabled = selectedSource.id !in state.sourceHealthChecking && selectedSource.health != SourceHealth.NOT_PORTED,
                        normalColor = ReferenceDivider,
                        normalContentColor = ReferenceText,
                        minHeight = 48.dp,
                        modifier = Modifier.weight(1f).padding(1.dp),
                    )
                }
            }

'''
t = replace_once(t, marker, insert, "explore source action strip")
write(path, t)

# Story: source action strip directly below primary story actions.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
t = read(path)
t = replace_once(
    t,
    '    onOpenOriginal: (String) -> Unit,\n    onCheckSource: (String) -> Unit,\n',
    '    onOpenOriginal: (String) -> Unit,\n    onCheckSource: (String) -> Unit,\n    onOpenSourceLogin: (String) -> Unit,\n',
    "story source callback",
)
marker = '''        }
        if (showStoryMenu) {
'''
insert = '''        }
        if (sourceDescriptor != null && (sourceDescriptor.loginUrl != null || sourceDescriptor.health != vn.nghetruyen.app.core.model.SourceHealth.READY)) {
            Row(modifier = Modifier.fillMaxWidth().background(ReferenceDivider).padding(2.dp)) {
                if (sourceDescriptor.loginUrl != null) {
                    ReferenceActionButton(
                        text = if (sourceDescriptor.id in state.sourceSessions) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP NGUỒN",
                        onClick = { onOpenSourceLogin(sourceDescriptor.id) },
                        normalColor = ReferenceGray,
                        minHeight = 48.dp,
                        modifier = Modifier.weight(1f).padding(1.dp),
                    )
                }
                ReferenceActionButton(
                    text = if (sourceDescriptor.id in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA NGUỒN",
                    onClick = { onCheckSource(sourceDescriptor.id) },
                    enabled = sourceDescriptor.id !in state.sourceHealthChecking,
                    normalColor = ReferenceGray,
                    minHeight = 48.dp,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
            }
        }
        if (showStoryMenu) {
'''
t = replace_once(t, marker, insert, "story source action strip")
write(path, t)

# Reader: dynamic source strip when a source needs session/health actions.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
t = read(path)
t = replace_once(
    t,
    '    onSelectSceneMusic: () -> Unit,\n    onMessage: (String) -> Unit,\n',
    '    onSelectSceneMusic: () -> Unit,\n    onOpenSourceLogin: (String) -> Unit,\n    onCheckSource: (String) -> Unit,\n    onMessage: (String) -> Unit,\n',
    "reader source callbacks",
)
t = replace_once(
    t,
    '    val activeNote = state.notes.firstOrNull { it.chapterId == content.chapter.id && it.paragraphIndex == activeIndex }\n',
    '    val activeNote = state.notes.firstOrNull { it.chapterId == content.chapter.id && it.paragraphIndex == activeIndex }\n'
    '    val readerSourceDescriptor = state.storyDetail?.story?.sourceId?.let { sourceId -> state.sources.firstOrNull { it.id == sourceId } }\n',
    "reader source descriptor",
)
marker = '''            Row(modifier = Modifier.fillMaxWidth()) {
                if (state.diagnosticsMode != "off") {
'''
insert = '''            if (readerSourceDescriptor != null &&
                (readerSourceDescriptor.loginUrl != null || readerSourceDescriptor.health != vn.nghetruyen.app.core.model.SourceHealth.READY)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (readerSourceDescriptor.loginUrl != null) {
                        ReaderButton(
                            if (readerSourceDescriptor.id in state.sourceSessions) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP NGUỒN",
                            { onOpenSourceLogin(readerSourceDescriptor.id) },
                            Modifier.weight(1f),
                            normalColor = ReferenceGray,
                        )
                    }
                    ReaderButton(
                        if (readerSourceDescriptor.id in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA NGUỒN",
                        { onCheckSource(readerSourceDescriptor.id) },
                        Modifier.weight(1f),
                        enabled = readerSourceDescriptor.id !in state.sourceHealthChecking,
                        normalColor = ReferenceGray,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                if (state.diagnosticsMode != "off") {
'''
t = replace_once(t, marker, insert, "reader source action strip")
write(path, t)

# App wiring for the three source action strips.
path = "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"
t = read(path)
t = replace_once(
    t,
    '                        onLoadMore = viewModel::loadMoreStories,\n                        onStoryClick = viewModel::openStory,\n',
    '                        onLoadMore = viewModel::loadMoreStories,\n                        onStoryClick = viewModel::openStory,\n                        onOpenSourceLogin = viewModel::openSourceLogin,\n                        onCheckSource = viewModel::checkSource,\n',
    "explore source wiring",
)
t = replace_once(
    t,
    '                    onOpenOriginal = viewModel::openExternalUrl,\n                    onCheckSource = viewModel::checkSource,\n',
    '                    onOpenOriginal = viewModel::openExternalUrl,\n                    onCheckSource = viewModel::checkSource,\n                    onOpenSourceLogin = viewModel::openSourceLogin,\n',
    "story source wiring",
)
t = replace_once(
    t,
    '                    onSelectSceneMusic = onSelectSceneMusic,\n                    onMessage = viewModel::readerActionMessage,\n',
    '                    onSelectSceneMusic = onSelectSceneMusic,\n                    onOpenSourceLogin = viewModel::openSourceLogin,\n                    onCheckSource = viewModel::checkSource,\n                    onMessage = viewModel::readerActionMessage,\n',
    "reader source wiring",
)
write(path, t)

# Diagnostic browser: move direct controls into the reference hierarchy.
path = "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt"
t = read(path)
t = replace_once(t, 'import android.content.ClipboardManager\n', 'import android.content.ClipboardManager\nimport android.content.Intent\n', "browser intent import")
t = replace_once(t, 'import android.view.ViewGroup\n', 'import android.view.View\nimport android.view.ViewGroup\n', "browser view import")
t = replace_once(t, '    private var userAgentMode = 0\n', '    private var userAgentMode = 0\n    private var autoClearLog = false\n', "browser auto clear state")
old = '''        root.addView(status, matchWrap())
        root.addView(progress, matchWrap())
        root.addView(urlField, matchWrap())
        root.addView(row(
            button("←") { if (webView.canGoBack()) webView.goBack() },
            button("→") { if (webView.canGoForward()) webView.goForward() },
            button("TẢI LẠI") { webView.reload() },
            button("ĐI") { navigate(urlField.text.toString()) },
        ), matchWrap())
        root.addView(row(
            button("KIỂM TRA JS") { runJavaScriptProbe() },
            button("COOKIE") { runCookieProbe() },
            button("QUÉT DOM") { runDomProbe() },
            button("REQUEST") { summarizeRequests() },
        ), matchWrap())
        root.addView(row(
            button("LƯU PHIÊN") { captureSession(); setStatus("Đã lưu phiên nguồn. Cookie chỉ được lưu mã hóa trong kho phiên.") },
            button("MỨC LOG") { verbose = !verbose; record("INFO", "LOG_LEVEL", if (verbose) "VERBOSE" else "BASIC") },
            button("SAO CHÉP") { copyLog() },
            button("XUẤT JSON") { exportLauncher.launch("source-${sourceId.take(40)}-diagnostics.json") },
            button("XÓA LOG") { entries.clear(); requests.clear(); requestCount = 0; renderLog() },
        ), matchWrap())
        root.addView(row(
            button("UA") { cycleUserAgent() },
            button("MIỀN") { strictOrigins = !strictOrigins; record("POLICY", "ORIGIN_MODE", if (strictOrigins) "SOURCE_ONLY" else "COMPATIBLE_HTTPS") },
            button("TÀI NGUYÊN") { blockExternalResources = !blockExternalResources; record("POLICY", "RESOURCE_MODE", if (blockExternalResources) "BLOCK_EXTERNAL" else "OBSERVE_EXTERNAL") },
            button("DIALOG") { dialogPolicy = dialogPolicy.next(); record("POLICY", "DIALOG_MODE", dialogPolicy.name) },
            button("XÓA COOKIE") { clearSourceCookies() },
            button("STORAGE") { runStorageProbe() },
        ), matchWrap())

'''
new = '''        val browserOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val otherOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val diagnosticOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        root.addView(status, matchWrap())
        root.addView(progress, matchWrap())
        root.addView(row(
            button("QUAY LẠI") { if (webView.canGoBack()) webView.goBack() },
            button("TIẾN TỚI") { if (webView.canGoForward()) webView.goForward() },
            button("TÙY CHỌN") {
                browserOptions.visibility = if (browserOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (browserOptions.visibility == View.GONE) {
                    otherOptions.visibility = View.GONE
                    diagnosticOptions.visibility = View.GONE
                    if (::logView.isInitialized) logView.visibility = View.GONE
                }
            },
        ), matchWrap())
        val addressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addressRow.addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addressRow.addView(button("ĐI TỚI") { navigate(urlField.text.toString()) })
        root.addView(addressRow, matchWrap())

        browserOptions.addView(button("LÀM MỚI") { webView.reload() }, matchWrap())
        browserOptions.addView(button("TÙY CHỌN KHÁC") {
            otherOptions.visibility = if (otherOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, matchWrap())
        browserOptions.addView(button("XÓA DỮ LIỆU ĐĂNG NHẬP CỦA TRANG") { clearSourceCookies() }, matchWrap())
        browserOptions.addView(button("ĐÓNG TRÌNH DUYỆT") { finish() }, matchWrap())
        root.addView(browserOptions, matchWrap())

        otherOptions.addView(button("CHẾ ĐỘ TƯƠNG THÍCH CHROME") { cycleUserAgent() }, matchWrap())
        otherOptions.addView(button("CHẨN ĐOÁN TRÌNH DUYỆT") {
            diagnosticOptions.visibility = View.VISIBLE
            if (::logView.isInitialized) logView.visibility = View.VISIBLE
        }, matchWrap())
        otherOptions.addView(button("MỨC NHẬT KÝ") {
            verbose = !verbose
            record("INFO", "LOG_LEVEL", if (verbose) "VERBOSE" else "BASIC")
        }, matchWrap())
        otherOptions.addView(button("TỰ XÓA NHẬT KÝ") {
            autoClearLog = !autoClearLog
            setStatus(if (autoClearLog) "Đã bật tự xóa nhật ký khi điều hướng." else "Đã tắt tự xóa nhật ký.")
        }, matchWrap())
        otherOptions.addView(button("MỞ BẰNG TRÌNH DUYỆT HỆ THỐNG") {
            val target = webView.url ?: initialUrl
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }, matchWrap())
        root.addView(otherOptions, matchWrap())

        diagnosticOptions.addView(row(
            button("LÀM MỚI") { webView.reload() },
            button("SAO CHÉP") { copyLog() },
            button("XUẤT") { exportLauncher.launch("source-${sourceId.take(40)}-diagnostics.json") },
        ), matchWrap())
        diagnosticOptions.addView(row(
            button("KIỂM TRA JS") { runJavaScriptProbe() },
            button("KIỂM TRA COOKIE") { runCookieProbe() },
            button("QUÉT TRANG") { runDomProbe() },
        ), matchWrap())
        diagnosticOptions.addView(row(
            button("XÓA NHẬT KÝ") { entries.clear(); requests.clear(); requestCount = 0; renderLog() },
            button("ĐÓNG") {
                diagnosticOptions.visibility = View.GONE
                if (::logView.isInitialized) logView.visibility = View.GONE
            },
        ), matchWrap())
        root.addView(diagnosticOptions, matchWrap())

'''
t = replace_once(t, old, new, "browser hierarchy")
t = replace_once(
    t,
    '        logView = TextView(this).apply { setTextIsSelectable(true); setPadding(16, 10, 16, 16); textSize = 11f }\n',
    '        logView = TextView(this).apply { setTextIsSelectable(true); setPadding(16, 10, 16, 16); textSize = 11f; visibility = View.GONE }\n',
    "browser log initial visibility",
)
t = replace_once(
    t,
    '    private fun navigate(raw: String) {\n        val target = raw.trim()\n',
    '    private fun navigate(raw: String) {\n        val target = raw.trim()\n        if (autoClearLog) { entries.clear(); requests.clear(); requestCount = 0; renderLog() }\n',
    "browser auto clear behavior",
)
write(path, t)

print("tail reference UI parity patch applied")
