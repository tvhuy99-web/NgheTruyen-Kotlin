package vn.nghetruyen.app

import android.app.Application
import android.webkit.WebView
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.playback.AudioDirectionRuntime
import vn.nghetruyen.app.sourceplatform.AndroidChromiumVBookRuntime
import vn.nghetruyen.app.sourceplatform.AndroidWebViewSessionNetworkBroker
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
import vn.nghetruyen.source.vbook.VBookActionRuntime
import vn.nghetruyen.source.vbook.VBookActionRuntimeRegistry
import vn.nghetruyen.source.vbook.VBookRawNetworkBroker
import java.util.IdentityHashMap

class NgheTruyenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val chromiumRuntimeLock = Any()
    private val chromiumRuntimes = IdentityHashMap<Any, VBookActionRuntime>()
    private var audioDirectionRuntime: AudioDirectionRuntime? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(DiagnosticScreenRestoreLifecycleCallbacks())
        SourceBrowserViewportHost.initialize(this)
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
                    val browserSessionNetwork = VBookRawNetworkBroker(
                        AndroidWebViewSessionNetworkBroker(
                            context = this,
                            cookiePartition = brokers.cookies,
                        ),
                    )
                    val replay = ChromiumVBookReplayCoordinator(
                        browserDelegate = brokers.browser,
                        networkDelegate = brokers.network,
                        browserNetworkDelegate = browserSessionNetwork,
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

        // Hydrate the process-wide snapshot before any narration planning can inspect ambience/SFX.
        val audioPreferences = AudioDirectionPreferences.shared(this)
        audioDirectionRuntime = AudioDirectionRuntime(
            context = this,
            libraryRepository = container.libraryRepository,
            preferences = audioPreferences,
            narrationPlanCoordinator = container.narrationPlanCoordinator,
        ).also(AudioDirectionRuntime::start)
    }
}
