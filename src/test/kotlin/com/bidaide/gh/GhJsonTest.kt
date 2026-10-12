package com.bidaide.gh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhJsonTest {
    @Test
    fun `parses pr list`() {
        val json = """
            [{"author":{"id":"x","is_bot":false,"login":"octocat","name":"Octo"},"baseRefName":"main","headRefName":"feature/a",
              "isDraft":true,"labels":[{"id":"l","name":"bug","color":"d73a4a","description":""}],"number":12,
              "reviewDecision":"","state":"OPEN","title":"Add a","updatedAt":"2026-10-01T10:20:30Z",
              "url":"https://github.com/o/r/pull/12"}]
        """.trimIndent()
        val pr = GhJson.pullRequests(json).single()
        assertEquals(12, pr.number)
        assertEquals("octocat", pr.author.display())
        assertEquals("feature/a", pr.headRefName)
        assertTrue(pr.isDraft)
        assertTrue(pr.isOpen)
        assertEquals("bug", pr.labels!!.single().name)
        assertNull(pr.files)
    }

    @Test
    fun `parses pr detail with both kinds of checks`() {
        val json = """
            {"number":5,"title":"T","state":"MERGED","isDraft":false,"author":null,"headRefName":"h","baseRefName":"b",
             "url":"u","body":"hello","additions":3,"deletions":1,"changedFiles":2,
             "files":[{"path":"a.kt","additions":3,"deletions":1}],
             "commits":[{"oid":"0123456789","messageHeadline":"msg","authors":[]}],
             "reviews":[{"author":{"login":"rev"},"state":"APPROVED","body":"","submittedAt":"2026-10-01T00:00:00Z"}],
             "comments":[{"author":{"login":"c"},"body":"hi","createdAt":"2026-10-02T00:00:00Z"}],
             "mergeable":"MERGEABLE",
             "statusCheckRollup":[
               {"__typename":"CheckRun","name":"build","workflowName":"CI","status":"COMPLETED","conclusion":"SUCCESS","detailsUrl":"d"},
               {"__typename":"StatusContext","context":"ci/legacy","state":"FAILURE","targetUrl":"t"},
               {"__typename":"CheckRun","name":"lint","workflowName":"","status":"IN_PROGRESS","conclusion":""}
             ]}
        """.trimIndent()
        val pr = GhJson.pullRequest(json)
        assertFalse(pr.isOpen)
        assertEquals("ghost", pr.author.display())
        assertEquals("a.kt", pr.files!!.single().path)
        val (run, status, pending) = pr.statusCheckRollup!!
        assertEquals("CI / build", run.displayName)
        assertTrue(run.passed)
        assertEquals("ci/legacy", status.displayName)
        assertTrue(status.failed)
        assertEquals("t", status.url)
        assertEquals("lint", pending.displayName)
        assertEquals("IN_PROGRESS", pending.result)
        assertFalse(pending.passed || pending.failed)
    }

    @Test
    fun `parses issues and empty list`() {
        assertTrue(GhJson.issues("[]").isEmpty())
        val issue = GhJson.issue("""{"number":9,"title":"I","state":"CLOSED","author":{"login":"a"},"url":"u","body":"","comments":[]}""")
        assertEquals(9, issue.number)
        assertFalse(issue.isOpen)
    }
}
