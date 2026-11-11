package com.vintagelab.camera.gallery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryQueryTest {
    @Test fun selectionArgsAreFolderPrefixAndOwner() {
        assertArrayEquals(
            arrayOf("Pictures/VintageLab/%", "Movies/VintageLab/%", "com.vintagelab.camera"),
            GalleryQuery.selectionArgs("Pictures/VintageLab", "Movies/VintageLab", "com.vintagelab.camera"),
        )
        assertArrayEquals(
            arrayOf("Pictures/VintageLab/%", "Movies/VintageLab/%", "p"),
            GalleryQuery.selectionArgs("Pictures/VintageLab/", "Movies/VintageLab/", "p"),
        )
    }

    @Test fun selectionHasOnePlaceholderPerArgAndExcludesPending() {
        assertEquals(3, GalleryQuery.SELECTION.count { it == '?' })
        assertTrue(GalleryQuery.SELECTION.contains("relative_path LIKE ?"))
        assertTrue(GalleryQuery.SELECTION.contains("owner_package_name=?"))
        assertTrue(GalleryQuery.SELECTION.contains("is_pending=0"))
    }

    @Test fun newestFirstWithStableTiebreak() {
        assertEquals("datetaken DESC, _id DESC", GalleryQuery.SORT)
    }

    @Test fun api29PutsLimitInTheSortString() {
        val p = GalleryQuery.plan(29, offset = 120, limit = 60)
        assertFalse(p.useBundle)
        assertEquals("datetaken DESC, _id DESC LIMIT 60 OFFSET 120", p.sortOrder)
    }

    @Test fun api30PlusUsesTheBundleAndAPlainSort() {
        for (sdk in listOf(30, 34, 36)) {
            val p = GalleryQuery.plan(sdk, offset = 60)
            assertTrue(p.useBundle)
            assertEquals(GalleryQuery.SORT, p.sortOrder)
            assertEquals(GalleryQuery.PAGE_SIZE, p.limit)
            assertEquals(60, p.offset)
        }
    }

    @Test fun projectionHasIdDateTypeAndMime() {
        assertArrayEquals(arrayOf("_id", "datetaken", "media_type", "mime_type"), GalleryQuery.PROJECTION)
    }
}
