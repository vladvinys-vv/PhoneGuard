package com.phoneguard.di

import com.phoneguard.BuildConfig
import com.phoneguard.threatintel.ThreatIntelClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Сетевой слой приложения.
 *
 * Единственный HTTP-клиент (OkHttp) используется threat-intel проверками.
 * Таймауты намеренно короткие: сетевая проверка не должна задерживать UI и
 * фоновые сканы. В release-сборке логирование запросов выключено полностью,
 * чтобы URL из SMS не попадали в logcat.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(ThreatIntelClient.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(ThreatIntelClient.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(ThreatIntelClient.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }
}
