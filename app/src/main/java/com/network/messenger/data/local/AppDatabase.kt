package com.network.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MeshNetworkEntity::class,
        NetworkMemberEntity::class,
        MessageEntity::class,
        BlockedUserEntity::class,
        MutedUserEntity::class,
        UserReportEntity::class,
        PendingPacketEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao
    abstract fun blockedUserDao(): BlockedUserDao
    abstract fun mutedUserDao(): MutedUserDao
    abstract fun userReportDao(): UserReportDao
    abstract fun messageDao(): MessageDao

    companion object {
        /**
         * Lossless Room migration from v1 (1.0.0) to v2 (1.1.0):
         * - Adds network mute flag
         * - Adds contact key verification & key-change tracking columns
         * - Adds offline queue retry/backoff columns
         * - Creates muted_users and user_reports tables
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mesh_networks ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE network_members ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE network_members ADD COLUMN verifiedAtMs INTEGER")
                db.execSQL("ALTER TABLE network_members ADD COLUMN keyChangedWarning INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE network_members ADD COLUMN previousKeyFingerprint TEXT")
                db.execSQL("ALTER TABLE pending_packets ADD COLUMN nextRetryAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_packets ADD COLUMN lastError TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS muted_users (
                        userId TEXT NOT NULL PRIMARY KEY,
                        mutedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_reports (
                        reportId TEXT NOT NULL PRIMARY KEY,
                        reportedUserId TEXT NOT NULL,
                        networkId TEXT,
                        reason TEXT NOT NULL,
                        sentToOwner INTEGER NOT NULL,
                        createdAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
