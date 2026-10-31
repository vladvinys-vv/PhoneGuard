package com.phoneguard.fullscan.checks

import android.content.Context
import androidx.annotation.StringRes

/**
 * Один фрагмент диагностики проверки, ещё не переведённый в текст.
 *
 * Зачем: часть проверок (`SecurityPatchCheck`) специально сделана «чистой» —
 * без `Context`, чтобы её логику можно было тестировать обычными JVM-тестами.
 * Раньше такие проверки возвращали готовые русские строки: в английской и
 * немецкой локали диагностика оставалась русской, а тесты были вынуждены
 * сверять текст («Внимание», «в будущем»), то есть ломались от любой правки
 * формулировки или перевода.
 *
 * Теперь проверка возвращает ресурс + аргументы, а текст собирается там, где
 * есть `Context` ([resolve]). Тесты сверяют `textRes`, а не слова.
 */
data class CheckDetail(
    @StringRes val textRes: Int,
    val args: List<Any> = emptyList()
) {
    fun resolve(context: Context): String =
        if (args.isEmpty()) {
            context.getString(textRes)
        } else {
            context.getString(textRes, *args.toTypedArray())
        }
}

/** Локализует список фрагментов диагностики. */
fun List<CheckDetail>.resolve(context: Context): List<String> = map { it.resolve(context) }
