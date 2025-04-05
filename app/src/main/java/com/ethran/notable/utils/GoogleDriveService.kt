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
import kotlin.collections.sortByDescending

/**
 * Service class to handle Google Drive operations with synchronization
 * Keeps both a current sync file and a backup of the previous sync
 */
class GoogleDriveService(private val context: Context) {

    companion object {
        private const val APP_FOLDER_NAME = "Notable Database Backups"
        private const val SYNC_FILE_NAME = "notable_database_sync.db"
        private const val SYNC_BACKUP_FILE_NAME = "notable_database_sync_backup.db"
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
     * Keeps both current sync and a backup of the previous sync
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

            // Check if current sync file exists
            val existingSyncFileId = findSyncFile(driveService, folderId)
            // Check if backup sync file exists
            val existingBackupFileId = findSyncFile(driveService, folderId, true)

            val mediaContent = FileContent(MIME_TYPE_DB, dbFile)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            if (existingSyncFileId != null) {
                // First, handle the backup file
                if (existingBackupFileId != null) {
                    // DELETE existing backup file before creating a new one
                    driveService.files().delete(existingBackupFileId).execute()
                    Log.i(TAG, "Deleted existing backup file: $existingBackupFileId")
                }

                // Create new backup file by copying the current sync
                val backupMetadata = com.google.api.services.drive.model.File().apply {
                    name = SYNC_BACKUP_FILE_NAME
                    parents = listOf(folderId)
                }

                // Get current sync file content
                val tempFile = File(context.cacheDir, "temp_current_sync.db")
                driveService.files().get(existingSyncFileId)
                    .executeMediaAndDownloadTo(FileOutputStream(tempFile))

                // Upload as backup
                val backupMediaContent = FileContent(MIME_TYPE_DB, tempFile)
                driveService.files().create(backupMetadata, backupMediaContent).execute()
                Log.i(TAG, "Created new backup from current sync")

                // Clean up temp file
                tempFile.delete()

                // Now update the current sync file with new content
                driveService.files().update(existingSyncFileId, null, mediaContent).execute()
                Log.i(TAG, "Updated existing sync file: $existingSyncFileId")

                return@withContext "Database synchronized successfully at $timestamp (Previous sync saved as backup)"
            } else {
                // Create new sync file
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = SYNC_FILE_NAME
                    parents = listOf(folderId)
                }

                driveService.files().create(fileMetadata, mediaContent).execute()
                Log.i(TAG, "Created initial sync file")
                return@withContext "Initial database sync created successfully"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing database", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Restore database from Google Drive (either current sync or backup sync)
     * @param useBackup Whether to restore from the backup sync instead of the current sync
     * @return Success or error message
     */
    suspend fun restoreDatabase(useBackup: Boolean = false): String = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext "Not signed in with Google"

            // Find app folder
            val folderId = findAppFolder(driveService) ?: return@withContext "No backup folder found"

            // Find requested sync file
            val syncFileId = findSyncFile(driveService, folderId, useBackup)
                ?: if (useBackup) {
                    return@withContext "No backup sync file found"
                } else {
                    return@withContext "No synchronized database found"
                }

            // Download the selected file
            val tempFile = File(context.cacheDir, "temp_restore.db")
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

            val source = if (useBackup) "backup sync" else "current sync"
            return@withContext "Database restored successfully from $source"
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring database", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Check if a backup sync file exists
     * @return true if a backup sync file exists
     */
    suspend fun backupSyncExists(): Boolean = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext false
            val folderId = findAppFolder(driveService) ?: return@withContext false
            return@withContext findSyncFile(driveService, folderId, true) != null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking backup sync existence", e)
            return@withContext false
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
     * This method has been modified to be more strict in finding files by exact name matching
     */
    private fun findSyncFile(driveService: Drive, folderId: String, isBackup: Boolean = false): String? {
        val fileName = if (isBackup) SYNC_BACKUP_FILE_NAME else SYNC_FILE_NAME
        try {
            // Use a more precise query to find the exact file
            val query = "name = '$fileName' and '$folderId' in parents and trashed = false"

            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")  // Also get the name for logging
                .execute()

            if (result.files.isEmpty()) {
                Log.i(TAG, "No sync file found with name: $fileName")
                return null
            }

            // If there are multiple files (shouldn't happen with exact name matching), log it
            if (result.files.size > 1) {
                Log.w(TAG, "Multiple ${if (isBackup) "backup" else "sync"} files found: ${result.files.size}")
                // Return the first one
                return result.files[0].id
            }

            Log.i(TAG, "Found ${if (isBackup) "backup" else "sync"} file: ${result.files[0].name}")
            return result.files[0].id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find sync file: $fileName", e)
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

    /**
     * Cleans up duplicate sync files in Google Drive
     * This should be called periodically to ensure we don't accumulate multiple backup files
     */
    suspend fun cleanupSyncFiles(): String = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext "Not signed in with Google"

            // Find app folder
            val folderId = findAppFolder(driveService) ?: return@withContext "No backup folder found"

            // Find all files with the sync name
            val syncFiles = findAllSyncFiles(driveService, folderId, false)
            val backupFiles = findAllSyncFiles(driveService, folderId, true)

            var deletedCount = 0

            // Keep only the newest sync file
            if (syncFiles.size > 1) {
                // Sort by modified time (newest first)
                val sortedSyncFiles = syncFiles.sortedByDescending { it.modifiedTime.value }

                // Keep the first one (newest), delete the rest
                sortedSyncFiles.drop(1).forEach { file ->
                    driveService.files().delete(file.id).execute()
                    deletedCount++
                    Log.i(TAG, "Deleted duplicate sync file: ${file.name}")
                }
            }

            // Keep only the newest backup file
            if (backupFiles.size > 1) {
                // Sort by modified time (newest first)
                val sortedSyncFiles = syncFiles.sortedByDescending { it.modifiedTime.value }

                // Keep the first one (newest), delete the rest
                sortedSyncFiles.drop(1).forEach { file ->
                    driveService.files().delete(file.id).execute()
                    deletedCount++
                    Log.i(TAG, "Deleted duplicate backup file: ${file.name}")
                }
            }

            return@withContext if (deletedCount > 0) {
                "Cleaned up $deletedCount duplicate sync files"
            } else {
                "No duplicate files found"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up sync files: ${e.message}", e)
            return@withContext "Error cleaning up files: ${e.message}"
        }
    }

    /**
     * Find all sync files with the given name pattern
     */
    private fun findAllSyncFiles(driveService: Drive, folderId: String, isBackup: Boolean): List<com.google.api.services.drive.model.File> {
        val fileName = if (isBackup) SYNC_BACKUP_FILE_NAME else SYNC_FILE_NAME
        try {
            val query = "name = '$fileName' and '$folderId' in parents and trashed = false"

            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime)")
                .execute()

            return result.files
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find sync files: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Checks if there is data in the cloud that is newer than the local database
     * @return true if cloud data is newer, false otherwise, null if error or not signed in
     */
    suspend fun isCloudDataNewer(): Boolean? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext null

            // Find app folder
            val folderId = findAppFolder(driveService) ?: return@withContext null

            // Find current sync file
            val syncFileId = findSyncFile(driveService, folderId) ?: return@withContext false

            // Get the metadata for the sync file to check last modified time
            val fileMetadata = driveService.files().get(syncFileId)
                .setFields("modifiedTime")
                .execute()

            // Parse the modified time
            val cloudModifiedTime = fileMetadata.modifiedTime.value

            // Get the last local sync time from preferences
            val prefs = context.getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
            val lastLocalSyncTime = prefs.getLong("last_sync_time", 0)

            // Compare timestamps
            return@withContext cloudModifiedTime > lastLocalSyncTime
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cloud data: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Checks if this is the first launch on this device
     * @return true if first launch, false otherwise
     */
    fun isFirstLaunch(): Boolean {
        val prefs = context.getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_first_launch", true)
    }

    /**
     * Sets the first launch flag to false
     */
    fun setFirstLaunchCompleted() {
        val prefs = context.getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_first_launch", false).apply()
    }

    /**
     * Updates the last sync timestamp
     */
    fun updateLastSyncTime() {
        val prefs = context.getSharedPreferences("notable_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
    }

    /**
     * Creates a local backup of the database
     * @return path to backup file, or null if backup failed
     */
    suspend fun createLocalBackup(): String? = withContext(Dispatchers.IO) {
        try {
            val dbFile = getDatabaseFile()
            if (!dbFile.exists()) {
                return@withContext null
            }

            // Create backup directory if it doesn't exist
            val backupDir = File(context.filesDir, "database_backups")
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            // Create backup file with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupFile = File(backupDir, "backup_${timestamp}.db")

            // Copy database to backup file
            dbFile.copyTo(backupFile, overwrite = true)

            return@withContext backupFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error creating local backup: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Performs automatic synchronization with conflict resolution
     * @return Result of the sync operation
     */
    suspend fun performAutoSync(): AutoSyncResult = withContext(Dispatchers.IO) {
        // Define result statuses
        if (!isUserSignedIn()) {
            return@withContext AutoSyncResult.NOT_SIGNED_IN
        }

        // Create local backup first for safety
        val backupPath = createLocalBackup()
        if (backupPath == null) {
            return@withContext AutoSyncResult.BACKUP_FAILED
        }

        // Check if this is first launch
        if (isFirstLaunch()) {
            // On first launch, check if cloud data exists
            //val cloudDataExists = findAppFolder(getDriveService()) != null
            val driveService = getDriveService()
            val cloudDataExists = if (driveService != null) {
                findAppFolder(driveService) != null
            } else {
                Log.w(TAG, "Could not access Google Drive service, cloud data check skipped.")
                false // Or handle the error differently
            }

            if (cloudDataExists) {
                // Cloud data exists, return conflict result to let UI handle the decision
                return@withContext AutoSyncResult.FIRST_LAUNCH_CONFLICT
            } else {
                // No cloud data, safe to upload
                val result = backupDatabase()
                updateLastSyncTime()
                setFirstLaunchCompleted()
                return@withContext if (result.contains("successfully")) {
                    AutoSyncResult.SUCCESS_UPLOADED
                } else {
                    AutoSyncResult.ERROR
                }
            }
        }

        // Normal sync flow (not first launch)
        val isCloudNewer = isCloudDataNewer()

        when {
            isCloudNewer == null -> return@withContext AutoSyncResult.ERROR
            isCloudNewer -> {
                // Cloud is newer, we should download
                val result = restoreDatabase()
                updateLastSyncTime()
                return@withContext if (result.contains("successfully")) {
                    AutoSyncResult.SUCCESS_DOWNLOADED
                } else {
                    AutoSyncResult.ERROR
                }
            }
            else -> {
                // Local is newer or same, we should upload
                val result = backupDatabase()
                updateLastSyncTime()
                return@withContext if (result.contains("successfully")) {
                    AutoSyncResult.SUCCESS_UPLOADED
                } else {
                    AutoSyncResult.ERROR
                }
            }
        }
    }

    // Add this enum class to represent the result of auto-sync
    enum class AutoSyncResult {
        SUCCESS_UPLOADED,
        SUCCESS_DOWNLOADED,
        NOT_SIGNED_IN,
        BACKUP_FAILED,
        ERROR,
        FIRST_LAUNCH_CONFLICT
    }

}