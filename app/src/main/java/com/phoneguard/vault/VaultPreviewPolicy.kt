package com.phoneguard.vault

/**
 * Сколько расшифрованных копий сейфа может лежать в кэше предпросмотра и когда их удалять.
 *
 * Расшифровка нужна только для того, чтобы отдать файл внешнему просмотрщику (`FileProvider`
 * открывает `cacheDir/vault_preview`). Раньше при каждой расшифровке удалялись лишь копии старше
 * 10 минут, поэтому за один вечер просмотра в кэше накапливалось столько **открытым текстом**
 * лежащих секретов, сколько файлов пользователь открыл за эти 10 минут, — и все они оставались
 * там до следующей расшифровки. Если приложение в этот момент убить, копии переживали перезапуск.
 *
 * Правила (проверены в `VaultPreviewPolicyTest`):
 *  - просроченные (старше [PREVIEW_MAX_AGE_MS]) удаляются всегда;
 *  - всего копий не больше [MAX_PREVIEW_FILES], лишние удаляются от старых к новым;
 *  - только что созданная копия не удаляется никогда — её уже мог открыть просмотрщик;
 *  - копии моложе [PREVIEW_GRACE_MS] удаляются только при превышении лимита: у пользователя
 *    есть время переключиться между двумя картинками, но не время накопить десяток копий;
 *  - `lastModified == 0` значит «время неизвестно»: по возрасту такую копию не удаляем,
 *    но при переполнении лимита она первая (считать её новейшей было бы неверно).
 */
internal object VaultPreviewPolicy {

    /** Сколько расшифрованная копия живёт в кэше предпросмотра. */
    const val PREVIEW_MAX_AGE_MS = 10 * 60 * 1000L

    /** Минимальный возраст копии, которую можно удалить, не защищая лимитом. */
    const val PREVIEW_GRACE_MS = 60 * 1000L

    /** Сколько расшифрованных копий разрешено держать в кэше одновременно. */
    const val MAX_PREVIEW_FILES = 4

    /** Файл кэша: имя и время последнего изменения (в проде — `File.name`/`File.lastModified()`). */
    data class Entry(val name: String, val lastModified: Long)

    /**
     * Какие копии удалить.
     *
     * @param entries всё, что сейчас лежит в каталоге предпросмотра
     * @param now текущее время
     * @param keepName имя копии, которую трогать нельзя (только что расшифрованная), или `null`
     */
    fun toDelete(
        entries: List<Entry>,
        now: Long,
        keepName: String? = null
    ): List<Entry> {
        val candidates = entries.filter { it.name != keepName }
        if (candidates.isEmpty()) return emptyList()

        val expired = candidates.filter { isExpired(it, now) }
        val rest = candidates.filterNot { isExpired(it, now) }

        // Лимит считает и защищённую копию: она занимает одно из мест в кэше.
        val allowed = if (keepName == null) MAX_PREVIEW_FILES else MAX_PREVIEW_FILES - 1
        val overLimit = rest
            .sortedWith(compareByDescending<Entry> { it.lastModified }.thenBy { it.name })
            .drop(allowed)
            .filter { isPastGrace(it, now) }

        return expired + overLimit
    }

    private fun isExpired(entry: Entry, now: Long): Boolean =
        entry.lastModified > 0 && now - entry.lastModified >= PREVIEW_MAX_AGE_MS

    private fun isPastGrace(entry: Entry, now: Long): Boolean =
        entry.lastModified <= 0 || now - entry.lastModified >= PREVIEW_GRACE_MS
}
