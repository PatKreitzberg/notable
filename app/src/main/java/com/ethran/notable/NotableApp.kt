package com.ethran.notable

import android.app.Application
import com.ethran.notable.utils.AutoSyncScheduler
import com.onyx.android.sdk.rx.RxManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.content.Context

class NotableApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RxManager.Builder.initAppContext(this)
        checkHiddenApiBypass()

        AutoSyncScheduler.scheduleSync(
            this,
            getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
                .getBoolean("auto_sync_enabled", false),
            getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
                .getLong("sync_interval", 60),
            getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
                .getBoolean("require_unmetered", false),
            getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
                .getBoolean("require_charging", false)
        )

    }

    private fun checkHiddenApiBypass() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

}