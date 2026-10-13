package com.bidaide.github.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewedHunksTest {
    private val path = "app/Main.kt"
    private val all = listOf(ViewedHunks.key(4, 4), ViewedHunks.key(16, 17), ViewedHunks.key(20, 22))

    @Test
    fun `ticking the last change marks the file as viewed`() {
        val hunks = ViewedHunks()
        assertNull(hunks.set(path, all[0], true, all, fileViewed = false))
        assertNull(hunks.set(path, all[1], true, all, fileViewed = false))
        assertEquals("2/3", hunks.progress(path, fileViewed = false))
        assertEquals(true, hunks.set(path, all[2], true, all, fileViewed = false))
        assertTrue(all.all { hunks.isViewed(path, it, fileViewed = false) })
    }

    @Test
    fun `a file marked as viewed shows every change ticked`() {
        val hunks = ViewedHunks()
        hunks.fileSet(path, true)
        assertTrue(all.all { hunks.isViewed(path, it, fileViewed = true) })
        assertFalse(hunks.isViewed(path, all[0], fileViewed = false))
        // Nothing half-done to report for a file that is done.
        hunks.seen(path, all)
        assertNull(hunks.progress(path, fileViewed = true))
    }

    @Test
    fun `unticking one change of a viewed file unmarks the file and keeps the others`() {
        val hunks = ViewedHunks()
        assertEquals(false, hunks.set(path, all[1], false, all, fileViewed = true))
        assertTrue(hunks.isViewed(path, all[0], fileViewed = false))
        assertFalse(hunks.isViewed(path, all[1], fileViewed = false))
        assertTrue(hunks.isViewed(path, all[2], fileViewed = false))
        assertEquals("2/3", hunks.progress(path, fileViewed = false))
        // Ticking it again completes the file once more.
        assertEquals(true, hunks.set(path, all[1], true, all, fileViewed = false))
    }

    @Test
    fun `unmarking the file unticks all its changes`() {
        val hunks = ViewedHunks()
        hunks.set(path, all[0], true, all, fileViewed = false)
        hunks.fileSet(path, false)
        assertTrue(all.none { hunks.isViewed(path, it, fileViewed = false) })
        assertNull(hunks.progress(path, fileViewed = false))
    }

    @Test
    fun `unticking in an unviewed file changes nothing else`() {
        val hunks = ViewedHunks()
        hunks.set(path, all[0], true, all, fileViewed = false)
        assertNull(hunks.set(path, all[0], false, all, fileViewed = false))
        assertNull(hunks.set(path, all[2], false, all, fileViewed = false))
        assertFalse(hunks.isViewed(path, all[0], fileViewed = false))
        // Ticking a change of an already viewed file does not report a state change either.
        assertNull(hunks.set(path, all[0], true, all, fileViewed = true))
    }

    @Test
    fun `files are independent and a single-change file completes at once`() {
        val hunks = ViewedHunks()
        assertEquals(true, hunks.set("New.kt", ViewedHunks.WHOLE_FILE, true, listOf(ViewedHunks.WHOLE_FILE), fileViewed = false))
        assertFalse(hunks.isViewed(path, all[0], fileViewed = false))
        assertNull(hunks.progress("never-opened.kt", fileViewed = false))
    }

    @Test
    fun `marks survive a restart but not a new head commit`() {
        val hunks = ViewedHunks()
        hunks.set(path, all[0], true, all, fileViewed = false)
        hunks.set("lib/Other.kt", ViewedHunks.key(1, 1), true, listOf(ViewedHunks.key(1, 1), ViewedHunks.key(9, 9)), fileViewed = false)
        val saved = hunks.serialize("abc123")

        val same = ViewedHunks().apply { restore(saved, "abc123") }
        assertTrue(same.isViewed(path, all[0], fileViewed = false))
        assertFalse(same.isViewed(path, all[1], fileViewed = false))
        assertTrue(same.isViewed("lib/Other.kt", ViewedHunks.key(1, 1), fileViewed = false))

        val moved = ViewedHunks().apply { restore(saved, "def456") }
        assertFalse(moved.isViewed(path, all[0], fileViewed = false))
        ViewedHunks().apply { restore(null, "abc123"); restore("", "abc123"); restore("garbage", "abc123") }
    }
}
