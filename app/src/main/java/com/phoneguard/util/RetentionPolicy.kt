package com.phoneguard.util

import java.io.File

/**
 * Правила хранения данных: что считается устаревшим и какие файлы приложению разрешено удалять.
 *
 * Вынесено из [DataRetentionManager] по двум причинам. Первая — тестируемость: менеджер тянет
 * `Context`, `AppDatabase` и корутины, поэтому его тест на MockK в JVM не исполнялся ни разу,
 * а именно здесь лежит самое опасное место — удаление файлов по пути из базы. Вторая —
 * наглядность: «удалять можно только внутри приватных каталогов приложения» теперь одна
 * функция с тестами, а не три проверки внутри метода.
 */
internal object RetentionPolicy {

    /** Срок хранения журналов и улик вторжений. */
    const val RETENTION_DAYS = 90L

    /**
     * Срок в миллисекундах.
     *
     * Все множители `Long`: иначе промежуточное `24 * 60 * 60 * 1000` (86 400 000) ещё влезает
     * в `Int`, а любое увеличение срока — уже нет (переполнение Int даёт отрицательный cutoff,
     * и «старым» становится всё подряд).
     */
    const val RETENTION_MS = RETENTION_DAYS * 24L * 60L * 60L * 1000L

    /** Граница «старше этого — удалить». */
    fun cutoff(now: Long): Long = now - RETENTION_MS

    /**
     * Файл старше границы.
     *
     * `lastModified == 0` (и любое неположительное значение) означает «время неизвестно» —
     * такие файлы не удаляются: `0` возвращает `File.lastModified()` при ошибке чтения.
     */
    fun isStale(lastModified: Long, cutoff: Long): Boolean = lastModified in 1 until cutoff

    /**
     * Лежит ли файл внутри приватных каталогов приложения.
     *
     * `photoPath` приходит из БД, поэтому удалять по нему произвольный путь нельзя: строка
     * вида `/sdcard/DCIM/...` или `../../databases/phoneguard_db` не должна становиться
     * удалённой фотографией. Сравнение идёт по каноническим путям с обязательным разделителем:
     * без него `/data/.../files-evil` считался бы лежащим внутри `/data/.../files`.
     */
    fun isOwnedByApp(target: File, roots: List<File?>): Boolean {
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return false
        return roots.filterNotNull().any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            canonical.path.startsWith(rootPath + File.separator)
        }
    }
}
