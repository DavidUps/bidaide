package com.bidaide.gh

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

// Shapes of `gh ... --json` output. Gson leaves absent fields null, so defaults only apply to fields gh always sends.

data class GhUser(val login: String? = null) {
    override fun toString() = login ?: GHOST
}

private const val GHOST = "ghost"

/** GitHub sends a null author for deleted accounts and shows them as "ghost". */
fun GhUser?.display(): String = this?.login ?: GHOST

data class GhMilestone(val title: String? = null)

data class GhLabel(val name: String = "", val color: String? = null)

data class GhFile(val path: String = "", val additions: Int = 0, val deletions: Int = 0)

data class GhCommit(val oid: String = "", val messageHeadline: String = "")

data class GhReview(val author: GhUser? = null, val state: String? = null, val body: String? = null, val submittedAt: String? = null)

data class GhComment(val author: GhUser? = null, val body: String? = null, val createdAt: String? = null)

/** Either a CheckRun (name/status/conclusion/detailsUrl) or a StatusContext (context/state/targetUrl). */
data class GhCheck(
    val name: String? = null,
    val workflowName: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    val detailsUrl: String? = null,
    val context: String? = null,
    val state: String? = null,
    val targetUrl: String? = null,
) {
    val displayName: String get() = listOfNotNull(workflowName?.takeIf { it.isNotBlank() }, name ?: context).joinToString(" / ")
    val result: String get() = (conclusion?.takeIf { it.isNotBlank() } ?: state ?: status ?: "PENDING").uppercase()
    val url: String? get() = detailsUrl ?: targetUrl
    val passed: Boolean get() = result in setOf("SUCCESS", "NEUTRAL", "SKIPPED")
    val failed: Boolean get() = result in setOf("FAILURE", "ERROR", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE")
}

data class GhPullRequest(
    val number: Int = 0,
    val title: String = "",
    val state: String = "",
    val isDraft: Boolean = false,
    val author: GhUser? = null,
    val headRefName: String = "",
    val baseRefName: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val url: String = "",
    val reviewDecision: String? = null,
    val labels: List<GhLabel>? = null,
    val body: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changedFiles: Int = 0,
    val files: List<GhFile>? = null,
    val commits: List<GhCommit>? = null,
    val reviews: List<GhReview>? = null,
    val comments: List<GhComment>? = null,
    val mergeable: String? = null,
    val statusCheckRollup: List<GhCheck>? = null,
    val assignees: List<GhUser>? = null,
    val baseRefOid: String? = null,
    val headRefOid: String? = null,
    /** Non-null while auto-merge is enabled. */
    val autoMergeRequest: Any? = null,
    val milestone: GhMilestone? = null,
    /** Users and teams still asked to review. */
    val reviewRequests: List<GhReviewRequest>? = null,
    /** The most recent review of each reviewer. */
    val latestReviews: List<GhReview>? = null,
    /** Not part of the gh JSON: filled in separately from [GhTimeline]. */
    @Transient val timeline: List<GhTimelineEvent>? = null,
    /** Not part of the gh JSON: the merge checklist from [GhMergeRules], for open pull requests. */
    @Transient val mergeStatus: GhMergeStatus? = null,
) {
    val checks: List<GhCheck> get() = statusCheckRollup.orEmpty()
    val checksFailed: Boolean get() = checks.any { it.failed }
    val checksPassed: Boolean get() = checks.isNotEmpty() && checks.all { it.passed }

    val isOpen: Boolean get() = state.equals("OPEN", ignoreCase = true)
}

/** A changed file from `GET /pulls/N/files`. [patch] is missing for binary files and very large diffs. */
data class GhPrFile(
    val filename: String = "",
    val status: String = "",
    @SerializedName("previous_filename") val previousFilename: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
)

data class GhStep(val name: String = "", val status: String? = null, val conclusion: String? = null, val number: Int = 0)

data class GhJob(
    val databaseId: Long = 0,
    val name: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    val url: String? = null,
    val steps: List<GhStep>? = null,
)

/** A GitHub Actions workflow run. */
data class GhRun(
    val databaseId: Long = 0,
    val displayTitle: String = "",
    val workflowName: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    val headBranch: String? = null,
    val headSha: String? = null,
    val event: String? = null,
    val createdAt: String? = null,
    val url: String = "",
    val jobs: List<GhJob>? = null,
) {
    val isFinished: Boolean get() = status.equals("completed", ignoreCase = true)
    val failed: Boolean get() = RunResults.failed(conclusion)
    val result: String get() = RunResults.label(status, conclusion)
}

object RunResults {
    private val FAILURES = setOf("failure", "timed_out", "startup_failure", "action_required")

    fun failed(conclusion: String?) = conclusion?.lowercase() in FAILURES

    fun passed(conclusion: String?) = conclusion.equals("success", ignoreCase = true)

    /** The conclusion once finished, otherwise the live status (queued, in_progress...). */
    fun label(status: String?, conclusion: String?): String = conclusion?.takeIf { it.isNotBlank() } ?: status.orEmpty()
}

data class GhWorkflow(val id: Long = 0, val name: String = "", val path: String = "", val state: String = "")

class GhViewedStates(val pullRequestId: String, val viewedPaths: Set<String>)

data class GhIssue(
    val number: Int = 0,
    val title: String = "",
    val state: String = "",
    val author: GhUser? = null,
    val labels: List<GhLabel>? = null,
    val assignees: List<GhUser>? = null,
    val updatedAt: String? = null,
    val url: String = "",
    val body: String? = null,
    val comments: List<GhComment>? = null,
    val milestone: GhMilestone? = null,
    @Transient val timeline: List<GhTimelineEvent>? = null,
) {
    val isOpen: Boolean get() = state.equals("OPEN", ignoreCase = true)
}

/** One commit with its own changes: [parent] is null for a root commit. */
class GhCommitDetail(val sha: String, val parent: String?, val files: List<GhPrFile>)

object GhJson {
    private val gson = Gson()

    fun pullRequests(json: String): List<GhPullRequest> =
        gson.fromJson<List<GhPullRequest>>(json, object : TypeToken<List<GhPullRequest>>() {}.type).orEmpty()

    fun pullRequest(json: String): GhPullRequest = gson.fromJson(json, GhPullRequest::class.java)

    /** `gh api --paginate --slurp` wraps the pages in an outer array. */
    fun prFiles(slurped: String): List<GhPrFile> =
        gson.fromJson<List<List<GhPrFile>>>(slurped, object : TypeToken<List<List<GhPrFile>>>() {}.type).orEmpty().flatten()

    /** Parses the slurped pages of [GhCommands.prViewedStates]: PR node id and the paths marked as viewed. */
    fun viewedStates(slurped: String): GhViewedStates {
        var id = ""
        val viewed = HashSet<String>()
        for (page in JsonParser.parseString(slurped).asJsonArray) {
            val pr = page.asJsonObject.getAsJsonObject("data").getAsJsonObject("repository").getAsJsonObject("pullRequest")
            id = pr.get("id").asString
            for (node in pr.getAsJsonObject("files").getAsJsonArray("nodes")) {
                val file = node.asJsonObject
                if (file.get("viewerViewedState").asString == "VIEWED") viewed += file.get("path").asString
            }
        }
        return GhViewedStates(id, viewed)
    }

    fun commitDetail(json: String): GhCommitDetail {
        val commit = parseObject(json)
        val files = gson.fromJson<List<GhPrFile>>(commit.get("files"), object : TypeToken<List<GhPrFile>>() {}.type).orEmpty()
        return GhCommitDetail(commit.string("sha").orEmpty(), commit.array("parents").firstOrNull()?.string("sha"), files)
    }

    fun runs(json: String): List<GhRun> = gson.fromJson<List<GhRun>>(json, object : TypeToken<List<GhRun>>() {}.type).orEmpty()

    fun run(json: String): GhRun = gson.fromJson(json, GhRun::class.java)

    fun workflows(json: String): List<GhWorkflow> =
        gson.fromJson<List<GhWorkflow>>(json, object : TypeToken<List<GhWorkflow>>() {}.type).orEmpty()

    fun issues(json: String): List<GhIssue> =
        gson.fromJson<List<GhIssue>>(json, object : TypeToken<List<GhIssue>>() {}.type).orEmpty()

    fun issue(json: String): GhIssue = gson.fromJson(json, GhIssue::class.java)
}
