package com.phoneguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        ScanHistory::class
    ],
    version = 4,
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

    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "phoneguard_db"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
