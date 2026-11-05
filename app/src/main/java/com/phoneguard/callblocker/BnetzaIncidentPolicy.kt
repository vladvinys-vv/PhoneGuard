package com.phoneguard.callblocker

import com.phoneguard.data.local.BlockedLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Из журнала блокировок — в жалобу Bundesnetzagentur.
 *
 * Класс жалобы (`BnetzaComplaintReport`) появился раньше, но **не использовался нигде**:
 * данные для него никто не собирал, а причина блокировки в журнале не сохранялась вовсе.
 * Здесь правила отбора и категоризации; жалоба на пустой список инцидентов не формируется.
 *
 * Почему не каждый заблокированный звонок становится жалобой:
 *
 *  * **международный номер сам по себе — не нарушение** (§ 67 TKG — про злоупотребление
 *    нумерацией, а не про зарубежные звонки), поэтому «просто иностранный» номер
 *    в жалобу не попадает;
 *  * **причина неизвестна** (записи до схемы 9) — категорию нарушения указать нечем,
 *    а придумывать её за пользователя нельзя: жалоба официальная;
 *  * SMS не попадают в эту жалобу — BNetzA принимает обращения по телефонным вызовам,
 *    форма и заголовок жалобы говорят именно об Anrufe.
 *
 * Объект публичный, а не `internal`: `BnetzaReportManager.Result.NotReady` отдаёт наружу
 * список [Issue], и внутренний тип в публичной сигнатуре — ошибка компиляции. Класс при
 * этом всё равно не является частью UI-контракта: ViewModel переводит [Issue] в свои
 * объекты и наружу их не отдаёт.
 */
object BnetzaIncidentPolicy {

    /** Почему жалобу сформировать нельзя. */
    enum class Issue {
        /** Нет ни одного подходящего случая за выбранный период. */
        NO_INCIDENTS,

        /** В записи пустой номер — отправлять такое в BNetzA бессмысленно. */
        BLANK_CALLER_NUMBER,

        /** Дата не в формате `YYYY-MM-DD HH:mm:ss`. */
        INVALID_TIMESTAMP,

        /** Не указано, от кого жалоба: анонимное обращение не рассматривается. */
        REPORTER_MISSING
    }

    /** Начало и конец периода в миллисекундах, включительно. */
    data class Window(val fromMillis: Long, val toMillis: Long) {
        init {
            require(fromMillis <= toMillis) { "начало периода позже конца" }
        }

        fun contains(timestamp: Long): Boolean = timestamp in fromMillis..toMillis
    }

    /** Категория нарушения по причине блокировки; `null` — жаловаться не на что. */
    fun violationFor(reason: String?): BnetzaViolationType? = when (reason) {
        CallBlockDecision.BlockReason.BLACKLISTED.name -> BnetzaViolationType.UNERLAUBTE_WERBUNG
        CallBlockDecision.BlockReason.SPAM_DATABASE.name -> BnetzaViolationType.UNERLAUBTE_WERBUNG
        // Номер не передан оператором или передан в виде, который не приводится к E.164:
        // это уже злоупотребление нумерацией, а не «частный звонок без определителя».
        CallBlockDecision.BlockReason.HIDDEN_NUMBER.name -> BnetzaViolationType.CALLER_ID_SPOOFING
        CallBlockDecision.BlockReason.UNPARSEABLE_NUMBER.name ->
            BnetzaViolationType.CALLER_ID_SPOOFING
        // «Просто иностранный номер» — не нарушение; причину придумывать нельзя.
        CallBlockDecision.BlockReason.INTERNATIONAL.name -> null
        else -> null
    }

    /** Формат даты, который требует официальная форма. */
    const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

    private val TIMESTAMP_REGEX = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

    /** Форматирование по умолчанию: локальное время устройства, немецкая локаль. */
    fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.GERMANY).format(Date(timestamp))

    /**
     * Отбирает инциденты для жалобы.
     *
     * @param formatTimestamp вынесен параметром, чтобы тест не зависел от часового пояса.
     */
    fun selectIncidents(
        logs: List<BlockedLog>,
        window: Window,
        formatTimestamp: (Long) -> String = ::formatTimestamp
    ): List<SpamCallIncident> = logs
        .asSequence()
        .filter { !it.isSms }
        .filter { window.contains(it.timestamp) }
        .sortedBy { it.timestamp }
        .mapNotNull { log ->
            val violation = violationFor(log.reason) ?: return@mapNotNull null
            SpamCallIncident(
                callerNumber = log.phoneNumber,
                callTimestamp = formatTimestamp(log.timestamp),
                violationType = violation,
                // Имя из журнала — это метКА спам-базы или «скрытый номер», а не
                // рекламируемый продукт; в официальную форму его не подставляем.
                advertisedProductOrCompany = null,
                wasAnswered = false,
                callDurationSeconds = 0,
                notes = null
            )
        }
        .toList()

    /** Проверка жалобы перед сохранением и отправкой. */
    fun validate(
        incidents: List<SpamCallIncident>,
        reporterName: String?,
        reporterPhone: String?,
        reporterEmail: String?
    ): List<Issue> = buildList {
        if (incidents.isEmpty()) add(Issue.NO_INCIDENTS)
        if (incidents.any { it.callerNumber.isBlank() }) add(Issue.BLANK_CALLER_NUMBER)
        if (incidents.any { !TIMESTAMP_REGEX.matches(it.callTimestamp) }) add(Issue.INVALID_TIMESTAMP)
        if (reporterName.isNullOrBlank() && reporterPhone.isNullOrBlank() && reporterEmail.isNullOrBlank()) {
            add(Issue.REPORTER_MISSING)
        }
    }
}
