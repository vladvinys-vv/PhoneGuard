package com.phoneguard.di

import com.phoneguard.data.shouldersurfer.ShoulderSurferController
import com.phoneguard.data.shouldersurfer.ShoulderSurferRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Биндинг интерфейса [ShoulderSurferController] на настоящую реализацию.
 *
 * Без него Hilt не сможет создать `ShoulderSurferUseCase`: у интерфейса нет `@Inject`-конструктора,
 * и граф разрывается на этапе генерации кода, а не компиляции — ровно та же ошибка, что была
 * когда-то у `IntrusionResponseExecutor` (см. `IntrusionModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShoulderSurferModule {

    @Binds
    @Singleton
    abstract fun bindShoulderSurferController(
        impl: ShoulderSurferRepository
    ): ShoulderSurferController
}
