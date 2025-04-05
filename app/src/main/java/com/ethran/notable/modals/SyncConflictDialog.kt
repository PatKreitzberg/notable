package com.ethran.notable.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.classes.SnackState
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.utils.GoogleDriveService
import kotlinx.coroutines.launch

@Composable
fun SyncConflictDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncService = GoogleDriveService(context)

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(2.dp, Color.Black, RectangleShape)
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = "Sync Conflict Detected",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )

            // Description
            Text(
                text = "There is already data in Google Drive, and you also have data on this device. How would you like to proceed?",
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Options
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                // Download option
                Button(
                    onClick = {
                        scope.launch {
                            val result = syncService.restoreDatabase()
                            syncService.updateLastSyncTime()
                            syncService.setFirstLaunchCompleted()

                            SnackState.globalSnackFlow.emit(
                                SnackConf(
                                    text = result,
                                    duration = 3000
                                )
                            )

                            onClose()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download from Google Drive")
                }

                // Upload option
                Button(
                    onClick = {
                        scope.launch {
                            val result = syncService.backupDatabase()
                            syncService.updateLastSyncTime()
                            syncService.setFirstLaunchCompleted()

                            SnackState.globalSnackFlow.emit(
                                SnackConf(
                                    text = result,
                                    duration = 3000
                                )
                            )

                            onClose()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Upload to Google Drive")
                }

                // Skip option
                Button(
                    onClick = {
                        syncService.setFirstLaunchCompleted()
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip for now")
                }
            }
        }
    }
}