package com.ethran.notable


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.SnackBar
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.classes.SnackState
import com.ethran.notable.datastore.EditorSettingCacheManager
import com.ethran.notable.db.KvProxy
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.NeoTools
import com.ethran.notable.modals.SyncConflictDialog
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.utils.AutoSyncWorker
import com.ethran.notable.utils.GoogleDriveService
import com.ethran.notable.viewmodels.SyncViewModel
import com.ethran.notable.views.Router
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState

var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

var TAG = "MainActivity"

@ExperimentalAnimationApi
@ExperimentalComposeUiApi
@ExperimentalFoundationApi
class MainActivity : ComponentActivity() {
    private val syncViewModel by viewModels<SyncViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()
        requestPermissions()

        // Listen for sync conflicts
        lifecycleScope.launch {
            AutoSyncWorker.syncConflictFlow.collect {
                syncViewModel.setSyncConflict(true)
            }
        }

        // Listen for sync results
        lifecycleScope.launch {
            AutoSyncWorker.syncResultFlow.collect { result ->
                // Show notification based on result
                val message = when(result) {
                    GoogleDriveService.AutoSyncResult.SUCCESS_UPLOADED ->
                        "Auto-sync successful - uploaded to cloud"
                    GoogleDriveService.AutoSyncResult.SUCCESS_DOWNLOADED ->
                        "Auto-sync successful - downloaded from cloud"
                    GoogleDriveService.AutoSyncResult.NOT_SIGNED_IN ->
                        "Auto-sync skipped - not signed in"
                    GoogleDriveService.AutoSyncResult.BACKUP_FAILED ->
                        "Auto-sync failed - backup creation failed"
                    GoogleDriveService.AutoSyncResult.ERROR ->
                        "Auto-sync failed"
                    else -> "Auto-sync status unknown"
                }

                this@MainActivity.lifecycleScope.launch {
                    SnackState.globalSnackFlow.emit(
                        SnackConf(text = message, duration = 3000)
                    )
                }
            }
        }


        ShipBook.start(
            this.application, BuildConfig.SHIPBOOK_APP_ID, BuildConfig.SHIPBOOK_APP_KEY
        )

        Log.i(TAG, "Notable started")


        if (SCREEN_WIDTH == 0) {
            SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
            SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
        }

        val snackState = SnackState()
        snackState.registerGlobalSnackObserver()
        // Refactor - we prob don't need this
        EditorSettingCacheManager.init(applicationContext)

        // it is workaround for now
        NeoTools =
            KvProxy(applicationContext).get("APP_SETTINGS", AppSettings.serializer())?.neoTools
                ?: false

        //EpdDeviceManager.enterAnimationUpdate(true);

        val intentData = intent.data?.lastPathSegment
        setContent {
            val hasConflict = syncViewModel.syncConflictDetected.collectAsState().value

            if (hasConflict) {
                SyncConflictDialog(onClose = {
                    syncViewModel.setSyncConflict(false)
                })
            }
            InkaTheme {
                CompositionLocalProvider(LocalSnackContext provides snackState) {
                    Box(
                        Modifier
                            .background(Color.White)
                    ) {
                        Router()
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.Black)
                    )
                    SnackBar(state = snackState)
                }
            }
        }
    }


    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            DrawCanvas.restartAfterConfChange.emit(Unit)
        }
    }

    override fun onPause() {
        super.onPause()
        this.lifecycleScope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // It is really necessary?
        if (hasFocus) {
            enableFullScreen() // Re-apply full-screen mode when focus is regained
        }
        this.lifecycleScope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    // when the screen orientation is changed, set new screen width  restart is not necessary,
    // as we need first to update page dimensions which is done in EditorView
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.i(TAG, "Switched to Landscape")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.i(TAG, "Switched to Portrait")
        }
        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
//        this.lifecycleScope.launch {
//            DrawCanvas.restartAfterConfChange.emit(Unit)
//        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
            }
        } else if (!Environment.isExternalStorageManager()) {
            requestManageAllFilesPermission()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestManageAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.fromParts("package", packageName, null)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    // written by GPT, but it works
    // needs to be checked if it is ok approach.
    private fun enableFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above
            // 'setDecorFitsSystemWindows(Boolean): Unit' is deprecated. Deprecated in Java
//            window.setDecorFitsSystemWindows(false)
            WindowCompat.setDecorFitsSystemWindows(window, false)
//            if (window.insetsController != null) {
//                window.insetsController!!.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
//                window.insetsController!!.systemBarsBehavior =
//                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//            }
            // Safely access the WindowInsetsController
            val controller = window.decorView.windowInsetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                Log.e(TAG, "WindowInsetsController is null")
            }
        } else {
            // For Android 10 and below
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

}