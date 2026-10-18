package com.bidaide.github.issues

import com.bidaide.gh.GhJson
import com.bidaide.github.ui.AvatarImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class IssueRowsTest {
    private val now = Instant.parse("2026-10-13T10:13:44Z")

    // The real shape of `gh issue list --json` for an issue with a linked pull request.
    private val issues = GhJson.issues(
        """[
          {"number":1,"title":"Display GitHub avatars for PR author and reviewers","state":"OPEN","author":{"login":"DavidUps"},
           "assignees":[{"id":"x","login":"DavidUps","name":"DavidUps"},{"id":"BOT_1","login":"Copilot","name":""}],
           "labels":[{"id":"LA_1","name":"enhancement","description":"New feature or request","color":"a2eeef"}],
           "createdAt":"2026-10-13T07:13:44Z","updatedAt":"2026-10-13T09:50:00Z","milestone":{"title":"v0.2"},
           "closedByPullRequestsReferences":[{"id":"PR_1","number":3,"url":"https://github.com/DavidUps/bidaide/pull/3"},{"number":7,"url":"u7"}]},
          {"number":2,"title":"Won't do","state":"CLOSED","stateReason":"NOT_PLANNED","author":null},
          {"number":3,"title":"Done","state":"CLOSED","stateReason":"COMPLETED","author":{"login":"ana"},"closedByPullRequestsReferences":[{"number":9}]}
        ]"""
    )

    @Before
    fun noNetwork() {
        AvatarImages.reset()
        AvatarImages.background = { }
        AvatarImages.onEdt = { it() }
    }

    @After
    fun forget() = AvatarImages.reset()

    @Test
    fun `an issue row reads like on github`() {
        val row = IssueRows.row(issues[0], now)
        assertEquals("Display GitHub avatars for PR author and reviewers", row.title)
        assertEquals("#1 · DavidUps opened 3 h ago · v0.2", row.subtitle)
        assertEquals(listOf("enhancement"), row.labels.map { it.text })
        assertEquals(listOf("2 PR"), row.badges.map { it.text })
        assertEquals(listOf("DavidUps", "Copilot"), row.people.map { it.login })
        assertEquals("23 min ago", row.time)
    }

    @Test
    fun `closed issues say how they were closed and bare ones still render`() {
        assertEquals("Open", IssueRows.state(issues[0]).label)
        assertEquals("Not planned", IssueRows.state(issues[1]).label)
        assertEquals("Closed", IssueRows.state(issues[2]).label)
        val bare = IssueRows.row(issues[1], now)
        assertEquals("#2 · ghost opened ", bare.subtitle)
        assertTrue(bare.badges.isEmpty() && bare.people.isEmpty() && bare.labels.isEmpty())
        assertEquals(listOf("1 PR"), IssueRows.row(issues[2], now).badges.map { it.text })
        assertTrue(IssueRows.summary(issues[0], now).contains("opened this issue 3 h ago"))
        assertTrue(IssueRows.summary(issues[0], now).contains("v0.2"))
    }

    @Test
    fun `the header shows author and assignees with avatars`() {
        assertEquals(listOf("DavidUps", "DavidUps", "Copilot"), IssueHeader(issues[0]).chips.map { it.login })
        // A deleted author and nobody assigned: still a header, just without people.
        assertTrue(IssueHeader(issues[1]).chips.isEmpty())
    }
}
