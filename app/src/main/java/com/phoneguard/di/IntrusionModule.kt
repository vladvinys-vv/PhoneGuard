package com.phoneguard.di

import com.phoneguard.intrusion.AndroidIntrusionResponseExecutor
import com.phoneguard.intrusion.HoneypotManager
import com.phoneguard.intrusion.IntrusionDetector
import com.phoneguard.intrusion.IntrusionResponseExecutor
import com.phoneguard.intrusion.IntrusionResponseOrchestrator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Раньше граф зависимостей Hilt был разорван: `IntrusionRepository` запрашивал
 * `IntrusionDetector`, `IntrusionResponseOrchestrator`, `HoneypotManager` и интерфейс
 * `IntrusionResponseExecutor`, для которых не было ни `@Inject`-конструктора
 * (у первых трёх), ни `@Binds` (у интерфейса). Приложение компилировалось,
 * но Hilt падал на этапе генерации кода — теперь биндинги объявлены явно.
 */
@Module
@InstallIn(SingletonComponent::class)
object IntrusionModule {

    @Provides
    @Singleton
    fun provideIntrusionDetector(): IntrusionDetector = IntrusionDetector()

    @Provides
    @Singleton
    fun provideIntrusionResponseOrchestrator(): IntrusionResponseOrchestrator =
        IntrusionResponseOrchestrator()

    @Provides
    @Singleton
    fun provideHoneypotManager(): HoneypotManager = HoneypotManager()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IntrusionBindingsModule {

    @Binds
    @Singleton
    abstract fun bindIntrusionResponseExecutor(
        impl: AndroidIntrusionResponseExecutor
    ): IntrusionResponseExecutor

}
