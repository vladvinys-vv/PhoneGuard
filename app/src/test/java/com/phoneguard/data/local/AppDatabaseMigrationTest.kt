package com.phoneguard.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InspectionRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val TEST_DB = "migration-test"

    @Rule
    @JvmField
    val migrationTestHelper = MigrationTestHelper(
        InspectionRegistry.getOrCreateContext(),
        AppDatabase::class.java,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2() {
        migrationTestHelper.createDatabase(TEST_DB, 1).close()
        migrationTestHelper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)
    }

    @Test
    fun migrate2To3() {
        migrationTestHelper.createDatabase(TEST_DB, 2).close()
        migrationTestHelper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3)
    }

    @Test
    fun migrate3To4() {
        migrationTestHelper.createDatabase(TEST_DB, 3).close()
        migrationTestHelper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)
    }

    @Test
    fun migrate4To5() {
        migrationTestHelper.createDatabase(TEST_DB, 4).close()
        migrationTestHelper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5)
    }

    @Test
    fun migrate5To6() {
        migrationTestHelper.createDatabase(TEST_DB, 5).close()
        migrationTestHelper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)
    }

    @Test
    fun migrate6To7() {
        migrationTestHelper.createDatabase(TEST_DB, 6).close()
        migrationTestHelper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)
    }
}
