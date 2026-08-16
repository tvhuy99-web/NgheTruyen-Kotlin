from pathlib import Path

path = Path("app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt")
text = path.read_text(encoding="utf-8")
old = '''    override fun onPause() {
        if (::webView.isInitialized) captureSession()
        super.onPause()
    }

    override fun onDestroy() {
'''
new = '''    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            captureSession()
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"diagnostic WebView lifecycle: expected 1 match, got {count}")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")

final = path.read_text(encoding="utf-8")
assert "override fun onResume()" in final
assert "webView.onResume()" in final
assert "webView.onPause()" in final
print("Round 5 diagnostic WebView lifecycle patch applied.")
