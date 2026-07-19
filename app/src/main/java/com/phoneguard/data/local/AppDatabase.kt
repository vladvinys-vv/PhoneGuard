package com.phoneguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.phoneguard.model.BlockedNumber
import com.phoneguard.model.BlockedLog
import com.phoneguard.model.FirewallRule
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.VaultItem
import com.phoneguard.model.SimSwapEvent
import com.phoneguard.model.ScanHistory
import com.phoneguard.model.VaultItemCategory
import com.phoneguard.model.FirewallLog
import com.phoneguard.util.DatabaseKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import net.sqlcipher.database.SupportFactory
import javax.inject.Inject
import javax.inject.Singleton

@Database(
    entities = [
        BlockedNumber::class,
        BlockedLog::class,
        FirewallRule::class,
        FirewallLog::class,
        VaultItem::class,
        com.phoneguard.model.SimSwapEvent::class,
        ScanHistory::class,
        com.phoneguard.model.SpamNumber::class
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = []
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun blockedLogDao(): BlockedLogDao
    abstract fun firewallDao(): FirewallDao
    abstract fun vaultDao(): VaultDao
    abstract fun simSwapEventDao(): com.phoneguard.model.SimSwapEventDao
    abstract fun scanHistoryDao(): ScanHistoryDao

    companion object {
        @TypeConverter
        @JvmStatic
        fun fromVaultItemCategory(value: VaultItemCategory): String = value.name

        @TypeConverter
        @JvmStatic
        fun toVaultItemCategory(value: String): VaultItemCategory =
            VaultItemCategory.valueOf(value.ifEmpty { VaultItemCategory.OTHER.name })

        @TypeConverter
        @JvmStatic
        fun fromTrafficDirection(value: FirewallLog.TrafficDirection): String = value.name

        @TypeConverter
        @JvmStatic
        fun toTrafficDirection(value: String): FirewallLog.TrafficDirection =
            FirewallLog.TrafficDirection.valueOf(value.ifEmpty { FirewallLog.TrafficDirection.UNKNOWN.name })
    }
}

@Singleton
class AppDatabaseProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val databaseKeyManager: DatabaseKeyManager
) {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `firewall_rules` (
                    `packageName` TEXT NOT NULL,
                    `appName` TEXT NOT NULL,
                    `blockWifi` INTEGER NOT NULL,
                    `blockMobile` INTEGER NOT NULL,
                    `blockAll` INTEGER NOT NULL,
                    `blockVpn` INTEGER NOT NULL,
                    `blockBackground` INTEGER NOT NULL,
                    `blockedDomains` TEXT NOT NULL,
                    `blockedIps` TEXT NOT NULL,
                    `allowByDefault` INTEGER NOT NULL,
                    `allowWifiOnly` INTEGER NOT NULL,
                    `allowMobileOnly` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `firewall_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `appName` TEXT NOT NULL,
                    `ipAddress` TEXT,
                    `domainName` TEXT,
                    `timestamp` INTEGER NOT NULL,
                    `connectionType` TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sim_swap_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `oldImsi` TEXT,
                    `newImsi` TEXT,
                    `latitude` REAL,
                    `longitude` REAL,
                    `addressString` TEXT,
                    `isConfirmed` INTEGER NOT NULL,
                    `isAttackSuspected` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `scan_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `riskScore` INTEGER NOT NULL,
                    `issuesFound` INTEGER NOT NULL,
                    `reportJson` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vault_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `fileSize` INTEGER NOT NULL,
                    `mimeType` TEXT NOT NULL,
                    `encryptionKeyAlias` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `isImage` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_history_timestamp` ON `scan_history` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_blocked_log_timestamp` ON `blocked_log` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_firewall_logs_timestamp` ON `firewall_logs` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_blocked_numbers_phoneNumber` ON `blocked_numbers` (`phoneNumber`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_createdAt` ON `vault_items` (`createdAt`)")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `spam_numbers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `phoneNumber` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `addedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spam_numbers_phoneNumber` ON `spam_numbers` (`phoneNumber`)")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'OTHER'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_category` ON `vault_items` (`category`)")
            db.execSQL("UPDATE `vault_items` SET `category` = 'IMAGE' WHERE `isImage` = 1")
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `firewall_logs` ADD COLUMN `trafficDirection` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        }
    }

    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "phoneguard_db"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .openHelperFactory { config ->
            val passphrase = databaseKeyManager.getDatabasePassphrase()
            val factory = SupportFactory(passphrase)
            SupportSQLiteOpenHelper.Configuration.builder(config.context)
                .name(config.name)
                .callback(config.callback)
                .build()
                .let { factory.create(it) }
        }
        .build()
}
