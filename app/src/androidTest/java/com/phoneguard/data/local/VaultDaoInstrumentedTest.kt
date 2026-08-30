package com.phoneguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.VaultItem
import com.phoneguard.model.VaultItemCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VaultDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.vaultDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveItem() = runBlocking {
        val item = VaultItem(
            fileName = "test.jpg",
            fileSize = 1024,
            mimeType = "image/jpeg",
            encryptionKeyAlias = "key1",
            category = VaultItemCategory.IMAGE
        )
        dao.insertItem(item)

        val items = dao.getAllItems().first()
        assertEquals(1, items.size)
        assertEquals("test.jpg", items.first().fileName)
    }

    @Test
    fun getItemByIdReturnsCorrectItem() = runBlocking {
        val item = VaultItem(
            fileName = "test.jpg",
            fileSize = 1024,
            mimeType = "image/jpeg",
            encryptionKeyAlias = "key1",
            category = VaultItemCategory.IMAGE
        )
        val id = dao.insertItem(item)

        val found = dao.getItemById(id)
        assertNotNull(found)
        assertEquals("test.jpg", found!!.fileName)
    }

    @Test
    fun deleteItemRemovesIt() = runBlocking {
        val item = VaultItem(
            fileName = "test.jpg",
            fileSize = 1024,
            mimeType = "image/jpeg",
            encryptionKeyAlias = "key1",
            category = VaultItemCategory.IMAGE
        )
        val id = dao.insertItem(item)
        dao.deleteItem(VaultItem(id = id, fileName = "test.jpg", fileSize = 1024, mimeType = "image/jpeg", encryptionKeyAlias = "key1", category = VaultItemCategory.IMAGE))

        val items = dao.getAllItems().first()
        assertTrue(items.isEmpty())
    }
}
