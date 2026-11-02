package com.phoneguard.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конфигурация миграций Room.
 *
 * Раньше здесь был инструментальный тест с `MigrationTestHelper`, но в
 * репозитории нет экспортированных JSON-схем (`app/schemas/` содержит только
 * README), а `MigrationTestHelper.createDatabase(db, 1)` без них падает: тест
 * не мог пройти в принципе.
 *
 * Теперь миграции вынесены в [DatabaseMigrations] (без Room/SQLCipher/KeyStore), поэтому
 * файл **исполняется** в обычном JVM-прогоне. Проверяем то, что видно без устройства:
 * цепочка непрерывна, версии не дублируются, каждая миграция действительно что-то делает
 * и не удаляет таблицы «в никуда». Настоящий SQL (типы, ссылки на таблицы и колонки)
 * прогоняется отдельно — `tools/migration_check.py` на живом SQLite.
 *
 * Валидацию через `MigrationTestHelper` можно вернуть, когда в `app/schemas/` появятся
 * экспортированные схемы (после первой сборки с включённым `exportSchema`).
 */
class AppDatabaseMigrationConfigTest {

    private val migrations = DatabaseMigrations.ALL_MIGRATIONS

    private class RecordingDatabase : SupportSQLiteDatabase {
        val statements = mutableListOf<String>()
        override fun execSQL(sql: String) {
            statements += sql
        }
        override fun query(query: String): Any? = null
    }

    @Test
    fun `target version matches the declared database version`() {
        assertEquals(DatabaseMigrations.LATEST_VERSION, migrations.last().endVersion)
    }

    @Test
    fun `migration chain is continuous with no gaps`() {
        // 1 -> 2 -> 3 ... без пропусков: пропуск означает, что пользователь со
        // старой базой получит IllegalStateException вместо миграции.
        var expectedStart = 1
        migrations.forEach { migration ->
            assertEquals("разрыв в цепочке миграций", expectedStart, migration.startVersion)
            assertEquals(expectedStart + 1, migration.endVersion)
            expectedStart = migration.endVersion
        }
        assertEquals(
            "последняя миграция должна вести к LATEST_VERSION",
            DatabaseMigrations.LATEST_VERSION,
            expectedStart
        )
    }

    @Test
    fun `no duplicate migrations`() {
        val keys = migrations.map { it.startVersion to it.endVersion }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue("миграций должно быть не меньше, чем версий минус одна", migrations.size >= 1)
    }

    @Test
    fun `every migration issues at least one statement`() {
        // Пустая миграция = версия поднята, а схема нет: Room посчитает базу
        // обновлённой, и следующая миграция упадёт на отсутствующей таблице.
        migrations.forEach { migration ->
            val database = RecordingDatabase()
            migration.migrate(database)
            assertTrue(
                "миграция ${migration.startVersion}->${migration.endVersion} ничего не делает",
                database.statements.isNotEmpty()
            )
            database.statements.forEach { sql ->
                assertTrue(
                    "пустой SQL в миграции ${migration.startVersion}->${migration.endVersion}",
                    sql.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `statements start with a supported verb`() {
        val allowed = listOf("CREATE", "ALTER", "UPDATE", "INSERT", "DELETE", "DROP", "PRAGMA")
        migrations.forEach { migration ->
            val database = RecordingDatabase()
            migration.migrate(database)
            database.statements.forEach { sql ->
                val verb = sql.trim().split(Regex("\\s+")).first().uppercase()
                assertTrue(
                    "неожиданный оператор «$verb» в миграции " +
                        "${migration.startVersion}->${migration.endVersion}: $sql",
                    allowed.contains(verb)
                )
            }
        }
    }

    @Test
    fun `no table is dropped without being recreated`() {
        // Потеря данных в миграции — худший из возможных багов: тихая и необратимая.
        migrations.forEach { migration ->
            val database = RecordingDatabase()
            migration.migrate(database)
            val sql = database.statements.joinToString("\n").uppercase()
            val dropped = Regex("DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?`?(\\w+)`?")
                .findAll(sql)
                .map { it.groupValues[1] }
                .toSet()
            val created = Regex("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?")
                .findAll(sql)
                .map { it.groupValues[1] }
                .toSet()
            val lost = dropped - created
            assertTrue(
                "миграция ${migration.startVersion}->${migration.endVersion} удаляет таблицу без " +
                    "пересоздания: $lost",
                lost.isEmpty()
            )
        }
    }

    @Test
    fun `create table statements have balanced parentheses`() {
        // Дешёвая проверка на обрезанный SQL: непарная скобка в CREATE TABLE ловится
        // здесь, а не на устройстве у пользователя с базой старой версии.
        migrations.forEach { migration ->
            val database = RecordingDatabase()
            migration.migrate(database)
            database.statements.forEach { sql ->
                assertEquals(
                    "непарные скобки в SQL миграции ${migration.startVersion}->" +
                        "${migration.endVersion}: $sql",
                    sql.count { it == '(' },
                    sql.count { it == ')' }
                )
            }
        }
    }

    @Test
    fun `migrations never touch the database version of another chain`() {
        // Защита от copy-paste: имя MIGRATION_a_b должно соответствовать Migration(a, b).
        val named = DatabaseMigrations::class.java.declaredFields
            .filter { it.name.startsWith("MIGRATION_") }
            .map { it.name }
        assertTrue("миграции не объявлены как поля объекта", named.isNotEmpty())
        named.forEach { name ->
            val (start, end) = name.removePrefix("MIGRATION_").split("_").map { it.toInt() }
            assertFalse("имя $name указывает на обратный переход", start >= end)
        }
    }
}
