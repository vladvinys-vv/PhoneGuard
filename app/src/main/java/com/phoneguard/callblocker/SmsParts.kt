package com.phoneguard.callblocker

/**
 * Склейка многочастных SMS.
 *
 * Длинное сообщение приходит несколькими PDU с одинаковым адресом отправителя.
 * Если проверять каждую часть отдельно, спам-ссылка может оказаться во второй
 * части — и такая SMS прошла бы фильтр. Функция чистая и покрыта JVM-тестом
 * (`SmsPartsTest`), потому что `BroadcastReceiver` на JVM не проверить.
 */
internal object SmsParts {

    /**
     * @param parts пары «адрес отправителя, текст части» в порядке доставки
     * @return текст по каждому отправителю (порядок первого появления сохранён);
     *         части без адреса отбрасываются — по ним нельзя принять решение
     */
    fun merge(parts: List<Pair<String?, String>>): LinkedHashMap<String, String> {
        val merged = LinkedHashMap<String, String>()
        for ((rawAddress, text) in parts) {
            val sender = rawAddress?.trim().orEmpty()
            if (sender.isEmpty()) continue
            merged[sender] = (merged[sender] ?: "") + text
        }
        return merged
    }
}
