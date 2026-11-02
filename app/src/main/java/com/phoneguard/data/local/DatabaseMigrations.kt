package com.phoneguard.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Миграции схемы Room, вынесенные из [AppDatabase].
 *
 * Причина выноса практическая: `AppDatabase` тянет `Room`, SQLCipher и AndroidKeyStore, поэтому
 * тест конфигурации миграций не собирался в офлайн-JVM и **не исполнялся** — «непрерывность
 * цепочки миграций» оставалась непроверенной. Здесь только объявления миграций и номер версии:
 * ни Room, ни Android, поэтому `AppDatabaseMigrationConfigTest` работает без Robolectric.
 *
 * SQL внутри `migrate` проверяется отдельно — `tools/migration_check.py` прогоняет его на
 * настоящем SQLite (там же ловятся ссылки на несуществующие таблицы и колонки).
 */
internal object DatabaseMigrations {

    /** Целевая версия схемы (должна совпадать с `version` в `@Database`). */
    const val LATEST_VERSION = 8

    /**
     * Все миграции по порядку.
     *
     * Единый список: и `AppDatabaseProvider`, и тест конфигурации берут его
     * отсюда, поэтому «забыли добавить новую миграцию в провайдер» больше
     * не проходит незамеченным (раньше список дублировался руками).
     */
    val ALL_MIGRATIONS: Array<Migration> by lazy {
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8
        )
    }

    val MIGRATION_1_2 = object : Migration(1, 2) {
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

    val MIGRATION_2_3 = object : Migration(2, 3) {
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

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_history_timestamp` ON `scan_history` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_blocked_log_timestamp` ON `blocked_log` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_firewall_logs_timestamp` ON `firewall_logs` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_blocked_numbers_phoneNumber` ON `blocked_numbers` (`phoneNumber`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_createdAt` ON `vault_items` (`createdAt`)")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
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

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'OTHER'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_category` ON `vault_items` (`category`)")
            db.execSQL("UPDATE `vault_items` SET `category` = 'IMAGE' WHERE `isImage` = 1")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `firewall_logs` ADD COLUMN `trafficDirection` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `intrusion_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `severity` TEXT NOT NULL,
                    `evidence` TEXT NOT NULL,
                    `uid` INTEGER,
                    `packageName` TEXT,
                    `ipAddress` TEXT,
                    `photoPath` TEXT,
                    `handled` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_intrusion_events_timestamp` ON `intrusion_events` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_intrusion_events_severity` ON `intrusion_events` (`severity`)")
        }
    }
}
