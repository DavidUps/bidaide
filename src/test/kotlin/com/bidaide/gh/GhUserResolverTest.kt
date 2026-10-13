package com.bidaide.gh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class GhUserResolverTest {
    private val workDir = File(".")

    @Test
    fun `returns cached user without executing gh again`() {
        val calls = AtomicInteger(0)
        val resolver = GhUserResolver { _, login ->
            calls.incrementAndGet()
            GhUser(login = login, avatarUrl = "https://avatar/$login")
        }
        val pr = GhPullRequest(
            number = 1,
            title = "T",
            state = "OPEN",
            author = GhUser(login = "alice"),
            headRefName = "h",
            baseRefName = "b",
            url = "u",
            reviewers = listOf(GhUser(login = "bob")),
        )

        resolver.resolvePullRequestUsers(workDir, pr)
        resolver.resolvePullRequestUsers(workDir, pr.copy(title = "T2"))

        assertEquals(2, calls.get())
    }

    @Test
    fun `keeps textual users when gh api fails`() {
        val resolver = GhUserResolver { _, _ -> error("boom") }
        val pr = GhPullRequest(
            number = 2,
            title = "T",
            state = "OPEN",
            author = GhUser(login = "alice"),
            headRefName = "h",
            baseRefName = "b",
            url = "u",
            reviewers = listOf(GhUser(login = "bob")),
        )

        val resolved = resolver.resolvePullRequestUsers(workDir, pr)

        assertEquals("alice", resolved.author?.login)
        assertNull(resolved.author?.avatarUrl)
        assertEquals(listOf("bob"), resolved.reviewers.mapNotNull { it.login })
    }

    @Test
    fun `uses metadata avatar without extra lookup`() {
        val calls = AtomicInteger(0)
        val resolver = GhUserResolver { _, login ->
            calls.incrementAndGet()
            GhUser(login = login, avatarUrl = "https://avatar/$login")
        }
        val pr = GhPullRequest(
            number = 3,
            title = "T",
            state = "OPEN",
            author = GhUser(login = "alice", avatarUrl = "https://existing/alice"),
            headRefName = "h",
            baseRefName = "b",
            url = "u",
            reviewers = listOf(GhUser(login = "bob", avatarUrl = "https://existing/bob")),
        )

        val resolved = resolver.resolvePullRequestUsers(workDir, pr)

        assertEquals(0, calls.get())
        assertEquals("https://existing/alice", resolved.author?.avatarUrl)
        assertEquals("https://existing/bob", resolved.reviewers.single().avatarUrl)
    }
}
