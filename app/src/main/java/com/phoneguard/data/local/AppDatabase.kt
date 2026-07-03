package com.phoneguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.phoneguard.model.BlockedNumber
import com.phoneguard.model.BlockedLog
import com.phoneguard.model.FirewallRule
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.VaultItem
import com.phoneguard.model.SimSwapEvent
import com.phoneguard.model.ScanHistory
import dagger.hilt.android.qualifiers.ApplicationContext
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
    version = 3,
    exportSchema = false
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
    @ApplicationContext context: Context
) {
    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "phoneguard_db"
    )
        .fallbackToDestructiveMigration()
        .build()
}
