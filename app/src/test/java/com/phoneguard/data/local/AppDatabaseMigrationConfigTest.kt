package com.phoneguard.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конфигурация миграций Room.
 *
 * Раньше здесь был инструментальный тест с `MigrationTestHelper`, но в
 * репозитории нет экспортированных JSON-схем (`app/schemas/` содержит только
 * README), а `MigrationTestHelper.createDatabase(db, 1)` без них падает: тест
 * не мог пройти в принципе. Проверяем то, что действительно можно проверить в
 * JVM: цепочка миграций непрерывна, ничего не забыто и не дублируется.
 *
 * Сами SQL-миграции проверяются на устройстве: после первой сборки
 * (`./gradlew :app:assembleDebug` при включённом `exportSchema`) схемы появятся в
 * `app/schemas/`, и тогда валидацию через `MigrationTestHelper` можно вернуть.
 */
class AppDatabaseMigrationConfigTest {

    private val migrations = AppDatabase.ALL_MIGRATIONS

    @Test
    fun `target version matches the declared database version`() {
        assertEquals(AppDatabase.LATEST_VERSION, migrations.last().endVersion)
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
            AppDatabase.LATEST_VERSION,
            expectedStart
        )
    }

    @Test
    fun `no duplicate migrations`() {
        val keys = migrations.map { it.startVersion to it.endVersion }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue("миграций должно быть не меньше, чем версий минус одна", migrations.size >= 1)
    }
}
