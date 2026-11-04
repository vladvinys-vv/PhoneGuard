package com.phoneguard.fullscan.checks

import com.phoneguard.util.NamePatternMatcher
import java.util.Locale

/**
 * Чистая эвристика фишинга в SMS — без Android, корутин и сети.
 *
 * Вынесена из [PhishingUrlChecker] отдельно, чтобы её можно было прогонять юнит-тестами без
 * `Context` и `ThreatIntelRepository`: раньше логика жила в классе с `contentResolver` и
 * OkHttp-репозиторием, поэтому ни один тест её фактически не исполнял.
 *
 * Правило: сообщение подозрительно, если в нём есть **и** ссылка, **и** один из признаков атаки —
 * «давящее» слово («срочно», «заблокирован», «подтвердите», …) либо имя хоста со смешанными
 * алфавитами (кириллица + латиница в одной метке — так выглядит омоглиф-подмена). Ни одного
 * признака без ссылки недостаточно: обычные сообщения не должны попадать в находки.
 *
 * Что было сломано и почему это важно:
 *  * **Обфускация ссылок не распознавалась.** `sberbank-online[.]ru`, `hxxp://…`, `sberbank . ru`
 *    — так пишут в половине фишинговых SMS именно для обхода фильтров. Метка `[` не проходила
 *    проверку символов, токен отбрасывался — «ссылки нет», сообщение чистое.
 *  * **Новые доменные зоны не считались ссылкой.** Список зон был перечислен руками и не содержал
 *    ни `.icu`, ни `.app`, ни `.link`, ни кириллических. Голый домен в такой зоне не находился.
 *  * **Ключевые слова искались подстрокой** — `код` находился в «кодекс», `карта` в «картон»,
 *    `verify` в «verifying». Сравнение переведено на слова (общий [NamePatternMatcher]).
 */
internal object PhishingHeuristics {

    /**
     * Короткие и неоднозначные слова: совпадение только целым словом.
     *
     * «код» подстрокой находится в «кодексе», поэтому словоформы перечислены явно.
     */
    private val EXACT_WORDS = setOf(
        "код", "кода", "кодом", "коды",
        "карта", "карты", "карту", "картой", "карте", "карт",
        "пароль", "пароля", "паролем",
        "банк", "банка", "банке", "банку", "банком",
        "счёт", "счет", "счёта", "счета", "ссылка", "ссылке", "ссылку"
    )

    /**
     * Основы длинных слов: совпадение с начала слова.
     *
     * «подтвер» покрывает «подтвердите/подтверждено/подтверждение», «перейд» — «перейдите/
     * перейти». Основы короче пяти символов сюда не попадают: у них слишком много соседей.
     */
    private val STEMS = listOf(
        "перейд", "получ", "выигр", "подтвер", "срочн", "заблокиров", "подозрит",
        "оплат", "верифи", "verif", "bestätig", "sperr", "gewinn", "spende", "konto"
    )

    /** Фразы: слова подряд, разделители не важны. */
    private val PHRASES = listOf("click here", "jetzt prüfen")

    /** Явные ссылки: схема, `www.`, сокращатели. Квантификаторы простые — без вложенности. */
    private val EXPLICIT_LINK = Regex(
        """(?:https?://|www\.)[^\s<>"']+|(?:bit\.ly|t\.me|tinyurl\.com|goo\.gl)/[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Расширения файлов, которые похожи на домен верхнего уровня.
     *
     * `photo.jpg` — это файл, а не сайт: без этого списка любое имя файла стало бы «ссылкой».
     */
    private val FILE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp",
        "mp3", "mp4", "mov", "avi", "mkv", "wav", "ogg",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv",
        "apk", "zip", "rar", "7z", "tar", "gz", "iso", "bin", "exe", "dmg",
        "html", "htm", "json", "xml", "js", "css", "php", "ini", "log"
    )

    /**
     * Замаскированные точки и схемы: `[.]`, `(.)`, `{.}`, `[dot]`, `(dot)`, `hxxp`.
     *
     * Заменяются перед поиском ссылок; в исходном тексте сообщения они не трогаются.
     */
    private val OBFUSCATED_DOT = Regex(
        """\s*(?:\[\s*\.\s*]|\(\s*\.\s*\)|\{\s*\.\s*\}|\[\s*dot\s*]|\(\s*dot\s*\)|\{\s*dot\s*\})\s*""",
        RegexOption.IGNORE_CASE
    )

    /** `hxxp://` и `hxxps://` — «безопасное» написание ссылки, которым делятся в чатах и спаме. */
    private val OBFUSCATED_SCHEME = Regex("""hxxp(s?)://""", RegexOption.IGNORE_CASE)

    /** Точка, обвешанная пробелами: «sberbank . ru». */
    private val SPACED_DOT = Regex("""(?<=[\p{L}\p{N}])\s+\.\s+(?=[\p{L}])""")

    /** Символы, которыми «обвешивают» ссылки в тексте: `(http://x.com),` и т.п. */
    private const val TRIM_CHARS = "\"'`,;)]}><[{([.!?:\""

    /** Локальная эвристика: ссылка + «давящее» слово или омоглиф-хост. */
    fun isSuspicious(body: String): Boolean {
        val links = extractLinks(body)
        if (links.isEmpty()) return false
        return hasPhishingKeyword(body) || links.any { hasMixedAlphabetHost(it) }
    }

    /** Есть ли в тексте «давящее» слово (сравнение по словам, а не подстрокой). */
    fun hasPhishingKeyword(body: String): Boolean {
        val words = NamePatternMatcher.tokens(deobfuscate(body))
        if (words.isEmpty()) return false
        if (words.any { word -> word in EXACT_WORDS }) return true
        if (words.any { word -> STEMS.any { stem -> word.startsWith(stem) } }) return true
        return PHRASES.any { phrase ->
            val needle = NamePatternMatcher.tokens(phrase).joinToString(separator = "")
            needle.isNotEmpty() && words.indices.any { start ->
                joinedFrom(words, start).startsWith(needle)
            }
        }
    }

    /**
     * Имя хоста со смешанными алфавитами: в одной метке есть и кириллица, и латиница.
     *
     * `аpple.com` (первая «а» — кириллическая) — классическая подмена; легитимный кириллический
     * домен (`яндекс.рф`) целиком кириллический и под правило не попадает.
     */
    fun hasMixedAlphabetHost(link: String): Boolean {
        val host = link
            .replace(OBFUSCATED_SCHEME, "http$1://")
            .substringAfter("://", link)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore('@')
        return host.split('.').any { label -> label.isMixedAlphabet() }
    }

    /**
     * Достаёт ссылки из текста сообщения: явные (схема, `www.`, сокращатели) и голые домены.
     *
     * Разбор идёт по словам и простыми регулярками: прежняя регулярка с вложенным
     * квантификатором (`(?:\.[\w-]+)*`) на тексте вида «a.a.a.a…» роняла проверку
     * `StackOverflowError` — то есть одно входящее SMS могло уронить полный скан.
     */
    fun extractLinks(body: String): List<String> {
        val links = mutableListOf<String>()
        for (rawToken in deobfuscate(body).split(' ', '\t', '\n', '\r')) {
            val token = rawToken.trim(*TRIM_CHARS.toCharArray())
            if (token.isEmpty()) continue
            val explicit = EXPLICIT_LINK.findAll(token).map { it.value.trim(*TRIM_CHARS.toCharArray()) }
            val found = explicit.filter { it.isNotEmpty() }.toList()
            if (found.isNotEmpty()) {
                links += found
            } else if (isBareDomain(token)) {
                links += token
            }
        }
        return links
    }

    /** Снимает маскировку ссылок: `[.]` → `.`, `hxxp` → `http`, пробелы вокруг точки. */
    fun deobfuscate(body: String): String {
        if (body.isEmpty()) return body
        return body
            .replace(OBFUSCATED_SCHEME, "http$1://")
            .replace(OBFUSCATED_DOT, ".")
            .replace(SPACED_DOT, ".")
    }

    /** `sberbank-online.ru` — да; `10.50`, `т.е`, `v1.2`, `photo.jpg` — нет. */
    private fun isBareDomain(token: String): Boolean {
        if ('.' !in token) return false
        if (token.any { it == '/' || it == '\\' || it == '?' || it == '#' || it == '@' || it == ':' }) return false
        val labels = token.lowercase(Locale.ROOT).split('.')
        if (labels.size < 2) return false
        if (!isZoneLike(labels.last())) return false
        return labels.all { label ->
            label.isNotEmpty() &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' || it == '–' }
        }
    }

    /**
     * Похожа ли последняя метка на домен верхнего уровня.
     *
     * Раньше зоны были перечислены руками (`ru`, `com`, `xyz`, …), поэтому фишинг в свежих
     * зонах (`.icu`, `.app`, `.link`, `.рф`) считался «сообщением без ссылки». Правило
     * заменено на форму: 2–24 буквы любого алфавита, кроме известных расширений файлов.
     */
    private fun isZoneLike(label: String): Boolean {
        if (label.length < 2 || label.length > 24) return false
        if (label in FILE_EXTENSIONS) return false
        return label.all { it.isLetter() }
    }

    private fun joinedFrom(words: List<String>, start: Int): String =
        buildString { for (index in start until words.size) append(words[index]) }

    private fun String.isMixedAlphabet(): Boolean {
        var hasCyrillic = false
        var hasLatin = false
        for (ch in this) {
            when {
                ch in '\u0400'..'\u04FF' -> hasCyrillic = true
                ch in 'a'..'z' || ch in 'A'..'Z' -> hasLatin = true
            }
            if (hasCyrillic && hasLatin) return true
        }
        return false
    }
}
