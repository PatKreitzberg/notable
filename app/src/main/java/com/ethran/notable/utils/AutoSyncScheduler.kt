package com.ethran.notable.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log
import java.util.concurrent.TimeUnit

/**
 * Utility class for scheduling automatic sync
 */
object AutoSyncScheduler {
    private const val SYNC_WORK_NAME = "notable_auto_sync"

    /**
     * Schedules automatic sync based on settings
     * @param context Application context
     * @param enabled Whether auto-sync is enabled
     * @param interval Sync interval in minutes
     * @param requireUnmetered Whether to require unmetered connection
     * @param requireCharging Whether to require charging
     */
    fun scheduleSync(
        context: Context,
        enabled: Boolean,
        interval: Long = 60,
        requireUnmetered: Boolean = false,
        requireCharging: Boolean = false
    ) {
        if (!enabled) {
            // Cancel any scheduled sync work
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            Log.i(TAG, "Auto-sync disabled, cancelled any scheduled sync")
            return
        }

        // Set up constraints
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(requireCharging)
            .build()

        // Create work request
        val syncRequest = PeriodicWorkRequestBuilder<AutoSyncWorker>(
            interval,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        // Schedule the work
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            syncRequest
        )

        Log.i(TAG, "Scheduled auto-sync every $interval minutes")
    }
}