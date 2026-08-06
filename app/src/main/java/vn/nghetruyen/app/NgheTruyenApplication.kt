package vn.nghetruyen.app

import android.app.Application

class NgheTruyenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
