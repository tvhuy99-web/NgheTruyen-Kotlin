package vn.nghetruyen.app

import android.app.Application
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.sourceplatform.AndroidChromiumVBookRuntime
import vn.nghetruyen.source.vbook.VBookActionRuntimeRegistry

class NgheTruyenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        VBookActionRuntimeRegistry.install { brokers, diagnostics ->
            AndroidChromiumVBookRuntime(
                context = this,
                brokers = brokers,
                diagnostics = diagnostics,
            )
        }
        ReferenceVietPhraseRuntime.load(this)
    }
}
