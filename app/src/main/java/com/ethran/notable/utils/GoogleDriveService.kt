package com.ethran.notable.utils

import android.content.Context
import android.os.Environment
import com.ethran.notable.TAG
import com.ethran.notable.db.AppDatabase
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service class to handle Google Drive operations with synchronization
 */
class GoogleDriveService(private val context: Context) {

    companion object {
        private const val APP_FOLDER_NAME = "Notable Database Backups"
        private const val SYNC_FILE_NAME = "notable_database_sync.db"
        private const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        private const val MIME_TYPE_DB = "application/octet-stream"
    }

    /**
     * Get Google Sign-In options for authentication
     */
    fun getGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
    }

    /**
     * Check if user is signed in with the required permissions
     */
    fun isUserSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null &&
                GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
    }

    /**
     * Get Drive service instance for API calls
     */
    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.e(TAG, "No Google account found")
            return null
        }

        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Notable")
            .build()
    }

    /**
     * Synchronize database to Google Drive
     * @return Success or error message
     */
    suspend fun backupDatabase(): String = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext "Not signed in with Google"

            // Get database file
            val dbFile = getDatabaseFile()
            if (!dbFile.exists()) {
                return@withContext "Database file not found"
            }

            // Find or create app folder
            val folderId = findOrCreateAppFolder(driveService)
                ?: return@withContext "Failed to create backup folder"

            // Check if sync file already exists
            val existingFileId = findSyncFile(driveService, folderId)
            val mediaContent = FileContent(MIME_TYPE_DB, dbFile)

            if (existingFileId != null) {
                // Update existing file
                driveService.files().update(existingFileId, null, mediaContent).execute()

                // Get the current timestamp for the log message
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())

                return@withContext "Database synchronized successfully at $timestamp"
            } else {
                // Create new sync file
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = SYNC_FILE_NAME
                    parents = listOf(folderId)
                }

                driveService.files().create(fileMetadata, mediaContent).execute()
                return@withContext "Initial database sync created successfully"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing database", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Restore database from synchronized Google Drive backup
     * @return Success or error message
     */
    suspend fun restoreDatabase(): String = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext "Not signed in with Google"

            // Find app folder
            val folderId = findAppFolder(driveService) ?: return@withContext "No backup folder found"

            // Find sync file
            val syncFileId = findSyncFile(driveService, folderId)
                ?: return@withContext "No synchronized backup found"

            // Download backup file
            val tempFile = File(context.cacheDir, "temp_backup.db")
            driveService.files().get(syncFileId)
                .executeMediaAndDownloadTo(FileOutputStream(tempFile))

            // Close database connections
            AppDatabase.closeConnection()

            // Replace database file
            val dbFile = getDatabaseFile()
            tempFile.copyTo(dbFile, overwrite = true)
            tempFile.delete()

            // Reopen database
            AppDatabase.getDatabase(context)

            return@withContext "Database restored successfully from synchronized backup"
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring database", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Find or create app folder in Google Drive
     */
    private fun findOrCreateAppFolder(driveService: Drive): String? {
        // Try to find existing folder
        val folderId = findAppFolder(driveService)
        if (folderId != null) {
            return folderId
        }

        // Create new folder
        try {
            val folderMetadata = com.google.api.services.drive.model.File().apply {
                name = APP_FOLDER_NAME
                mimeType = MIME_TYPE_FOLDER
            }

            val folder = driveService.files().create(folderMetadata)
                .setFields("id")
                .execute()

            return folder.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create app folder", e)
            return null
        }
    }

    /**
     * Find app folder in Google Drive
     */
    private fun findAppFolder(driveService: Drive): String? {
        try {
            val result = driveService.files().list()
                .setQ("name = '$APP_FOLDER_NAME' and mimeType = '$MIME_TYPE_FOLDER' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()

            return if (result.files.isNotEmpty()) result.files[0].id else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find app folder", e)
            return null
        }
    }

    /**
     * Find sync file in Google Drive
     */
    private fun findSyncFile(driveService: Drive, folderId: String): String? {
        try {
            val result = driveService.files().list()
                .setQ("name = '$SYNC_FILE_NAME' and '$folderId' in parents and trashed = false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()

            return if (result.files.isNotEmpty()) result.files[0].id else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find sync file", e)
            return null
        }
    }

    /**
     * Get database file path
     */
    private fun getDatabaseFile(): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dbDir = File(documentsDir, "notabledb")
        return File(dbDir, "app_database")
    }
}