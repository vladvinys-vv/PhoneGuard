package com.phoneguard.di

import com.phoneguard.antitheft.AntiTheftSecurityServiceImpl
import com.phoneguard.antitheft.SecurityService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindSecurityService(impl: AntiTheftSecurityServiceImpl): SecurityService
}
