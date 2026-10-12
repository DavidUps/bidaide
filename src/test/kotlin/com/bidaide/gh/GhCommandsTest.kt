package com.bidaide.gh

import com.bidaide.gh.GhCommands.MergeMethod
import com.bidaide.gh.GhCommands.ReviewKind
import com.bidaide.gh.GhCommands.StateFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhCommandsTest {
    @Test
    fun `pr list adds search only when present`() {
        val plain = GhCommands.prList(StateFilter.OPEN, "  ")
        assertEquals(listOf("pr", "list", "--state", "open"), plain.take(4))
        assertFalse("--search" in plain)

        val searched = GhCommands.prList(StateFilter.MERGED, " author:@me ")
        assertEquals("merged", searched[3])
        assertEquals(listOf("--search", "author:@me"), searched.takeLast(2))
    }

    @Test
    fun `issue list maps merged to closed`() {
        assertEquals("closed", GhCommands.issueList(StateFilter.MERGED, "")[3])
    }

    @Test
    fun `pr create includes optional flags`() {
        assertEquals(
            listOf("pr", "create", "--title", "T", "--body", "", "--base", "main", "--draft"),
            GhCommands.prCreate("T", "", "main", draft = true),
        )
        assertEquals(listOf("pr", "create", "--title", "T", "--body", "B"), GhCommands.prCreate("T", "B", " ", draft = false))
    }

    @Test
    fun `review omits blank body`() {
        assertEquals(listOf("pr", "review", "7", "--approve"), GhCommands.prReview(7, ReviewKind.APPROVE, ""))
        assertEquals(
            listOf("pr", "review", "7", "--request-changes", "--body", "no"),
            GhCommands.prReview(7, ReviewKind.REQUEST_CHANGES, "no"),
        )
    }

    @Test
    fun `merge and state changes`() {
        assertEquals(listOf("pr", "merge", "3", "--squash", "--delete-branch"), GhCommands.prMerge(3, MergeMethod.SQUASH, true))
        assertEquals(listOf("pr", "close", "3"), GhCommands.prSetOpen(3, false))
        assertEquals(listOf("issue", "reopen", "4"), GhCommands.issueSetOpen(4, true))
    }

    @Test
    fun `browse formats lines, ref and print flag`() {
        assertEquals(listOf("browse", "a/B.kt:5", "--branch", "dev"), GhCommands.browse("a/B.kt", 5..5, "dev", null, false))
        assertEquals(
            listOf("browse", "a/B.kt:5-9", "--commit=abc", "--no-browser"),
            GhCommands.browse("a/B.kt", 5..9, "dev", "abc", true),
        )
        assertEquals(listOf("browse", "dir"), GhCommands.browse("dir", null, null, null, false))
    }

    @Test
    fun `gist create`() {
        assertEquals(listOf("gist", "create", "/t/A.kt", "--desc", "d", "--public"), GhCommands.gistCreate("/t/A.kt", "d", true))
        assertEquals(listOf("gist", "create", "/t/A.kt"), GhCommands.gistCreate("/t/A.kt", "", false))
    }

    @Test
    fun `auth errors are recognised`() {
        assertTrue(GhCli.isAuthError("To get started with GitHub CLI, please run:  gh auth login"))
        assertTrue(GhCli.isAuthError("You are not logged into any GitHub hosts."))
        assertFalse(GhCli.isAuthError("no pull requests found"))
    }

    @Test
    fun `github remotes are parsed`() {
        assertEquals("acme/app", GhRepoContext.parseGitHubRepo("https://github.com/acme/app.git"))
        assertEquals("o/r.name", GhRepoContext.parseGitHubRepo("git@github.com:o/r.name.git"))
        assertEquals("o/r", GhRepoContext.parseGitHubRepo("https://github.com/o/r/"))
        assertEquals(null, GhRepoContext.parseGitHubRepo("https://gitlab.com/o/r.git"))
    }
}
