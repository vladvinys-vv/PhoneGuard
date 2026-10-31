package com.phoneguard.data.repository

import com.phoneguard.data.local.AppDatabase
import com.phoneguard.model.SimSwapEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий истории SIM-swap событий.
 *
 * Выделен из `AppDatabase`, чтобы детектор SIM-swap и UI не зависели от DAO напрямую
 * (и чтобы логика «последнее неподтверждённое событие» была в одном месте).
 */
@Singleton
class SimSwapRepository @Inject constructor(
    private val appDatabase: AppDatabase
) {

    private val dao get() = appDatabase.simSwapEventDao()

    fun observeEvents(): Flow<List<SimSwapEvent>> = dao.observeAll()

    suspend fun latest(): SimSwapEvent? = withContext(Dispatchers.IO) { dao.latest() }

    /** Последнее событие, которое пользователь ещё не подтвердил. */
    suspend fun latestUnconfirmed(): SimSwapEvent? = latest()?.takeIf { !it.isConfirmed }

    suspend fun record(event: SimSwapEvent): Long = withContext(Dispatchers.IO) { dao.insert(event) }

    suspend fun confirm(id: Long) = withContext(Dispatchers.IO) { dao.markConfirmed(id) }

    suspend fun markAttackSuspected(id: Long) = withContext(Dispatchers.IO) { dao.markAttackSuspected(id) }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { dao.deleteAll() }
}
