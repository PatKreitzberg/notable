package com.ethran.notable.modals

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.TAG
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.utils.GoogleDriveService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.launch

@Composable
fun GoogleDriveBackupDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackManager = LocalSnackContext.current
    val driveService = remember { GoogleDriveService(context) }

    // State
    var isSignedIn by remember { mutableStateOf(driveService.isUserSignedIn()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    // Sign-in launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                task.getResult(ApiException::class.java)
                isSignedIn = driveService.isUserSignedIn()

                if (isSignedIn) {
                    scope.launch {
                        snackManager.displaySnack(
                            SnackConf(text = "Google sign-in successful", duration = 2000)
                        )
                    }
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Sign-in failed: ${e.statusCode}", e)
                scope.launch {
                    snackManager.displaySnack(
                        SnackConf(text = "Sign-in failed", duration = 2000)
                    )
                }
            }
        }
    }

    // Check sign-in status on load
    LaunchedEffect(Unit) {
        isSignedIn = driveService.isUserSignedIn()
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(2.dp, Color.Black, RectangleShape)
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = "Google Drive Synchronization",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Status message
            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Loading indicator
            if (isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Sign-in status and button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = if (isSignedIn) "Connected to Google Drive" else "Not connected",
                    modifier = Modifier.weight(1f)
                )

                if (!isSignedIn) {
                    ActionButton(
                        text = "Sign In",
                        onClick = {
                            val googleSignInClient = GoogleSignIn.getClient(
                                context, driveService.getGoogleSignInOptions()
                            )
                            signInLauncher.launch(googleSignInClient.signInIntent)
                        }
                    )
                } else {
                    ActionButton(
                        text = "Sign Out",
                        onClick = {
                            val googleSignInClient = GoogleSignIn.getClient(
                                context, driveService.getGoogleSignInOptions()
                            )
                            googleSignInClient.signOut().addOnCompleteListener {
                                isSignedIn = false
                            }
                        }
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Sync and restore buttons (only enabled when signed in)
            if (isSignedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ActionButton(
                        text = "Sync Now",
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = "Synchronizing database..."

                                val result = driveService.backupDatabase()

                                isLoading = false
                                statusMessage = result

                                snackManager.displaySnack(
                                    SnackConf(text = result, duration = 3000)
                                )
                            }
                        },
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    ActionButton(
                        text = "Restore",
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = "Restoring from sync..."

                                val result = driveService.restoreDatabase()

                                isLoading = false
                                statusMessage = result

                                snackManager.displaySnack(
                                    SnackConf(text = result, duration = 3000)
                                )
                            }
                        },
                    )
                }
            } else {
                Text(
                    text = "Sign in with Google to sync or restore your database",
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Close button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                ActionButton(
                    text = "Close",
                    onClick = onClose
                )
            }
        }
    }
}