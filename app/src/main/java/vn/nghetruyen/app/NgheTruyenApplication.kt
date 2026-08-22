package vn.nghetruyen.app

import android.app.Application
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.freesound.Mode3LibraryAssetMatcher
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
import vn.nghetruyen.app.startup.StartupFreshnessMaintenance
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

        // Mode-3 local matching owns its own detailed diagnostic events and preprocessed metadata
        // index. Install only the application context; the AppContainer remains lazy until a match
        // actually runs.
        Mode3LibraryAssetMatcher.initialize(this)

        val freshnessMaintenance = StartupFreshnessMaintenance(
            context = this,
            database = container.database,
            settingsRepository = container.settingsRepository,
        )
        runCatching {
            // Both operations are completed before any playback/audio runtime can hydrate state.
            // This makes a successful migration a hard boundary: once the UI is available, legacy
            // Download/content:// audio no longer needs its external source file.
            runBlocking(Dispatchers.IO) {
                freshnessMaintenance.clearTransientState()
                freshnessMaintenance.internalizeLegacyLocalAudio()
            }
        }.onFailure { error ->
            Log.w(STARTUP_TAG, "Bảo trì dữ liệu lúc khởi động chưa hoàn tất.", error)
        }

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

    private companion object {
        const val STARTUP_TAG = "NgheTruyenStartup"
    }
}
