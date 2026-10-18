package com.bidaide

import com.bidaide.gh.GhJson
import com.bidaide.gh.GhNotifications
import com.bidaide.github.inbox.Inbox
import com.bidaide.github.pr.PullRequestRows
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.TimeAgo
import com.bidaide.github.ui.Tones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.time.Instant
import java.time.ZoneOffset

class RedesignTest {
    private val now = Instant.parse("2026-10-13T10:00:00Z")
    private fun ago(iso: String?) = TimeAgo.format(iso, now, ZoneOffset.UTC)

    @Test
    fun `times read like on github`() {
        assertEquals("now", ago("2026-10-13T09:59:40Z"))
        assertEquals("8 min ago", ago("2026-10-13T09:52:00Z"))
        assertEquals("3 h ago", ago("2026-10-13T06:10:00Z"))
        assertEquals("yesterday", ago("2026-10-12T08:00:00Z"))
        assertEquals("14 days ago", ago("2026-09-29T09:00:00Z"))
        assertEquals("on Aug 28", ago("2026-08-28T09:00:00Z"))
        assertEquals("on Dec 3, 2025", ago("2025-12-03T09:00:00Z"))
        // Clock skew between this machine and GitHub must not produce "-1 min ago".
        assertEquals("now", ago("2026-10-13T10:00:30Z"))
        assertEquals("", ago(null))
        assertEquals("", ago("not a date"))
    }

    @Test
    fun `a pull request row says who, when, where and in what state`() {
        val pr = GhJson.pullRequests(
            """[{"number":5,"title":"Clear the findings","state":"OPEN","author":{"login":"DavidUps"},"headRefName":"fix/report","baseRefName":"main",
                "createdAt":"2026-10-13T09:40:00Z","updatedAt":"2026-10-13T09:52:00Z","reviewDecision":"REVIEW_REQUIRED",
                "labels":[{"name":"bug","color":"d73a4a"}],"assignees":[{"login":"ana"},{"login":"luis"}],
                "statusCheckRollup":[{"name":"build","status":"COMPLETED","conclusion":"FAILURE"}]}]"""
        ).single()
        val row = PullRequestRows.row(pr, now)
        assertEquals("Clear the findings", row.title)
        assertEquals("#5 · opened 20 min ago by DavidUps · fix/report → main", row.subtitle)
        assertEquals(listOf("bug"), row.labels.map { it.text })
        assertEquals(Color(0xd73a4a), row.labels.single().tone)
        assertEquals(listOf("Review required", "✗ checks"), row.badges.map { it.text })
        assertEquals(listOf("ana", "luis"), row.people.map { it.login })
        assertEquals("8 min ago", row.time)
        assertEquals("Open", PullRequestRows.state(pr).label)
    }

    @Test
    fun `rows cope with drafts, merged pull requests and missing data`() {
        val (draft, merged, bare) = GhJson.pullRequests(
            """[{"number":1,"title":"wip","state":"OPEN","isDraft":true,"author":{"login":"ana"},"headRefName":"a","baseRefName":"main","assignees":[]},
                {"number":2,"title":"done","state":"MERGED","author":null,"headRefName":"b","baseRefName":"main","reviewDecision":"APPROVED",
                 "statusCheckRollup":[{"name":"build","status":"COMPLETED","conclusion":"SUCCESS"}]},
                {"number":3,"title":"old gh","state":"CLOSED"}]"""
        )
        assertTrue(PullRequestRows.row(draft, now).subtitle.startsWith("#1 · draft, opened"))
        // Nobody assigned: the author stands in, so there is always a face.
        assertEquals(listOf("ana"), PullRequestRows.row(draft, now).people.map { it.login })
        assertEquals("Draft", PullRequestRows.state(draft).label)
        assertEquals("Merged", PullRequestRows.state(merged).label)
        assertEquals(listOf("Approved", "✓ checks"), PullRequestRows.row(merged, now).badges.map { it.text })
        assertTrue(PullRequestRows.row(merged, now).people.isEmpty())
        assertTrue(PullRequestRows.summary(merged, now).contains("merged"))
        assertEquals("Closed", PullRequestRows.state(bare).label)
        assertTrue(PullRequestRows.row(bare, now).badges.isEmpty())
        assertEquals(Tones.GRAY, Tones.ofLabel(null))
        assertEquals(Tones.GRAY, Tones.ofLabel("nope"))
    }

    @Test
    fun `the more menu only appears when it has something to hold`() {
        // Panels that never fold use Int.MAX_VALUE; that must not overflow into "always fold".
        assertFalse(GhListDetailPanel.foldsButtons(3, Int.MAX_VALUE))
        assertFalse(GhListDetailPanel.foldsButtons(0, Int.MAX_VALUE))
        assertFalse(GhListDetailPanel.foldsButtons(4, 4))
        // One left over is shown as a button rather than hidden alone in a menu.
        assertFalse(GhListDetailPanel.foldsButtons(5, 4))
        assertTrue(GhListDetailPanel.foldsButtons(6, 4))
        assertTrue(GhListDetailPanel.foldsButtons(12, 4))
    }

    private val notifications = GhNotifications.parseList(
        """[
          {"id":"1","unread":false,"reason":"author","subject":{"title":"Clear the findings","url":"https://api.github.com/repos/DavidUps/bidaide/pulls/5","type":"PullRequest"},
           "repository":{"full_name":"DavidUps/bidaide","owner":{"avatar_url":"https://avatars.githubusercontent.com/u/1?v=4"}}},
          {"id":"2","unread":true,"reason":"review_requested","subject":{"title":"Show avatars","url":"https://api.github.com/repos/DavidUps/bidaide/pulls/3","type":"PullRequest"},
           "repository":{"full_name":"DavidUps/bidaide"}},
          {"id":"3","unread":true,"reason":"subscribed","subject":{"title":"Release 2.0","url":null,"type":"Release"},"repository":{"full_name":"acme/lib"}},
          {"id":"4","unread":true,"reason":"assign","subject":{"title":"Login tests","url":"https://api.github.com/repos/acme/app/issues/15","type":"Issue"},"repository":{"full_name":"acme/app"}}
        ]"""
    )

    @Test
    fun `the inbox sidebar offers filters and one view per repository`() {
        val views = Inbox.views(notifications)
        assertEquals(
            listOf("Bandeja", "Asignadas", "Participando", "Menciones", "Menciones a equipo", "Review solicitada", "DavidUps/bidaide", "acme/app", "acme/lib"),
            views.map { it.label },
        )
        assertEquals(listOf(null, Inbox.FILTERS, Inbox.FILTERS, Inbox.FILTERS, Inbox.FILTERS, Inbox.FILTERS, Inbox.REPOSITORIES, Inbox.REPOSITORIES, Inbox.REPOSITORIES), views.map { it.section })
        fun unread(label: String) = Inbox.unread(views.first { it.label == label }, notifications)
        assertEquals(3, unread("Bandeja"))
        assertEquals(1, unread("Asignadas"))
        // Watching a repository is not taking part in a conversation.
        assertEquals(2, unread("Participando"))
        assertEquals(1, unread("Review solicitada"))
        assertEquals(1, unread("DavidUps/bidaide"))
        assertEquals("https://avatars.githubusercontent.com/u/1?v=4", notifications.first().ownerAvatarUrl)
        assertEquals("repo:acme/app", views[7].key)
    }

    @Test
    fun `the inbox list combines view, unread and search`() {
        val views = Inbox.views(notifications)
        val repo = views.first { it.label == "DavidUps/bidaide" }
        assertEquals(listOf("1", "2"), Inbox.filter(notifications, repo, unreadOnly = false, text = "").map { it.id })
        assertEquals(listOf("2"), Inbox.filter(notifications, repo, unreadOnly = true, text = "").map { it.id })
        assertEquals(listOf("3", "4"), Inbox.filter(notifications, Inbox.ALL, unreadOnly = true, text = " ACME ").map { it.id })
        assertEquals(listOf("4"), Inbox.filter(notifications, Inbox.ALL, unreadOnly = false, text = "login").map { it.id })
        assertTrue(Inbox.filter(notifications, views.first { it.label == "Menciones" }, false, "").isEmpty())
        assertEquals("review solicitada", Inbox.reason(notifications[1]))
        assertEquals("siguiendo", Inbox.reason(notifications[2]))
        assertFalse(Inbox.views(emptyList()).any { it.section == Inbox.REPOSITORIES })
        assertEquals(listOf("api", "notifications?per_page=100&all=true"), GhNotifications.inbox())
    }
}
