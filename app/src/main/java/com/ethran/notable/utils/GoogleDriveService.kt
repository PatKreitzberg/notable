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
 * Service class to handle Google Drive operations
 */
class GoogleDriveService(private val context: Context) {

    companion object {
        private const val APP_FOLDER_NAME = "Notable Database Backups"
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
     * Backup database to Google Drive
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

            // Create backup file name with timestamp
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            val backupName = "notable_backup_$timestamp.db"

            // Create file metadata
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = backupName
                parents = listOf(folderId)
            }

            // Upload file content
            val mediaContent = FileContent(MIME_TYPE_DB, dbFile)
            driveService.files().create(fileMetadata, mediaContent).execute()

            // Cleanup old backups - keep only last 5
            cleanupOldBackups(driveService, folderId)

            return@withContext "Database backup created successfully"
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up database", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Restore database from most recent Google Drive backup
     * @return Success or error message
     */
    suspend fun restoreDatabase(): String = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext "Not signed in with Google"

            // Find app folder
            val folderId = findAppFolder(driveService) ?: return@withContext "No backup folder found"

            // Find most recent backup
            val result = driveService.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setOrderBy("createdTime desc")
                .setPageSize(1)
                .setFields("files(id, name)")
                .execute()

            if (result.files.isEmpty()) {
                return@withContext "No backups found"
            }

            val backupFile = result.files[0]

            // Download backup file
            val tempFile = File(context.cacheDir, "temp_backup.db")
            driveService.files().get(backupFile.id)
                .executeMediaAndDownloadTo(FileOutputStream(tempFile))

            // Close database connections
            AppDatabase.closeConnection()

            // Replace database file
            val dbFile = getDatabaseFile()
            tempFile.copyTo(dbFile, overwrite = true)
            tempFile.delete()

            // Reopen database
            AppDatabase.getDatabase(context)

            return@withContext "Database restored successfully from ${backupFile.name}"
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
     * Keep only the 5 most recent backups
     */
    private fun cleanupOldBackups(driveService: Drive, folderId: String) {
        try {
            val result = driveService.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setOrderBy("createdTime desc")
                .setFields("files(id, name)")
                .execute()

            if (result.files.size > 5) {
                // Delete older backups (keeping the 5 most recent)
                for (i in 5 until result.files.size) {
                    driveService.files().delete(result.files[i].id).execute()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old backups", e)
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