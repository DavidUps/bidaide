package com.bidaide

import com.bidaide.gh.GhIssue
import com.bidaide.gh.GhLabel
import com.bidaide.gh.GhMergeRules
import com.bidaide.gh.GhMilestone
import com.bidaide.gh.GhUser
import com.bidaide.gh.MergeRequirement.State
import com.bidaide.github.issues.IssueBoard
import com.bidaide.github.issues.IssueBoard.GroupBy
import com.bidaide.github.pr.PullRequestHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardAndRulesTest {

    private fun state(
        mergeState: String = "BLOCKED", mergeable: String = "MERGEABLE", decision: String? = "REVIEW_REQUIRED", draft: Boolean = false,
        reviews: String = "", threads: String = "", checks: String? = "",
    ) = """{"data":{"repository":{"pullRequest":{"isDraft":$draft,"mergeable":"$mergeable","mergeStateStatus":"$mergeState",
        "reviewDecision":${decision?.let { "\"$it\"" }},"baseRefName":"main","latestOpinionatedReviews":{"nodes":[$reviews]},
        "reviewThreads":{"nodes":[$threads]},
        "commits":{"nodes":[{"commit":{"statusCheckRollup":${checks?.let { "{\"contexts\":{\"nodes\":[$it]}}" }}}}]}}}}}"""

    private fun review(state: String, login: String) = """{"state":"$state","author":{"login":"$login"}}"""
    private fun run(name: String, conclusion: String?, required: Boolean) =
        """{"__typename":"CheckRun","name":"$name","status":"${if (conclusion == null) "IN_PROGRESS" else "COMPLETED"}","conclusion":${conclusion?.let { "\"$it\"" }},"isRequired":$required}"""

    private val rules = """[
        {"type":"pull_request","parameters":{"required_approving_review_count":2,"required_review_thread_resolution":true,"require_code_owner_review":false}},
        {"type":"required_status_checks","parameters":{"strict_required_status_checks_policy":true,"required_status_checks":[{"context":"build"},{"context":"lint"}]}},
        {"type":"required_linear_history"}]"""

    private fun GhMergeRulesResult(json: String, rules: String?, protection: String? = null) = GhMergeRules.evaluate(json, rules, protection)

    @Test
    fun `a pull request short of approvals and checks is not ready`() {
        val status = GhMergeRulesResult(
            state(reviews = review("APPROVED", "ana"), threads = """{"isResolved":true},{"isResolved":false}""", checks = run("build", "SUCCESS", true)),
            rules,
        )
        val byLabel = status.requirements.associateBy { it.label }
        assertEquals(State.PENDING, byLabel.getValue("Reviews").state)
        assertEquals("1 of 2 required approvals", byLabel.getValue("Reviews").detail)
        // "lint" is demanded by the rules but has not reported at all yet.
        assertEquals(State.PENDING, byLabel.getValue("Required checks").state)
        assertTrue(byLabel.getValue("Required checks").detail.contains("lint"))
        assertEquals(State.FAIL, byLabel.getValue("Conversations").state)
        assertEquals(State.OK, byLabel.getValue("Branch up to date").state)
        assertEquals(State.OK, byLabel.getValue("Conflicts").state)
        assertEquals(State.INFO, byLabel.getValue("Linear history").state)
        assertFalse(status.ready)
        assertEquals("Blocked: does not meet the requirements of main", status.verdict)
    }

    @Test
    fun `a pull request that satisfies every rule is ready`() {
        val status = GhMergeRulesResult(
            state(
                mergeState = "CLEAN", decision = "APPROVED", reviews = review("APPROVED", "ana") + "," + review("APPROVED", "luis"),
                threads = """{"isResolved":true}""", checks = run("build", "SUCCESS", true) + "," + run("lint", "SKIPPED", true) + "," + run("extra", "FAILURE", false),
            ),
            rules,
        )
        assertTrue(status.requirements.toString(), status.ready)
        assertEquals("Ready to merge", status.verdict)
        val byLabel = status.requirements.associateBy { it.label }
        assertEquals("2 of 2 pass", byLabel.getValue("Required checks").detail)
        assertEquals(State.INFO, byLabel.getValue("Optional checks").state)
        assertTrue(PullRequestHtml.mergeStatus(status).contains("Ready to merge"))
    }

    @Test
    fun `requested changes, failed required checks, stale branch and conflicts all block`() {
        val status = GhMergeRulesResult(
            state(
                mergeState = "BEHIND", mergeable = "CONFLICTING", decision = "CHANGES_REQUESTED", draft = true,
                reviews = review("CHANGES_REQUESTED", "ana") + "," + review("APPROVED", "luis"),
                checks = run("build", "FAILURE", true) + "," + run("lint", null, true),
            ),
            rules,
        )
        assertEquals(
            setOf("Draft", "Reviews", "Required checks", "Branch up to date", "Conflicts"),
            status.failing.map { it.label }.toSet(),
        )
        assertTrue(status.failing.first { it.label == "Reviews" }.detail.startsWith("ana requested changes"))
        assertEquals("failing: build", status.failing.first { it.label == "Required checks" }.detail)
    }

    @Test
    fun `classic branch protection counts like a ruleset`() {
        val protection = """{"data":{"repository":{"pullRequest":{"baseRef":{"branchProtectionRule":{"requiresApprovingReviews":true,
            "requiredApprovingReviewCount":1,"requiresConversationResolution":true,"requiresStrictStatusChecks":false,
            "requiredStatusCheckContexts":["ci"],"requiresLinearHistory":false,"requiresCommitSignatures":true}}}}}}"""
        val status = GhMergeRulesResult(state(checks = "", decision = "REVIEW_REQUIRED"), "[]", protection)
        val byLabel = status.requirements.associateBy { it.label }
        assertEquals("0 of 1 required approvals", byLabel.getValue("Reviews").detail)
        assertEquals("running or not started: ci", byLabel.getValue("Required checks").detail)
        assertEquals(State.OK, byLabel.getValue("Conversations").state)
        assertEquals(State.INFO, byLabel.getValue("Signed commits").state)
        assertNull(byLabel["Branch up to date"])
    }

    @Test
    fun `without readable rules the pull request's own data still gives an answer`() {
        // The real shape of a PR on a private free-plan repository: no rollup, no reviews, rules API refused.
        val json = """{"data":{"repository":{"pullRequest":{"isDraft":false,"mergeable":"CONFLICTING","mergeStateStatus":"DIRTY","reviewDecision":null,
            "baseRefName":"main","latestOpinionatedReviews":{"nodes":[]},"reviewThreads":{"nodes":[{"isResolved":false}]},
            "commits":{"nodes":[{"commit":{"statusCheckRollup":null}}]}}}}}"""
        val status = GhMergeRulesResult(json, null, null)
        val byLabel = status.requirements.associateBy { it.label }
        assertEquals("Has conflicts", status.verdict)
        assertEquals(State.INFO, byLabel.getValue("Reviews").state)
        assertEquals("none", byLabel.getValue("Required checks").detail)
        assertEquals(State.INFO, byLabel.getValue("Conversations").state)
        assertEquals(State.FAIL, byLabel.getValue("Conflicts").state)
        assertEquals(State.INFO, byLabel.getValue("Branch rules").state)
        assertEquals("main", GhMergeRules.baseBranch(json))
        assertEquals(listOf("api", "repos/{owner}/{repo}/rules/branches/release/1.0"), GhMergeRules.rules("release/1.0"))
    }

    // --- Issue board ---

    private val issues = listOf(
        GhIssue(number = 1, title = "Crash al abrir", state = "OPEN", author = GhUser("ana"), labels = listOf(GhLabel("bug"), GhLabel("Android")), assignees = listOf(GhUser("david"))),
        GhIssue(number = 2, title = "Modo oscuro", state = "OPEN", labels = listOf(GhLabel("feature")), milestone = GhMilestone("v1")),
        GhIssue(number = 3, title = "Sin clasificar", state = "CLOSED"),
    )

    @Test
    fun `issues are grouped into columns`() {
        val byLabel = IssueBoard.columns(issues, GroupBy.LABEL)
        assertEquals(listOf("No label", "Android", "bug", "feature"), byLabel.map { it.title })
        assertEquals(listOf(listOf("3"), listOf("1"), listOf("1"), listOf("2")), byLabel.map { column -> column.cards.map { it.id } })
        assertEquals("#1 Crash al abrir", byLabel[1].cards.single().title)
        // The label is the column; the card shows who and to whom instead.
        assertEquals("ana · @david", byLabel[1].cards.single().subtitle)

        val byMilestone = IssueBoard.columns(issues, GroupBy.MILESTONE, known = listOf("v2", "v1"))
        assertEquals(listOf("No milestone", "v1", "v2"), byMilestone.map { it.title })
        assertEquals(listOf(2, 1, 0), byMilestone.map { it.cards.size })

        val byState = IssueBoard.columns(issues, GroupBy.STATE)
        assertEquals(listOf("Open" to 2, "Closed" to 1), byState.map { it.title to it.cards.size })

        assertEquals(listOf("Unassigned", "david"), IssueBoard.columns(issues, GroupBy.ASSIGNEE).map { it.title })
        assertEquals(listOf("No label"), IssueBoard.columns(emptyList(), GroupBy.LABEL).map { it.title })
    }

    @Test
    fun `moving a card changes only the grouped value`() {
        assertEquals(
            listOf("issue", "edit", "7", "--add-label", "doing", "--remove-label", "todo"),
            IssueBoard.moveCommand(7, GroupBy.LABEL, "todo", "doing"),
        )
        assertEquals(listOf("issue", "edit", "7", "--add-label", "todo"), IssueBoard.moveCommand(7, GroupBy.LABEL, IssueBoard.NONE, "todo"))
        assertEquals(listOf("issue", "edit", "7", "--remove-label", "todo"), IssueBoard.moveCommand(7, GroupBy.LABEL, "todo", IssueBoard.NONE))
        assertEquals(
            listOf("issue", "edit", "7", "--add-assignee", "ana", "--remove-assignee", "david"),
            IssueBoard.moveCommand(7, GroupBy.ASSIGNEE, "david", "ana"),
        )
        assertEquals(listOf("issue", "edit", "7", "--milestone", "v2"), IssueBoard.moveCommand(7, GroupBy.MILESTONE, "v1", "v2"))
        assertEquals(listOf("issue", "edit", "7", "--remove-milestone"), IssueBoard.moveCommand(7, GroupBy.MILESTONE, "v1", IssueBoard.NONE))
        assertEquals(listOf("issue", "close", "7"), IssueBoard.moveCommand(7, GroupBy.STATE, "OPEN", "CLOSED"))
        assertEquals(listOf("issue", "reopen", "7"), IssueBoard.moveCommand(7, GroupBy.STATE, "CLOSED", "OPEN"))
        assertEquals("Issue #7 closed", IssueBoard.moveDescription(7, GroupBy.STATE, "CLOSED"))
        assertEquals("Issue #7 → no label", IssueBoard.moveDescription(7, GroupBy.LABEL, IssueBoard.NONE))
    }
}
