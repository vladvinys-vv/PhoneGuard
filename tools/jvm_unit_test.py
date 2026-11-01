#!/usr/bin/env python3
"""Запуск unit-тестов чистой логики без Gradle, Maven и JUnit-jar.

Зачем: в песочнице нет доступа к Google Maven, поэтому `./gradlew testProductionDebugUnitTest`
не запускается, а `kotlin_frontend_check.py` только компилирует. Этот скрипт компилирует
`kotlinc`-ом исходники без Android-зависимостей вместе с их тестами и **выполняет** их
настоящим JVM-раннером с минимальным шимом `org.junit` (аннотации + `Assert` + `Runner`).

Шим закрывает только то, что нужно чистым тестам: JUnit-аннотации, `Assert`, а также заглушки
аннотаций Room и `javax.inject` — благодаря им под тесты попадают модели и конвертеры, которые
в рантайме не тянут Android.

Что получается на выходе: сколько тестов реально прогнано, сколько упало (с сообщением и местом
падения). Тесты, которым нужны Android, Robolectric, MockK, OkHttp, Gson или полноценные
корутины, здесь не выполняются — для них нет артефактов без сети; их проверяет Gradle.

Использование:
    python3 tools/jvm_unit_test.py            # прогнать всё, что получается
    python3 tools/jvm_unit_test.py --verbose  # показать, что и почему пропущено
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java"
TEST = ROOT / "app/src/test/java"
CACHE = Path(tempfile.gettempdir()) / "phoneguard-jvm-tests"

# Префиксы импортов, которые шим закрывает заглушками.
STUBBED_PREFIXES = (
    "androidx.room.", "androidx.annotation.", "javax.inject.", "kotlinx.coroutines.flow.",
    "android.util.Log", "android.util.Base64", "android.os.Build", "android.os.SystemClock",
    "android.text.TextUtils", "android.content.Context",
    "android.content.ContentResolver", "android.content.pm.PackageManager",
    "android.content.pm.ApplicationInfo", "android.provider.Settings",
    "android.app.AppOpsManager", "dagger.hilt.android.qualifiers.ApplicationContext",
    "androidx.sqlite.db.SupportSQLiteDatabase",
)

# Импорты, которые без сети не разрешить даже заглушкой.
BLOCKING_PREFIXES = (
    "android.", "androidx.", "io.mockk", "org.robolectric", "okhttp3", "com.google.gson",
    "kotlinx.coroutines", "dagger.", "androidx.test", "com.google.android",
)

JUNIT_SHIM = '''package org.junit

@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION)
annotation class Test(val expected: kotlin.reflect.KClass<out Throwable> = None::class)
class None : Throwable()
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Before
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class After
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Ignore(val value: String = "")
class AssertionFailure(message: String) : AssertionError(message)

object Assert {
    @JvmStatic fun fail(message: String? = null): Nothing = throw AssertionFailure(message ?: "fail()")
    @JvmStatic fun assertTrue(condition: Boolean) = assertTrue(null, condition)
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) throw AssertionFailure(message ?: "ожидалось true")
    }
    @JvmStatic fun assertFalse(condition: Boolean) = assertFalse(null, condition)
    @JvmStatic fun assertFalse(message: String?, condition: Boolean) {
        if (condition) throw AssertionFailure(message ?: "ожидалось false")
    }
    @JvmStatic fun assertNull(actual: Any?) = assertNull(null, actual)
    @JvmStatic fun assertNull(message: String?, actual: Any?) {
        if (actual != null) throw AssertionFailure("${message ?: "ожидалось null"}: получено $actual")
    }
    @JvmStatic fun assertNotNull(actual: Any?) = assertNotNull(null, actual)
    @JvmStatic fun assertNotNull(message: String?, actual: Any?) {
        if (actual == null) throw AssertionFailure(message ?: "ожидалось не null")
    }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionFailure("${message?.plus(": ") ?: ""}ожидалось <$expected>, получено <$actual>")
    }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals(null, expected, actual, delta)
    @JvmStatic fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (expected.isNaN() != actual.isNaN() || (!expected.isNaN() && kotlin.math.abs(expected - actual) > delta)) {
            throw AssertionFailure("${message?.plus(": ") ?: ""}ожидалось <$expected>, получено <$actual>")
        }
    }
    @JvmStatic fun assertNotEquals(unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionFailure("не ожидалось <$unexpected>")
    }
    @JvmStatic fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) throw AssertionFailure("массивы различаются")
    }
    @JvmStatic fun assertSame(expected: Any?, actual: Any?) {
        if (expected !== actual) throw AssertionFailure("ожидался тот же объект")
    }
    @JvmStatic fun <T : Throwable> assertThrows(type: Class<T>, block: () -> Unit): T {
        try { block() } catch (thrown: Throwable) {
            if (type.isInstance(thrown)) return type.cast(thrown)
            throw AssertionFailure("ожидалось ${type.name}, получено ${thrown::class.java.name}: ${thrown.message}")
        }
        throw AssertionFailure("ожидалось ${type.name}, но исключения не было")
    }
}
'''

RUNNER_SHIM = '''package org.junit

import kotlin.reflect.KClass

object Runner {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        var failed = 0
        var skipped = 0
        for (className in args) {
            val type: KClass<*> = Class.forName(className).kotlin
            val methods = type.java.declaredMethods
            val tests = methods
                .filter { it.isAnnotationPresent(Test::class.java) && !it.isAnnotationPresent(Ignore::class.java) }
                .sortedBy { it.name }
            if (type.annotations.any { it is Ignore }) { skipped += tests.size; continue }
            for (test in tests) {
                val instance = type.java.getDeclaredConstructor().newInstance()
                try {
                    methods.filter { it.isAnnotationPresent(Before::class.java) }.forEach { it.isAccessible = true; it.invoke(instance) }
                    test.isAccessible = true
                    val expected = test.getAnnotation(Test::class.java).expected.java
                    var thrown: Throwable? = null
                    try { test.invoke(instance) }
                    catch (error: java.lang.reflect.InvocationTargetException) { thrown = error.cause ?: error }
                    if (expected != None::class.java) {
                        if (thrown == null || !expected.isInstance(thrown)) {
                            throw AssertionFailure("ожидалось исключение ${expected.name}, получено " +
                                (thrown?.let { it::class.java.name } ?: "ничего"))
                        }
                    } else if (thrown != null) throw thrown
                    methods.filter { it.isAnnotationPresent(After::class.java) }.forEach { it.isAccessible = true; it.invoke(instance) }
                    passed++
                    println("PASS ${type.simpleName}.${test.name}")
                } catch (error: Throwable) {
                    failed++
                    val cause = (error as? java.lang.reflect.InvocationTargetException)?.cause ?: error
                    val where = cause.stackTrace.firstOrNull { it.className.startsWith("com.phoneguard") }?.let { " @ $it" } ?: ""
                    println("FAIL ${type.simpleName}.${test.name} :: ${cause::class.java.simpleName}: ${cause.message}$where")
                }
            }
        }
        println("SUMMARY passed=$passed failed=$failed skipped=$skipped")
        if (failed > 0) kotlin.system.exitProcess(1)
    }
}
'''

STUB_SOURCES = {
    "androidx/room/Room.kt": '''package androidx.room

@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class TypeConverter
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS) annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val primaryKeys: Array<String> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val inheritSuperIndices: Boolean = false,
)
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS) annotation class ForeignKey(
    val entity: kotlin.reflect.KClass<*> = Any::class,
    val parentColumns: Array<String> = [],
    val childColumns: Array<String> = [],
    val onDelete: Int = 1,
    val onUpdate: Int = 1,
) {
    companion object {
        const val NO_ACTION: Int = 1
        const val RESTRICT: Int = 2
        const val SET_NULL: Int = 3
        const val SET_DEFAULT: Int = 4
        const val CASCADE: Int = 5
    }
}
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS) annotation class Dao
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Insert(val onConflict: Int = 3)
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Update
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Delete
object OnConflictStrategy { const val REPLACE: Int = 1; const val IGNORE: Int = 2; const val ABORT: Int = 3 }

/** Заглушка RoomDatabase: для компиляции `@Database`-класса достаточно базового типа. */
abstract class RoomDatabase {
    open fun close() {}
    open fun clearAllTables() {}
}
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS) annotation class Database(
    val entities: Array<kotlin.reflect.KClass<*>> = [],
    val version: Int = 1,
    val exportSchema: Boolean = true,
    val views: Array<kotlin.reflect.KClass<*>> = [],
    val autoMigrations: Array<AutoMigration> = [],
)
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS)
annotation class AutoMigration(val from: Int = 0, val to: Int = 0, val spec: kotlin.reflect.KClass<*> = Any::class)
/** Базовая миграция Room: только объявление, исполняется на устройстве. */
abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase)
}
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS) annotation class DatabaseView(val value: String = "", val viewName: String = "")
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class ColumnInfo(val name: String = "", val defaultValue: String = VALUE_UNSPECIFIED) {
    companion object { const val VALUE_UNSPECIFIED: String = "[value-unspecified]" }
}
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS) annotation class TypeConverters(vararg val value: kotlin.reflect.KClass<*> = [])
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD) annotation class Index(val value: Array<String> = [], val unique: Boolean = false, val name: String = "", val orders: IntArray = [])
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FIELD) annotation class Embedded(val prefix: String = "")
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Ignore
''',
    "androidx/sqlite/db/SupportSQLiteDatabase.kt": '''package androidx.sqlite.db

/**
 * Заглушка SupportSQLiteDatabase: SQL-миграции на JVM не исполняются (это делает
 * `tools/migration_check.py` в настоящем SQLite), но объявление метода `migrate` нужно,
 * чтобы `AppDatabase` и тест его конфигурации компилировались.
 */
interface SupportSQLiteDatabase {
    fun execSQL(sql: String)
    fun query(query: String): Any?
}
''',
    "androidx/annotation/Annotations.kt": '''package androidx.annotation

@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) annotation class StringRes
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) annotation class DrawableRes
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) annotation class ColorRes
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) annotation class RawRes
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) annotation class AttrRes
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) annotation class AnyRes
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY) annotation class RequiresApi(val value: Int = 1)
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY) annotation class RequiresPermission(vararg val value: String = [])
''',
    "android/util/Log.kt": '''package android.util

/** Заглушка android.util.Log: пишет в stdout, чтобы падения были видны в выводе прогона. */
object Log {
    @JvmField val VERBOSE: Int = 2
    @JvmField val DEBUG: Int = 3
    @JvmField val INFO: Int = 4
    @JvmField val WARN: Int = 5
    @JvmField val ERROR: Int = 6

    @JvmStatic fun v(tag: String, msg: String): Int = print(tag, msg) { "V" }
    @JvmStatic fun d(tag: String, msg: String): Int = print(tag, msg) { "D" }
    @JvmStatic fun i(tag: String, msg: String): Int = print(tag, msg) { "I" }
    @JvmStatic fun w(tag: String, msg: String): Int = print(tag, msg) { "W" }
    @JvmStatic fun e(tag: String, msg: String): Int = print(tag, msg) { "E" }
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable): Int = print(tag, "$msg ${tr.message}") { "V" }
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable): Int = print(tag, "$msg ${tr.message}") { "D" }
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable): Int = print(tag, "$msg ${tr.message}") { "I" }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int = print(tag, "$msg ${tr.message}") { "W" }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = print(tag, "$msg ${tr.message}") { "E" }
    @JvmStatic fun w(tag: String, tr: Throwable): Int = print(tag, "${tr.message}") { "W" }
    @JvmStatic fun e(tag: String, tr: Throwable): Int = print(tag, "${tr.message}") { "E" }
    @JvmStatic fun wtf(tag: String, msg: String): Int = print(tag, msg) { "WTF" }
    @JvmStatic fun wtf(tag: String, msg: String, tr: Throwable): Int = print(tag, "$msg ${tr.message}") { "WTF" }
    @JvmStatic fun isLoggable(tag: String, level: Int): Boolean = false

    private fun print(tag: String, msg: String, level: () -> String): Int {
        println("${level()}/$tag: $msg")
        return 0
    }
}
''',
    "android/util/Base64.kt": '''package android.util

import java.util.Base64 as JBase64

/** Заглушка android.util.Base64 поверх java.util.Base64 (NO_WRAP = без переводов строк). */
object Base64 {
    @JvmField val DEFAULT: Int = 0
    @JvmField val NO_PADDING: Int = 1
    @JvmField val NO_WRAP: Int = 2
    @JvmField val CRLF: Int = 4
    @JvmField val URL_SAFE: Int = 8
    @JvmField val NO_CLOSE: Int = 16

    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String =
        if (flags and URL_SAFE != 0) JBase64.getUrlEncoder().encodeToString(input)
        else JBase64.getEncoder().encodeToString(input)

    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray =
        if (flags and URL_SAFE != 0) JBase64.getUrlEncoder().encode(input)
        else JBase64.getEncoder().encode(input)

    @JvmStatic fun decode(str: String, flags: Int): ByteArray =
        if (flags and URL_SAFE != 0) JBase64.getUrlDecoder().decode(str)
        else JBase64.getDecoder().decode(str)

    @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input), flags)
}
''',
    "android/content/Context.kt": '''package android.content

/**
 * Минимальная заглушка Context: только то, что нужно чистым проверкам для сборки текста.
 * Тесты, которым нужен настоящий Context (ресурсы, сервисы, SharedPreferences), в JVM-прогон
 * не попадают — они идут под Robolectric в Gradle.
 */
abstract class Context {
    open fun getString(resId: Int): String = "res:$resId"
    open fun getString(resId: Int, vararg formatArgs: Any?): String = "res:$resId(" + formatArgs.joinToString() + ")"
    open val packageName: String get() = "com.phoneguard"
    open val filesDir: java.io.File get() = java.io.File(System.getProperty("java.io.tmpdir"), "phoneguard-files")
    open val cacheDir: java.io.File get() = java.io.File(System.getProperty("java.io.tmpdir"), "phoneguard-cache")
    open val applicationContext: Context get() = this
    open val contentResolver: ContentResolver get() = ContentResolver()
    open val applicationInfo: android.content.pm.ApplicationInfo get() = android.content.pm.ApplicationInfo()
    open val packageManager: android.content.pm.PackageManager get() = android.content.pm.PackageManager()
    open fun getSystemService(name: String): Any? = null

    companion object {
        const val APP_OPS_SERVICE: String = "appops"
        const val WINDOW_SERVICE: String = "window"
        const val CONNECTIVITY_SERVICE: String = "connectivity"
        const val TELEPHONY_SERVICE: String = "phone"
    }
}
''',
    "android/content/ContentResolver.kt": '''package android.content

/** Заглушка ContentResolver: значения настроек отдаёт `android.provider.Settings`. */
open class ContentResolver
''',
    "android/content/pm/PackageManager.kt": '''package android.content.pm

/**
 * Заглушка PackageManager: список приложений пуст, поэтому проверки «подозрительный пакет»
 * на JVM видят ровно то, что им передали в тесте, и не зависят от машины.
 */
open class ApplicationInfo {
    var packageName: String = ""
    var uid: Int = 0
    var flags: Int = 0
    var enabled: Boolean = true

    companion object {
        const val FLAG_DEBUGGABLE: Int = 2
        const val FLAG_SYSTEM: Int = 1
        const val FLAG_UPDATED_SYSTEM_APP: Int = 128
        const val FLAG_EXTERNAL_STORAGE: Int = 262144
        const val FLAG_INSTALLED: Int = 8388608
    }
}

open class PackageManager {
    open fun getInstalledApplications(flags: Int): List<ApplicationInfo> = emptyList()
    open fun getInstalledApplications(flags: ApplicationInfoFlags): List<ApplicationInfo> = emptyList()
    open fun getApplicationLabel(info: ApplicationInfo): CharSequence = info.packageName
    open fun getApplicationLabel(info: Any): CharSequence = ""

    /** Заглушка `PackageManager.ApplicationInfoFlags` (API 33+). */
    class ApplicationInfoFlags private constructor() {
        companion object {
            @JvmStatic fun of(value: Long): ApplicationInfoFlags = ApplicationInfoFlags()
        }
    }

    /** Заглушка `PackageManager.PackageInfoFlags` (API 33+). */
    class PackageInfoFlags private constructor() {
        companion object {
            @JvmStatic fun of(value: Long): PackageInfoFlags = PackageInfoFlags()
        }
    }
    open fun getInstalledPackages(flags: Int): List<PackageInfo> = emptyList()
    open fun checkPermission(permission: String, packageName: String): Int = PERMISSION_DENIED
    open fun getPackageInfo(packageName: String, flags: Int): PackageInfo = throw NameNotFoundException(packageName)
    open fun getPackageInfo(packageName: String, flags: PackageInfoFlags): PackageInfo = throw NameNotFoundException(packageName)

    class NameNotFoundException(name: String) : Exception(name)

    companion object {
        const val PERMISSION_GRANTED: Int = 0
        const val PERMISSION_DENIED: Int = -1
        const val GET_PERMISSIONS: Int = 4096
        const val GET_ACTIVITIES: Int = 1
        const val GET_SERVICES: Int = 4
        const val GET_RECEIVERS: Int = 2
        const val GET_META_DATA: Int = 128
        const val MATCH_SYSTEM_ONLY: Int = 1048576
        const val MATCH_UNINSTALLED_PACKAGES: Int = 8192
    }
}

/** Заглушка `android.content.pm.PackageInfo`: только то, что читают проверки. */
open class PackageInfo {
    var packageName: String = ""
    var versionName: String? = null
    var requestedPermissions: Array<String>? = null
    var permissions: Array<Any>? = null
    var applicationInfo: ApplicationInfo? = null
}
''',
    "android/app/AppOpsManager.kt": '''package android.app

/**
 * Заглушка AppOpsManager: по умолчанию ни одно приложение не имеет «рисовать поверх других
 * окон», поэтому проверка оверлеев на JVM не выдаёт ложных находок.
 */
open class AppOpsManager {
    open fun unsafeCheckOpNoThrow(op: String, uid: Int, packageName: String?): Int = MODE_DEFAULT
    open fun checkOpNoThrow(op: String, uid: Int, packageName: String?): Int = MODE_DEFAULT
    open fun checkOp(op: Int, uid: Int, packageName: String?): Int = MODE_DEFAULT
    open fun noteOp(op: String, uid: Int, packageName: String?): Int = MODE_DEFAULT

    companion object {
        const val MODE_ALLOWED: Int = 0
        const val MODE_IGNORED: Int = 1
        const val MODE_ERRORED: Int = 2
        const val MODE_DEFAULT: Int = 3
        const val OPSTR_SYSTEM_ALERT_WINDOW: String = "android:system_alert_window"
        const val OPSTR_GET_USAGE_STATS: String = "android:get_usage_stats"
    }
}
''',
    "android/provider/Settings.kt": '''package android.provider

import android.content.ContentResolver

/**
 * Заглушка Settings: значения по умолчанию — «выключено». Так проверка интрузий на JVM ведёт себя
 * как чистый телефон: ADB выключен, сторонних служб доступности нет.
 */
object Settings {
    object Global {
        const val ADB_ENABLED: String = "adb_enabled"
        const val DEVELOPMENT_SETTINGS_ENABLED: String = "development_settings_enabled"

        @JvmStatic fun getInt(resolver: ContentResolver, name: String, def: Int): Int = def
        @JvmStatic fun getString(resolver: ContentResolver, name: String): String? = null
        @JvmStatic fun getLong(resolver: ContentResolver, name: String, def: Long): Long = def
    }

    object Secure {
        const val ENABLED_ACCESSIBILITY_SERVICES: String = "enabled_accessibility_services"
        const val ACCESSIBILITY_ENABLED: String = "accessibility_enabled"
        const val INSTALL_NON_MARKET_APPS: String = "install_non_market_apps"

        @JvmStatic fun getInt(resolver: ContentResolver, name: String, def: Int): Int = def
        @JvmStatic fun getString(resolver: ContentResolver, name: String): String? = null
        @JvmStatic fun getFloat(resolver: ContentResolver, name: String, def: Float): Float = def
    }

    object System {
        const val SCREEN_OFF_TIMEOUT: String = "screen_off_timeout"

        @JvmStatic fun getInt(resolver: ContentResolver, name: String, def: Int): Int = def
        @JvmStatic fun getString(resolver: ContentResolver, name: String): String? = null
    }
}
''',
    "android/os/Build.kt": '''package android.os

/** Заглушка android.os.Build: SDK_INT = 35 (targetSdk проекта), остальное — стабильные строки. */
object Build {
    object VERSION {
        @JvmField val SDK_INT: Int = 35
        @JvmField val RELEASE: String = "15"
        @JvmField val CODENAME: String = "REL"
        @JvmField val INCREMENTAL: String = "stub"
        @JvmField val SECURITY_PATCH: String = "2026-11-01"
        @JvmField val PREVIEW_SDK_INT: Int = 0
    }

    object VERSION_CODES {
        const val BASE: Int = 1
        const val LOLLIPOP: Int = 21
        const val M: Int = 23
        const val N: Int = 24
        const val N_MR1: Int = 25
        const val O: Int = 26
        const val O_MR1: Int = 27
        const val P: Int = 28
        const val Q: Int = 29
        const val R: Int = 30
        const val S: Int = 31
        const val S_V2: Int = 32
        const val TIRAMISU: Int = 33
        const val UPSIDE_DOWN_CAKE: Int = 34
        const val VANILLA_ICE_CREAM: Int = 35
        const val BAKLAVA: Int = 36
        const val CUR_DEVELOPMENT: Int = 10000
    }

    @JvmField val MANUFACTURER: String = "Google"
    @JvmField val BRAND: String = "google"
    @JvmField val MODEL: String = "stub-model"
    @JvmField val DEVICE: String = "stub-device"
    @JvmField val PRODUCT: String = "stub-product"
    @JvmField val HARDWARE: String = "stub-hw"
    @JvmField val FINGERPRINT: String = "google/stub/stub:15/STUB/1:user/release-keys"
    @JvmField val TAGS: String = "release-keys"
    @JvmField val TYPE: String = "user"
    @JvmField val ID: String = "STUB"
    @JvmField val BOOTLOADER: String = "unknown"
    @JvmField val HOST: String = "stub"
    @JvmField val BOARD: String = "stub"
    @JvmField val DISPLAY: String = "stub"
    @JvmField val SUPPORTED_ABIS: Array<String> = arrayOf("arm64-v8a")
    @JvmField val SUPPORTED_32_BIT_ABIS: Array<String> = arrayOf("armeabi-v7a")
    @JvmField val SUPPORTED_64_BIT_ABIS: Array<String> = arrayOf("arm64-v8a")
    @JvmField val TIME: Long = 0L
    @JvmStatic fun getRadioVersion(): String = "stub-radio"
}
''',
    "android/os/SystemClock.kt": '''package android.os

object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun currentThreadTimeMillis(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun sleep(ms: Long) { Thread.sleep(ms) }
}
''',
    "android/text/TextUtils.kt": '''package android.text

object TextUtils {
    @JvmStatic fun isEmpty(str: CharSequence?): Boolean = str == null || str.isEmpty()
    @JvmStatic fun equals(a: CharSequence?, b: CharSequence?): Boolean = a?.toString() == b?.toString()
    @JvmStatic fun join(delimiter: CharSequence, vararg tokens: Any?): String = tokens.joinToString(delimiter.toString())
}
''',
    "dagger/hilt/android/qualifiers/ApplicationContext.kt": '''package dagger.hilt.android.qualifiers

/** Заглушка квалификатора Hilt: для компиляции чистых классов хватает самой аннотации. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class ApplicationContext
''',
    "javax/inject/Inject.kt": '''package javax.inject

@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class Inject
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Singleton
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Named(val value: String = "")
''',
    "kotlinx/coroutines/flow/Flow.kt": '''package kotlinx.coroutines.flow

interface Flow<out T>
''',
}


def resolve_tool(name: str, env: str, default: str) -> str | None:
    from_env = os.environ.get(env)
    if from_env and Path(from_env).exists():
        return from_env
    found = shutil.which(name)
    if found:
        return found
    return default if Path(default).exists() else None


def imports_of(path: Path) -> list[str]:
    return re.findall(r"^import\s+([\w.]+)", path.read_text(encoding="utf-8"), re.M)


def blocking_import(path: Path) -> str | None:
    for name in imports_of(path):
        stubbed = any(name.startswith(prefix) for prefix in STUBBED_PREFIXES)
        if not stubbed and any(name.startswith(prefix) for prefix in BLOCKING_PREFIXES):
            return name
    return None


R_REFERENCE = re.compile(r"\bR\.(string|array|plist|drawable|color|xml|raw|id|integer|bool|style|dimen)\.(\w+)")


def resource_stub(sources: list[Path]) -> str | None:
    """Собирает заглушку com.phoneguard.R по фактическим ссылкам R.<тип>.<имя>."""
    fields: dict[str, set[str]] = {}
    for path in sources:
        for kind, name in R_REFERENCE.findall(path.read_text(encoding="utf-8")):
            fields.setdefault(kind, set()).add(name)
    if not fields:
        return None
    body = ["package com.phoneguard", "", "/** Сгенерированная заглушка ресурсов для JVM-прогона. */", "object R {"]
    for kind, names in sorted(fields.items()):
        body.append(f"    object {kind} {{")
        for index, name in enumerate(sorted(names), start=1):
            body.append(f"        @JvmField val {name}: Int = {index}")
        body.append("    }")
    body.append("}")
    return "\n".join(body) + "\n"

TEST_CLASS_DECL = re.compile(r"^[ \t]*(?:internal |private |public |open |abstract )*class (\w+)", re.M)


def kotlin_classes_of(path: Path) -> list[str]:
    """Все тест-классы файла.

    Раньше брался только первый класс, поэтому второй класс в том же файле компилировался,
    но **не исполнялся** — тесты «проходили», не запускаясь. Теперь собираем все классы,
    в теле которых есть `@Test`.
    """
    source = path.read_text(encoding="utf-8")
    package = re.search(r"^package\s+([\w.]+)", source, re.M)
    if not package:
        return []
    declarations = list(TEST_CLASS_DECL.finditer(source))
    names: list[str] = []
    for index, declaration in enumerate(declarations):
        end = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
        if "@Test" in source[declaration.start():end]:
            names.append(f"{package.group(1)}.{declaration.group(1)}")
    return names


def kotlin_class_of(path: Path) -> str:
    """Первый тест-класс файла (для совместимости с одиночными классами)."""
    classes = kotlin_classes_of(path)
    return classes[0] if classes else ""


def class_index() -> dict[str, tuple[Path, str]]:
    """{имя класса: (файл, пакет)} по всем исходникам приложения."""
    index: dict[str, tuple[Path, str]] = {}
    for path in MAIN.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        if not package:
            continue
        names = re.findall(r"^(?:internal |private |public |abstract |open |sealed |data |enum )*"
                           r"(?:class|object|interface) (\w+)", source, re.M)
        # Расширения и top-level функции (`fun IntrusionEventEntity.toIntrusionEvent()`)
        names += re.findall(r"^(?:internal |private |public )?fun (?:<[^>]+> )?(?:[\w.]+\.)?(\w+)\(", source, re.M)
        for name in names:
            index.setdefault(name, (path, package.group(1)))
    return index


def package_of(path: Path) -> str:
    match = re.search(r"^package\s+([\w.]+)", path.read_text(encoding="utf-8"), re.M)
    return match.group(1) if match else ""


BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"(?m)^\s*//.*$")


def code_only(text: str) -> str:
    """Текст без комментариев и KDoc.

    Иначе упоминание класса в документации (`[ThreatIntelClient]`) втягивало бы в замыкание
    весь файл с сетевыми и корутинными зависимостями, и тест на чистые модели выпадал из прогона.
    """
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub(" ", text))


def resolve_sources(test: Path, index: dict[str, tuple[Path, str]]) -> tuple[list[Path], str | None]:
    """Транзитивное замыкание исходников, нужных тесту. Возвращает (файлы, причина_пропуска)."""
    included: dict[Path, str] = {test: test.read_text(encoding="utf-8")}
    code: dict[Path, str] = {test: code_only(included[test])}
    queue = [(test, code[test])]
    while queue:
        path, text = queue.pop()
        own_package = package_of(path)
        for name, (source_path, package) in index.items():
            if source_path in included or source_path == path:
                continue
            if not re.search(rf"\b{re.escape(name)}\b", text):
                continue
            same_package = package == own_package
            imported = f"import {package}.{name}" in text or f"import {package}.*" in text
            if not (same_package or imported):
                continue
            reason = blocking_import(source_path)
            if reason:
                return [], f"{source_path.relative_to(ROOT)} требует {reason}"
            included[source_path] = source_path.read_text(encoding="utf-8")
            code[source_path] = code_only(included[source_path])
            queue.append((source_path, code[source_path]))
    return sorted(p for p in included if p != test), None


def build_shim(kotlinc: str) -> Path | None:
    shim_jar = CACHE / "junit-shim.jar"
    marker = CACHE / "shim-version"
    version = str(len(JUNIT_SHIM) + len(RUNNER_SHIM) + sum(len(v) for v in STUB_SOURCES.values()))
    if shim_jar.exists() and marker.exists() and marker.read_text().strip() == version:
        return shim_jar
    CACHE.mkdir(parents=True, exist_ok=True)
    work = CACHE / "shim-src"
    if work.exists():
        shutil.rmtree(work)
    for relative, content in STUB_SOURCES.items():
        target = work / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    (work / "JUnit.kt").write_text(JUNIT_SHIM, encoding="utf-8")
    (work / "Runner.kt").write_text(RUNNER_SHIM, encoding="utf-8")
    sources = [str(p) for p in work.rglob("*.kt")]
    result = subprocess.run(
        [kotlinc, *sources, "-include-runtime", "-d", str(shim_jar), "-nowarn"],
        capture_output=True, text=True,
    )
    if result.returncode != 0 or not shim_jar.exists():
        print("не удалось собрать шим org.junit:")
        print((result.stderr or result.stdout).strip()[:3000])
        return None
    marker.write_text(version, encoding="utf-8")
    return shim_jar


ASSERT_IMPORTS = (
    "assertEquals", "assertNotEquals", "assertTrue", "assertFalse", "assertNull", "assertNotNull",
    "assertSame", "assertArrayEquals", "assertThrows", "fail",
)


def prepare_test(test: Path, work: Path) -> Path:
    """`import org.junit.Assert.*` в шиме невозможно (Assert — объект), поэтому разворачиваем его.

    Реальные тесты не трогаем: копия кладётся в рабочий каталог прогона.
    """
    source = test.read_text(encoding="utf-8")
    if "import org.junit.Assert.*" not in source:
        return test
    replacement = "\n".join(f"import org.junit.Assert.{name}" for name in ASSERT_IMPORTS)
    target = work / "prepared" / test.name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source.replace("import org.junit.Assert.*", replacement), encoding="utf-8")
    return target

def compile_and_run(kotlinc: str, java: str, shim: Path, tests: list[Path], sources: list[Path],
                    out_dir: Path) -> tuple[bool, str]:
    out_dir.mkdir(parents=True, exist_ok=True)
    extra: list[str] = []
    stub = resource_stub(sources + tests)
    if stub is not None:
        generated = out_dir / "generated" / "R.kt"
        generated.parent.mkdir(parents=True, exist_ok=True)
        generated.write_text(stub, encoding="utf-8")
        extra.append(str(generated))
    result = subprocess.run(
        [kotlinc, *[str(p) for p in sources], *extra, *[str(p) for p in tests],
         "-classpath", str(shim), "-d", str(out_dir), "-nowarn"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        return False, (result.stderr or result.stdout).strip()
    classes = sorted({name for path in tests for name in kotlin_classes_of(path)})
    if not classes:
        return False, "в тестах не найдено классов"
    run = subprocess.run(
        [java, "-cp", os.pathsep.join([str(out_dir), str(shim)]), "org.junit.Runner", *classes],
        capture_output=True, text=True,
    )
    return True, run.stdout + run.stderr


SELF_TEST_PASSING = """package selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimPassingTest {
    @Test
    fun `passing assertion`() {
        assertEquals(2, 1 + 1)
        assertTrue("должно быть true", listOf(1, 2, 3).isNotEmpty())
    }

    @Test
    fun `expected exception is recognised`() {
        assertThrows(IllegalArgumentException::class.java) { throw IllegalArgumentException("boom") }
    }

    @Test
    fun `expected exception annotation is supported`() {
    }
}
"""

SELF_TEST_TWO_CLASSES = """package selftest

import org.junit.Assert.assertEquals
import org.junit.Test

/** Второй класс в том же файле раньше молча не исполнялся — проверяем, что оба идут в прогон. */
class FirstHalfTest {
    @Test
    fun `first class runs`() {
        assertEquals(2, 1 + 1)
    }
}

class SecondHalfTest {
    @Test
    fun `second class runs`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `second class runs both tests`() {
        assertEquals("ok", "ok")
    }
}
"""

SELF_TEST_FAILING = """package selftest

import org.junit.Assert.assertEquals
import org.junit.Test

class ShimFailingTest {
    @Test
    fun `failing assertion is reported`() {
        assertEquals("сообщение", 1, 2)
    }
}
"""


def self_test(kotlinc: str, java: str, shim: Path) -> int:
    """Проверяет сам раннер: ловит ли он падения и умеет ли считать успешные тесты."""
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        cases = [
            ("ShimPassingTest", SELF_TEST_PASSING, 3, 0),
            ("ShimFailingTest", SELF_TEST_FAILING, 0, 1),
            # Два тест-класса в одном файле: раньше исполнялся только первый.
            ("TwoClassesTest", SELF_TEST_TWO_CLASSES, 3, 0),
        ]
        for name, source, expected_passed, expected_failed in cases:
            test_file = work / f"{name}.kt"
            test_file.write_text(source, encoding="utf-8")
            ok, output = compile_and_run(kotlinc, java, shim, [test_file], [], work / name)
            if not ok:
                print(f"jvm_unit_test --self-test: провален на компиляции {name}:\n{output[:1500]}")
                return 1
            summary = re.search(r"SUMMARY passed=(\d+) failed=(\d+)", output)
            if summary is None:
                print(f"jvm_unit_test --self-test: раннер не вернул сводку для {name}")
                return 1
            passed, failed = int(summary.group(1)), int(summary.group(2))
            if (passed, failed) != (expected_passed, expected_failed):
                print(
                    f"jvm_unit_test --self-test: {name} — ожидалось "
                    f"{expected_passed} успешных и {expected_failed} падений, "
                    f"получено {passed}/{failed}"
                )
                return 1
    print("jvm_unit_test --self-test: ok (успех, падение, assertThrows и несколько классов в файле)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="показать пропущенные тесты и почему")
    parser.add_argument("--self-test", action="store_true", help="проверить сам раннер на синтетических тестах")
    args = parser.parse_args()

    kotlinc = resolve_tool("kotlinc", "KOTLINC", "/tmp/kt/package/bin/kotlinc")
    java_home = os.environ.get("JAVA_HOME", "/tmp/pyjd17/jdk4py/java-runtime")
    java = resolve_tool("java", "JAVA", str(Path(java_home) / "bin" / "java"))
    if not kotlinc or not java:
        print("jvm_unit_test: пропущено — нет kotlinc/java")
        return 0

    shim = build_shim(kotlinc)
    if shim is None:
        return 1

    if args.self_test:
        return self_test(kotlinc, java, shim)

    index = class_index()

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        skipped: list[tuple[Path, str]] = []
        collected: list[str] = []
        executed: list[Path] = []
        for test in sorted(TEST.rglob("*Test.kt")):
            reason = blocking_import(test)
            if reason:
                skipped.append((test, f"импорт {reason}"))
                continue
            sources, skip_reason = resolve_sources(test, index)
            if skip_reason:
                skipped.append((test, skip_reason))
                continue
            prepared = prepare_test(test, work)
            ok, out = compile_and_run(kotlinc, java, shim, [prepared], sources, work / test.stem)
            if not ok:
                skipped.append((test, "не компилируется без Android/Robolectric/MockK"))
                continue
            executed.append(test)
            collected.append(out)

        output = "\n".join(collected)
        failures = [line for line in output.splitlines() if line.startswith("FAIL ")]
        for line in failures:
            print(line)
        summaries = re.findall(r"SUMMARY passed=(\d+) failed=(\d+) skipped=(\d+)", output)
        passed = sum(int(item[0]) for item in summaries)
        failed = sum(int(item[1]) for item in summaries)
        summary = bool(summaries)

        if args.verbose:
            for test, reason in skipped:
                print(f"skip {test.relative_to(ROOT)} — {reason}")

        executed_names = ", ".join(t.stem for t in executed) or "—"
        print(f"тест-файлов выполнено: {len(executed)} ({executed_names})")
        print(f"тестов: {passed + failed}, прошло: {passed}, упало: {failed}")
        if not summary:
            print("раннер не отработал — смотрите вывод компиляции выше")
            return 1
        return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
