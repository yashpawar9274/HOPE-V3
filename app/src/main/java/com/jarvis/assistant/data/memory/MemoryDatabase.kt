package com.jarvis.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryEntity::class,
        MemorySyncJob::class,
        CoreBehaviorRule::class,
        CoreBehaviorHistory::class,
        CoreBehaviorDeletionEntity::class,
    ],
    version = 4,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun memorySyncDao(): MemorySyncDao
    abstract fun coreBehaviorDao(): CoreBehaviorDao

    companion object {
        private const val DATABASE_NAME = "jarvis_memory.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `confidence` REAL NOT NULL DEFAULT 0.5"
                )
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `verificationStatus` TEXT NOT NULL DEFAULT 'needs_confirmation'"
                )
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `sourceType` TEXT NOT NULL DEFAULT 'legacy_unverified'"
                )
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `sourceConversationId` TEXT")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `sourceTurnId` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_sync_outbox` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `jobId` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `operation` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `matchText` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL DEFAULT 0,
                        `lastError` TEXT,
                        `permanentFailure` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_sync_outbox_jobId` " +
                        "ON `memory_sync_outbox` (`jobId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_sync_outbox_ownerUid_id` " +
                        "ON `memory_sync_outbox` (`ownerUid`, `id`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `core_behavior_rules` (
                        `instructionId` TEXT NOT NULL,
                        `instructionText` TEXT NOT NULL,
                        `normalizedRule` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `activeStatus` INTEGER NOT NULL,
                        `sourceConversation` TEXT,
                        `confirmationStatus` TEXT NOT NULL,
                        PRIMARY KEY(`instructionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_core_behavior_rules_activeStatus` ON `core_behavior_rules` (`activeStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_core_behavior_rules_normalizedRule` ON `core_behavior_rules` (`normalizedRule`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `core_behavior_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `instructionId` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `instructionSnapshot` TEXT NOT NULL,
                        `surface` TEXT,
                        `occurredAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_core_behavior_history_instructionId` ON `core_behavior_history` (`instructionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_core_behavior_history_occurredAt` ON `core_behavior_history` (`occurredAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_core_behavior_history_eventId` ON `core_behavior_history` (`eventId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `core_behavior_deletions` (
                        `instructionId` TEXT NOT NULL,
                        `instructionSnapshot` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`instructionId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `core_behavior_rules_new` (
                        `ownerUid` TEXT NOT NULL,
                        `instructionId` TEXT NOT NULL,
                        `instructionText` TEXT NOT NULL,
                        `normalizedRule` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `activeStatus` INTEGER NOT NULL,
                        `sourceConversation` TEXT,
                        `confirmationStatus` TEXT NOT NULL,
                        PRIMARY KEY(`ownerUid`, `instructionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `core_behavior_rules_new` (
                        `ownerUid`, `instructionId`, `instructionText`, `normalizedRule`,
                        `category`, `scope`, `priority`, `createdAt`, `updatedAt`,
                        `activeStatus`, `sourceConversation`, `confirmationStatus`
                    )
                    SELECT '__legacy_unassigned__', `instructionId`, `instructionText`,
                        `normalizedRule`, `category`, `scope`, `priority`, `createdAt`,
                        `updatedAt`, `activeStatus`, `sourceConversation`, `confirmationStatus`
                    FROM `core_behavior_rules`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `core_behavior_rules`")
                db.execSQL("ALTER TABLE `core_behavior_rules_new` RENAME TO `core_behavior_rules`")
                db.execSQL(
                    "CREATE INDEX `index_core_behavior_rules_ownerUid_activeStatus` " +
                        "ON `core_behavior_rules` (`ownerUid`, `activeStatus`)"
                )
                db.execSQL(
                    "CREATE INDEX `index_core_behavior_rules_ownerUid_normalizedRule` " +
                        "ON `core_behavior_rules` (`ownerUid`, `normalizedRule`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE `core_behavior_history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `instructionId` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `instructionSnapshot` TEXT NOT NULL,
                        `surface` TEXT,
                        `occurredAt` INTEGER NOT NULL,
                        `ownerUid` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `core_behavior_history_new` (
                        `id`, `eventId`, `instructionId`, `action`, `instructionSnapshot`,
                        `surface`, `occurredAt`, `ownerUid`
                    )
                    SELECT `id`, `eventId`, `instructionId`, `action`, `instructionSnapshot`,
                        `surface`, `occurredAt`, '__legacy_unassigned__'
                    FROM `core_behavior_history`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `core_behavior_history`")
                db.execSQL("ALTER TABLE `core_behavior_history_new` RENAME TO `core_behavior_history`")
                db.execSQL(
                    "CREATE INDEX `index_core_behavior_history_ownerUid_instructionId` " +
                        "ON `core_behavior_history` (`ownerUid`, `instructionId`)"
                )
                db.execSQL(
                    "CREATE INDEX `index_core_behavior_history_ownerUid_occurredAt` " +
                        "ON `core_behavior_history` (`ownerUid`, `occurredAt`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_core_behavior_history_ownerUid_eventId` " +
                        "ON `core_behavior_history` (`ownerUid`, `eventId`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE `core_behavior_deletions_new` (
                        `instructionId` TEXT NOT NULL,
                        `instructionSnapshot` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        PRIMARY KEY(`ownerUid`, `instructionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `core_behavior_deletions_new` (
                        `instructionId`, `instructionSnapshot`, `deletedAt`, `ownerUid`
                    )
                    SELECT `instructionId`, `instructionSnapshot`, `deletedAt`, '__legacy_unassigned__'
                    FROM `core_behavior_deletions`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `core_behavior_deletions`")
                db.execSQL("ALTER TABLE `core_behavior_deletions_new` RENAME TO `core_behavior_deletions`")
            }
        }

        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
