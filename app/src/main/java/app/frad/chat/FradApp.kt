package app.frad.chat

import android.app.Application
import app.frad.chat.diagnostics.CrashReports
import app.frad.chat.profile.Profile

class FradApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val profile = Profile(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        CrashReports.install(this, version) { profile.crashReports }
    }
}
