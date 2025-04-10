package com.ethran.notable.db

import android.content.Context
import android.os.Environment
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.ethran.notable.modals.PaperFormat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date


class Converters {
    @TypeConverter
    fun fromListString(value: List<String>) = Json.encodeToString(value)

    @TypeConverter
    fun toListString(value: String) = Json.decodeFromString<List<String>>(value)

    @TypeConverter
    fun fromListPoint(value: List<StrokePoint>) = Json.encodeToString(value)

    @TypeConverter
    fun toListPoint(value: String) = Json.decodeFromString<List<StrokePoint>>(value)

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    // Converters for PaperFormat enum
    @TypeConverter
    fun fromPaperFormat(value: PaperFormat?): String? {
        return value?.name
    }

    @TypeConverter
    fun toPaperFormat(value: String?): PaperFormat? {
        return value?.let {
            try {
                PaperFormat.valueOf(it)
            } catch (e: IllegalArgumentException) {
                PaperFormat.A4 // Default to A4 if invalid format
            }
        }
    }
}


@Database(
    entities = [Folder::class, Notebook::class, Page::class, Stroke::class, Image::class, Kv::class],
    version = 32, // Increase version for the new PaperFormat field
    autoMigrations = [
        AutoMigration(19, 20),
        AutoMigration(20, 21),
        AutoMigration(21, 22),
        AutoMigration(23, 24),
        AutoMigration(24, 25),
        AutoMigration(25, 26),
        AutoMigration(26, 27),
        AutoMigration(27, 28),
        AutoMigration(28, 29),
        AutoMigration(29, 30),
        AutoMigration(30, 31),
        AutoMigration(31, 32), // Migration for PaperFormat
    ], exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun kvDao(): KvDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun ImageDao(): ImageDao

    companion object {

        /**
         * Close the database connection
         * Used when we need to replace the database file during restore
         */
        fun closeConnection() {
            if (INSTANCE != null) {
                synchronized(this) {
                    INSTANCE?.close()
                    INSTANCE = null
                }
            }
        }

        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            if (INSTANCE == null) {
                synchronized(this) {
                    val documentsDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val dbDir = File(documentsDir, "notabledb")
                    if (!dbDir.exists()) {
                        dbDir.mkdirs()
                    }
                    val dbFile = File(dbDir, "app_database")

                    // Use Room to build the database
                    INSTANCE =
                        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
                            .allowMainThreadQueries() // Avoid in production
                            .addMigrations(
                                MIGRATION_16_17,
                                MIGRATION_17_18,
                                MIGRATION_22_23,
                                MIGRATION_29_30,
                                MIGRATION_31_32 // Add migration for PaperFormat
                            )
                            .build()
                }
            }
            return INSTANCE!!
        }
    }
}