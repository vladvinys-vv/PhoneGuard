package com.phoneguard.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.phoneguard.model.FirewallRule
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.VaultItem
import com.phoneguard.model.ScanHistory
import com.phoneguard.util.DatabaseKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

@TypeConverters(Converters::class)
@Database(
    entities = [
        BlockedNumber::class,
        BlockedLog::class,
        FirewallRule::class,
        FirewallLog::class,
        VaultItem::class,
        com.phoneguard.model.SimSwapEvent::class,
        ScanHistory::class,
        com.phoneguard.model.SpamNumber::class,
        IntrusionEventEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun blockedLogDao(): BlockedLogDao
    abstract fun firewallDao(): FirewallDao
    abstract fun vaultDao(): VaultDao
    abstract fun simSwapEventDao(): SimSwapEventDao
    abstract fun scanHistoryDao(): ScanHistoryDao
    abstract fun spamNumberDao(): SpamNumberDao
    abstract fun intrusionEventDao(): IntrusionEventDao

    companion object {
        /** Целевая версия схемы (должна совпадать с `version` в `@Database`). */
        const val LATEST_VERSION = DatabaseMigrations.LATEST_VERSION

        /**
         * Все миграции по порядку — см. [DatabaseMigrations].
         *
         * Единый список: и `AppDatabaseProvider`, и тест конфигурации берут его отсюда,
         * поэтому «забыли добавить новую миграцию в провайдер» больше не проходит
         * незамеченным (раньше список дублировался руками).
         */
        val ALL_MIGRATIONS: Array<Migration> get() = DatabaseMigrations.ALL_MIGRATIONS
    }
}

private const val TAG = "AppDatabase"

@Singleton
class AppDatabaseProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val databaseKeyManager: DatabaseKeyManager
) {
    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        DatabaseKeyManager.DATABASE_NAME
    )
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .openHelperFactory { config ->
            val passphrase = try {
                databaseKeyManager.getDatabasePassphrase()
            } catch (e: DatabaseKeyManager.DatabaseKeyException) {
                // Ключ AndroidKeyStore утерян (восстановление бэкапа на другом
                // устройстве, сброс keystore) — иначе приложение не запускалось бы
                // вообще и у пользователя не было бы даже экрана с объяснением.
                Log.w(TAG, "Ключ БД недоступен — пересоздаём локальную базу", e)
                databaseKeyManager.resetWithNewPassphrase()
            }
            SupportOpenHelperFactory(passphrase).create(config)
        }
        .build()
}
