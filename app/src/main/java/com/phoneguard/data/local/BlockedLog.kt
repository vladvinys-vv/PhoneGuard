package com.phoneguard.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_log", indices = [Index(value = ["timestamp"])])
data class BlockedLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val name: String? = null,
    val isSms: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Почему звонок/SMS был заблокирован (`CallBlockDecision.BlockReason.name`).
     *
     * `null` — запись сделана до версии схемы 9 либо причина неизвестна. Строкой, а не
     * enum-конвертером: значение читают журнал и генератор жалобы, а неизвестное имя из
     * будущей версии не должно ронять чтение таблицы.
     */
    val reason: String? = null
)
