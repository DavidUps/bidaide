package com.bidaide.github.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrWorktreesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `worktree lives under the system dir, per repository and per pull request`() {
        val system = File("/sys")
        val dir = PrWorktrees.worktreeDir(system, "/Users/me/work/eatin", 42)
        assertEquals("pr-42", dir.name)
        assertEquals(File("/sys/bidaide-worktrees"), dir.parentFile.parentFile)
        assertTrue(dir.parentFile.name.startsWith("eatin-"))
    }

    @Test
    fun `two clones with the same name get different folders`() {
        val a = PrWorktrees.repoFolderName("/a/app")
        val b = PrWorktrees.repoFolderName("/b/app")
        assertTrue(a.startsWith("app-") && b.startsWith("app-"))
        assertFalse(a == b)
        assertEquals(a, PrWorktrees.repoFolderName("/a/app/"))
        assertTrue(PrWorktrees.repoFolderName("/x/my repo!").startsWith("my-repo--"))
    }

    @Test
    fun `remote is the one pointing at the repository gh resolves`() {
        val remotes = mapOf(
            "origin" to listOf("https://github.com/me/android.git"),
            "upstream" to listOf("git@github.com:Org/Android.git"),
        )
        assertEquals("upstream", PrWorktrees.remoteFor(remotes, "org/android"))
        assertEquals("origin", PrWorktrees.remoteFor(remotes, "me/android"))
        assertNull(PrWorktrees.remoteFor(remotes, "other/repo"))
        assertEquals("https://github.com/other/repo.git", PrWorktrees.repoUrl("other/repo"))
    }

    @Test
    fun `git command lines fetch the head and keep the worktree detached`() {
        assertEquals(listOf("--no-tags", "origin", "pull/7/head"), PrWorktrees.fetchArgs("origin", 7))
        assertEquals(listOf("add", "--detach", "/w/pr-7", "abc"), PrWorktrees.addArgs(File("/w/pr-7"), "abc"))
        assertEquals(listOf("--detach", "--force", "abc"), PrWorktrees.updateArgs("abc"))
        assertEquals(listOf("remove", "--force", "/w/pr-7"), PrWorktrees.removeArgs(File("/w/pr-7")))
    }

    @Test
    fun `worktree list porcelain yields the paths`() {
        val porcelain = """
            worktree /repo
            HEAD 96173f4007a593c34fd0b106e726ee440aa729d4
            branch refs/heads/main

            worktree /sys/bidaide-worktrees/repo-1/pr-3
            HEAD eb82101d807813f731c51b44326bf377f1ab105a
            detached
            locked reason with spaces

        """.trimIndent()
        assertEquals(listOf("/repo", "/sys/bidaide-worktrees/repo-1/pr-3"), PrWorktrees.parseWorktreeList(porcelain))
    }

    @Test
    fun `only paths strictly inside the worktrees folder are ours`() {
        val root = temp.newFolder("bidaide-worktrees")
        val inside = File(root, "repo-1/pr-3")
        assertTrue(PrWorktrees.isInside(root, inside))
        assertFalse(PrWorktrees.isInside(root, root))
        assertFalse(PrWorktrees.isInside(root, File(root, "../elsewhere")))
        assertFalse(PrWorktrees.isInside(root, File(root.parentFile, "bidaide-worktrees-other")))
        assertEquals(listOf(inside), PrWorktrees.ownWorktrees(root, listOf(temp.root.path, inside.path)))
    }

    @Test
    fun `install task comes from module and variant`() {
        assertEquals(":app:installDebug", PrWorktrees.installTask(PrWorktrees.FALLBACK_MODULE))
        assertEquals(":mobile:installFreeStaging", PrWorktrees.installTask(PrAppModule(":mobile", "freeStaging", "a.b")))
        assertEquals(":installRelease", PrWorktrees.installTask(PrAppModule(":", "release", null)))
    }

    @Test
    fun `gradle build runs the wrapper with plain console`() {
        assertEquals("gradlew.bat", PrWorktrees.wrapperName(windows = true))
        assertEquals(listOf("/w/gradlew", ":app:installDebug", "--console=plain"), PrWorktrees.buildCommand(File("/w/gradlew"), ":app:installDebug"))
    }

    @Test
    fun `gradle root is located inside the repository`() {
        val repo = temp.newFolder("repo")
        assertEquals("", PrWorktrees.gradleRootInRepo(repo, repo))
        assertEquals("android", PrWorktrees.gradleRootInRepo(repo, File(repo, "android")))
        assertNull(PrWorktrees.gradleRootInRepo(repo, temp.newFolder("other")))
    }

    @Test
    fun `test mode defaults to the worktree and warns about local changes`() {
        assertEquals(PrTestMode.WORKTREE, PrTestMode.parse(null))
        assertEquals(PrTestMode.WORKTREE, PrTestMode.parse("bogus"))
        assertEquals(PrTestMode.CHECKOUT, PrTestMode.parse("CHECKOUT"))
        assertTrue("sin commit" in PrTestMode.CHECKOUT.label(hasLocalChanges = true))
        assertFalse("sin commit" in PrTestMode.CHECKOUT.label(hasLocalChanges = false))
        assertTrue("local.properties" in PrWorktrees.failureHint(copiedLocalProperties = true))
    }
}
