package com.phoneguard.fullscan.checks

/**
 * Правила работы с путями из `MediaStore` — без Android API.
 *
 * Два места в `FileSystemScanCheck` были источником ошибок:
 *
 *  - путь на Android 10+ собирался как `"$root/$relPath$displayName"`. Если
 *    `RELATIVE_PATH` приходил без завершающего `/` (некоторые OEM-прошивки), получался
 *    склеенный путь `…/Downloadphoto.apk`, а если `RELATIVE_PATH` был пустым — путь в
 *    корне хранилища вместо пропуска записи;
 *  - проверка «APK лежит не в Download» дублировала расширение файла и каталог загрузок
 *    в двух местах.
 *
 * Обе операции стали чистыми функциями (и покрыты тестами): `scopedPath`, `isSuspiciousApk`.
 */
object MediaStoreFilePolicy {

    /** Расширение APK. */
    private const val APK_EXTENSION = "apk"

    /**
     * Путь файла из `RELATIVE_PATH` и `DISPLAY_NAME` (Android 10+, scoped storage).
     *
     * @param externalRoot корень внешнего хранилища (обычно `/storage/emulated/0`)
     * @return абсолютный путь либо `null`, если данных не хватает — запись пропускается
     */
    fun scopedPath(externalRoot: String, relativePath: String?, displayName: String?): String? {
        val relPath = relativePath?.trim().orEmpty()
        val name = displayName?.trim().orEmpty()
        if (relPath.isEmpty() || name.isEmpty()) return null

        val root = externalRoot.trimEnd('/')
        val directory = relPath.trim('/')
        return if (directory.isEmpty()) "$root/$name" else "$root/$directory/$name"
    }

    /** Является ли путь APK-файлом (расширение без учёта регистра). */
    fun isApk(path: String): Boolean =
        path.substringAfterLast('.', "").equals(APK_EXTENSION, ignoreCase = true)

    /**
     * APK вне публичного каталога Download — типичная маскировка: установщик кладёт
     * файл в `Download`, а вредоносные сборщики прячут его в `DCIM`, `Pictures`, `Android/data`.
     */
    fun isSuspiciousApk(path: String): Boolean =
        isApk(path) && !FileSystemHeuristics.isInsideDownloads(path)
}
