package com.phoneguard.di

import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.local.AppDatabaseProvider
import com.phoneguard.data.local.BlockedLogDao
import com.phoneguard.data.local.BlockedNumberDao
import com.phoneguard.data.local.FirewallDao
import com.phoneguard.data.local.ScanHistoryDao
import com.phoneguard.data.local.SpamNumberDao
import com.phoneguard.data.local.VaultDao
import com.phoneguard.data.local.IntrusionEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(provider: AppDatabaseProvider): AppDatabase = provider.database

    @Provides
    fun provideBlockedNumberDao(db: AppDatabase): BlockedNumberDao = db.blockedNumberDao()

    @Provides
    fun provideBlockedLogDao(db: AppDatabase): BlockedLogDao = db.blockedLogDao()

    @Provides
    fun provideFirewallDao(db: AppDatabase): FirewallDao = db.firewallDao()

    @Provides
    fun provideVaultDao(db: AppDatabase): VaultDao = db.vaultDao()

    @Provides
    fun provideScanHistoryDao(db: AppDatabase): ScanHistoryDao = db.scanHistoryDao()

    @Provides
    fun provideSpamNumberDao(db: AppDatabase): SpamNumberDao = db.spamNumberDao()

    @Provides
    fun provideIntrusionEventDao(db: AppDatabase): IntrusionEventDao = db.intrusionEventDao()
}
