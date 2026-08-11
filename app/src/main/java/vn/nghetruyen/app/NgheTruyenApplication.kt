package vn.nghetruyen.app

import android.app.Application
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.sourceplatform.AndroidChromiumVBookRuntime
import vn.nghetruyen.app.sourceplatform.ChromiumVBookNetworkProjectionBroker
import vn.nghetruyen.source.vbook.VBookActionRuntimeRegistry
import java.util.IdentityHashMap

class NgheTruyenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val chromiumRuntimeLock = Any()
    private val chromiumRuntimes = IdentityHashMap<Any, AndroidChromiumVBookRuntime>()

    override fun onCreate() {
        super.onCreate()
        VBookActionRuntimeRegistry.install { brokers, diagnostics ->
            synchronized(chromiumRuntimeLock) {
                chromiumRuntimes[brokers.storage] ?: AndroidChromiumVBookRuntime(
                    context = this,
                    brokers = brokers.copy(
                        network = ChromiumVBookNetworkProjectionBroker(brokers.network),
                    ),
                    diagnostics = diagnostics,
                ).also { chromiumRuntimes[brokers.storage] = it }
            }
        }
        ReferenceVietPhraseRuntime.load(this)
    }
}
