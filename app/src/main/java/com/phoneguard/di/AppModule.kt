package com.phoneguard.di

import android.content.Context
import com.phoneguard.data.local.SimSwapEventDao
import com.phoneguard.data.repository.SimSwapRepository
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.simswap.SimSwapDetector
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context): PreferencesManager {
        return PreferencesManager(context)
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSimSwapEventDao(database: com.phoneguard.data.local.AppDatabase): SimSwapEventDao = database.simSwapEventDao()

    @Provides
    @Singleton
    fun provideSimSwapDetector(
        @ApplicationContext context: Context,
        simSwapRepository: SimSwapRepository,
        preferencesManager: PreferencesManager,
        securityService: com.phoneguard.antitheft.SecurityService
    ): SimSwapDetector = SimSwapDetector(
        context,
        simSwapRepository,
        preferencesManager,
        securityService,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )
}
