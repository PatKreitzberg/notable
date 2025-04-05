package com.ethran.notable.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.TAG
import com.ethran.notable.classes.SnackState
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.utils.AutoSyncScheduler
import com.ethran.notable.utils.GoogleDriveService
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.launch
import android.content.Context

@Composable
fun AutoSyncSettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load preferences
    val prefs = remember {
        context.getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
    }

    var autoSyncEnabled by remember {
        mutableStateOf(prefs.getBoolean("auto_sync_enabled", false))
    }

    var syncInterval by remember {
        mutableStateOf(prefs.getLong("sync_interval", 60))
    }

    var requireUnmetered by remember {
        mutableStateOf(prefs.getBoolean("require_unmetered", false))
    }

    var requireCharging by remember {
        mutableStateOf(prefs.getBoolean("require_charging", false))
    }

    var isLoading by remember { mutableStateOf(false) }

    // Create sync service
    val syncService = remember { GoogleDriveService(context) }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(2.dp, Color.Black, RectangleShape)
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = "Automatic Sync Settings",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Enable auto-sync toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = "Enable Automatic Sync",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = { autoSyncEnabled = it }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Only show settings if auto-sync is enabled
            if (autoSyncEnabled) {
                LazyColumn {
                    item {
                        // Sync interval
                        Text(
                            text = "Sync Interval",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        // Radio button group for interval options
                        Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {

                            IntervalOption(
                                text = "1 hour",
                                selected = syncInterval == 60L,
                                onClick = { syncInterval = 60L }
                            )
                            IntervalOption(
                                text = "6 hours",
                                selected = syncInterval == 360L,
                                onClick = { syncInterval = 360L }
                            )
                            IntervalOption(
                                text = "12 hours",
                                selected = syncInterval == 720L,
                                onClick = { syncInterval = 720L }
                            )
                            IntervalOption(
                                text = "24 hours",
                                selected = syncInterval == 1440L,
                                onClick = { syncInterval = 1440L }
                            )
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        // Network options
                        Text(
                            text = "Network Settings",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, bottom = 8.dp)
                        ) {
                            Text(
                                text = "Only sync on Wi-Fi",
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = requireUnmetered,
                                onCheckedChange = { requireUnmetered = it }
                            )
                        }

                        // Charging option
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, bottom = 8.dp)
                        ) {
                            Text(
                                text = "Only sync when charging",
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = requireCharging,
                                onCheckedChange = { requireCharging = it }
                            )
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        // Sync now button
                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true

                                    if (!syncService.isUserSignedIn()) {
                                        SnackState.globalSnackFlow.emit(
                                            SnackConf(
                                                text = "Please sign in to Google Drive first",
                                                duration = 3000,
                                            )
                                        )
                                    } else {
                                        val result = syncService.performAutoSync()
                                        val message = when(result) {
                                            GoogleDriveService.AutoSyncResult.SUCCESS_UPLOADED ->
                                                "Sync successful - uploaded to cloud"
                                            GoogleDriveService.AutoSyncResult.SUCCESS_DOWNLOADED ->
                                                "Sync successful - downloaded from cloud"
                                            GoogleDriveService.AutoSyncResult.NOT_SIGNED_IN ->
                                                "Not signed in to Google Drive"
                                            GoogleDriveService.AutoSyncResult.BACKUP_FAILED ->
                                                "Local backup failed"
                                            GoogleDriveService.AutoSyncResult.ERROR ->
                                                "Sync failed"
                                            GoogleDriveService.AutoSyncResult.FIRST_LAUNCH_CONFLICT ->
                                                "Cloud data exists. Please choose whether to download or upload."
                                        }

                                        SnackState.globalSnackFlow.emit(
                                            SnackConf(text = message, duration = 3000)
                                        )
                                    }

                                    isLoading = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            enabled = !isLoading
                        ) {
                            Text(text = if (isLoading) "Syncing..." else "Sync Now")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save and Close buttons
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onClose() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.LightGray)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        // Save settings
                        prefs.edit()
                            .putBoolean("auto_sync_enabled", autoSyncEnabled)
                            .putLong("sync_interval", syncInterval)
                            .putBoolean("require_unmetered", requireUnmetered)
                            .putBoolean("require_charging", requireCharging)
                            .apply()

                        // Schedule or cancel auto-sync
                        AutoSyncScheduler.scheduleSync(
                            context,
                            autoSyncEnabled,
                            syncInterval,
                            requireUnmetered,
                            requireCharging
                        )

                        // Show confirmation
                        scope.launch {
                            SnackState.globalSnackFlow.emit(
                                SnackConf(
                                    text = if (autoSyncEnabled)
                                        "Automatic sync enabled"
                                    else
                                        "Automatic sync disabled",
                                    duration = 2000
                                )
                            )
                        }

                        onClose()
                    }
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun IntervalOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}