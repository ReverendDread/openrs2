package org.openrs2.cache

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

object StoreTest {
    private val DISK_ROOT = Path.of(StoreTest::class.java.getResource("disk-store/empty").toURI())
    private val FLAT_FILE_ROOT = Path.of(StoreTest::class.java.getResource("flat-file-store/empty").toURI())

    @Test
    fun testOpen() {
        Store.open(DISK_ROOT).use { store ->
            assertTrue(store is DiskStore)
        }

        Store.open(FLAT_FILE_ROOT).use { store ->
            assertTrue(store is FlatFileStore)
        }
    }
}
