package vn.nghetruyen.app

import android.app.Application
import android.webkit.WebView
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.playback.ChapterPaginationDiagnostics
import vn.nghetruyen.app.sourceplatform.AndroidChromiumVBookRuntime
import vn.nghetruyen.app.sourceplatform.ChromiumVBookBrowserReplayRuntime
import vn.nghetruyen.app.sourceplatform.ChromiumVBookDispatcherParityRuntime
import vn.nghetruyen.app.sourceplatform.ChromiumVBookReplayCoordinator
import vn.nghetruyen.app.sourceplatform.DiagnosticScreenRestoreLifecycleCallbacks
import vn.nghetruyen.app.sourceplatform.SourceBrowserViewportHost
import vn.nghetruyen.app.sourceplatform.SourceWebViewCookieReader
import vn.nghetruyen.app.sourceplatform.replayAwareChromiumDiagnostics
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.vbook.VBookActionRuntime
import vn.nghetruyen.source.vbook.VBookActionRuntimeRegistry
import java.util.IdentityHashMap

class NgheTruyenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val chromiumRuntimeLock = Any()
    private val chromiumRuntimes = IdentityHashMap<Any, VBookActionRuntime>()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(DiagnosticScreenRestoreLifecycleCallbacks())
        SourceBrowserViewportHost.initialize(this)
        ChapterPaginationDiagnostics.install { name, isError, attributes ->
            container.sourceDiagnostics.mark(
                name = name,
                category = DiagnosticCategory.RUNTIME,
                severity = if (isError) DiagnosticSeverity.ERROR else DiagnosticSeverity.DEBUG,
                sourceId = "app",
                attributes = attributes,
            )
        }
        VBookActionRuntimeRegistry.install { brokers, diagnostics ->
            if (WebView.getCurrentWebViewPackage() == null) {
                VBookActionRuntime { _, _, request ->
                    SourcePlatformResult.Failure(SourcePlatformFailure(
                        SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE,
                        "CHROMIUM_WEBVIEW_UNAVAILABLE:provider-missing",
                        request.traceId,
                    ))
                }
            } else synchronized(chromiumRuntimeLock) {
                chromiumRuntimes[brokers.storage] ?: run {
                    val replay = ChromiumVBookReplayCoordinator(
                        browserDelegate = brokers.browser,
                        networkDelegate = brokers.network,
                    )
                    val chromium = AndroidChromiumVBookRuntime(
                        context = this,
                        brokers = brokers.copy(
                            browser = replay.browserBroker,
                            network = replay.networkBroker,
                        ),
                        diagnostics = replayAwareChromiumDiagnostics(diagnostics),
                        webViewCookieReader = brokers.browser as? SourceWebViewCookieReader,
                    )
                    ChromiumVBookDispatcherParityRuntime(
                        ChromiumVBookBrowserReplayRuntime(
                            delegate = chromium,
                            replay = replay,
                            diagnostics = diagnostics,
                        ),
                    )
                }.also { chromiumRuntimes[brokers.storage] = it }
            }
        }
        ReferenceVietPhraseRuntime.load(this)
    }
}
