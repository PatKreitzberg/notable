package com.ethran.notable.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ethran.notable.TAG
import com.ethran.notable.classes.SnackState
import com.ethran.notable.classes.SnackConf
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * Worker class that handles automatic synchronization with Google Drive
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        // Flow to notify UI of sync conflicts
        val syncConflictFlow = MutableSharedFlow<Boolean>()

        // Flow to notify UI of sync results
        val syncResultFlow = MutableSharedFlow<GoogleDriveService.AutoSyncResult>()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting automatic sync")

            // Get sync preferences
            val prefs = applicationContext.getSharedPreferences(
                "notable_sync_prefs",
                Context.MODE_PRIVATE
            )

            // Check if auto-sync is enabled
            val isAutoSyncEnabled = prefs.getBoolean("auto_sync_enabled", false)
            if (!isAutoSyncEnabled) {
                Log.i(TAG, "Auto-sync is disabled, skipping")
                return@withContext Result.success()
            }

            // Create Google Drive service
            val driveService = GoogleDriveService(applicationContext)

            val cleanupResult = driveService.cleanupSyncFiles()
            Log.i(TAG, cleanupResult)
            // Perform sync
            val result = driveService.performAutoSync()

            // Handle result
            when (result) {
                GoogleDriveService.AutoSyncResult.SUCCESS_UPLOADED -> {
                    Log.i(TAG, "Auto-sync successful - uploaded to cloud")
                    syncResultFlow.emit(result)
                }
                GoogleDriveService.AutoSyncResult.SUCCESS_DOWNLOADED -> {
                    Log.i(TAG, "Auto-sync successful - downloaded from cloud")
                    syncResultFlow.emit(result)
                }
                GoogleDriveService.AutoSyncResult.FIRST_LAUNCH_CONFLICT -> {
                    Log.i(TAG, "First launch conflict detected")
                    syncConflictFlow.emit(true)
                    return@withContext Result.retry() // Retry after user resolves conflict
                }
                GoogleDriveService.AutoSyncResult.NOT_SIGNED_IN -> {
                    Log.i(TAG, "Not signed in, skipping auto-sync")
                    return@withContext Result.success() // Not an error, just skip
                }
                else -> {
                    Log.e(TAG, "Auto-sync failed: $result")
                    syncResultFlow.emit(result)
                    return@withContext Result.failure() // Failed
                }
            }

            return@withContext Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-sync: ${e.message}", e)
            return@withContext Result.failure()
        }
    }
}