package vn.nghetruyen.app

import android.app.Application
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime

class NgheTruyenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ReferenceVietPhraseRuntime.load(this)
    }
}
