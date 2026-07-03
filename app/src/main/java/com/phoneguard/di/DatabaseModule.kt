package com.phoneguard.di

import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.local.AppDatabaseProvider
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
    fun provideBlockedNumberDao(db: AppDatabase) = db.blockedNumberDao()

    @Provides
    fun provideBlockedLogDao(db: AppDatabase) = db.blockedLogDao()

    @Provides
    fun provideFirewallDao(db: AppDatabase) = db.firewallDao()

    @Provides
    fun provideVaultDao(db: AppDatabase) = db.vaultDao()

    @Provides
    fun provideScanHistoryDao(db: AppDatabase) = db.scanHistoryDao()
}
