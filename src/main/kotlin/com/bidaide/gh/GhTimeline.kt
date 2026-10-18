package com.bidaide.gh

import com.bidaide.BidaideBundle

/** One entry of the activity feed of an issue or pull request. [body] is Markdown, only set for comments and reviews. */
data class GhTimelineEvent(val kind: String, val actor: String, val date: String?, val summary: String, val body: String? = null)

/** The full conversation of an issue or pull request (comments, reviews, commits, label changes...) in order. REST only. */
object GhTimeline {
    fun args(number: Int) = listOf("api", "repos/{owner}/{repo}/issues/$number/timeline?per_page=100", "--paginate", "--slurp")

    /** Events that say nothing a reader cares about. */
    private val NOISE = setOf("subscribed", "unsubscribed", "mentioned", "copilot_work_started", "copilot_work_finished")

    fun parse(slurped: String): List<GhTimelineEvent> = slurpedObjects(slurped).mapNotNull { e ->
        val kind = e.string("event") ?: return@mapNotNull null
        if (kind in NOISE) return@mapNotNull null
        val actor = e.obj("actor")?.string("login") ?: e.obj("user")?.string("login") ?: e.obj("author")?.string("name") ?: "ghost"
        val date = e.string("created_at") ?: e.string("submitted_at") ?: e.obj("author")?.string("date")
        val body = e.string("body")?.takeIf { it.isNotBlank() }
        val summary = when (kind) {
            "commented" -> BidaideBundle.message("gh.timeline.commented")
            "reviewed" -> when (e.string("state")?.lowercase()) {
                "approved" -> BidaideBundle.message("gh.timeline.approved")
                "changes_requested" -> BidaideBundle.message("gh.timeline.changesRequested")
                "dismissed" -> BidaideBundle.message("gh.timeline.dismissed")
                "pending" -> BidaideBundle.message("gh.timeline.pending")
                else -> BidaideBundle.message("gh.timeline.reviewed")
            }
            "committed" -> "commit ${e.string("sha").orEmpty().take(7)} ${e.string("message").orEmpty().lineSequence().firstOrNull().orEmpty()}"
            "labeled" -> BidaideBundle.message("gh.timeline.labeled", e.obj("label")?.string("name").orEmpty())
            "unlabeled" -> BidaideBundle.message("gh.timeline.unlabeled", e.obj("label")?.string("name").orEmpty())
            "assigned" -> BidaideBundle.message("gh.timeline.assigned", e.obj("assignee")?.string("login").orEmpty())
            "unassigned" -> BidaideBundle.message("gh.timeline.unassigned", e.obj("assignee")?.string("login").orEmpty())
            "review_requested" -> BidaideBundle.message("gh.timeline.reviewRequested", reviewer(e))
            "review_request_removed" -> BidaideBundle.message("gh.timeline.reviewRequestRemoved", reviewer(e))
            "renamed" -> BidaideBundle.message("gh.timeline.renamed", e.obj("rename")?.string("from").orEmpty(), e.obj("rename")?.string("to").orEmpty())
            "milestoned" -> BidaideBundle.message("gh.timeline.milestoned", e.obj("milestone")?.string("title").orEmpty())
            "demilestoned" -> BidaideBundle.message("gh.timeline.demilestoned", e.obj("milestone")?.string("title").orEmpty())
            "closed" -> BidaideBundle.message("gh.timeline.closed")
            "reopened" -> BidaideBundle.message("gh.timeline.reopened")
            "merged" -> BidaideBundle.message("gh.timeline.merged")
            "head_ref_force_pushed" -> BidaideBundle.message("gh.timeline.forcePushed")
            "head_ref_deleted" -> BidaideBundle.message("gh.timeline.branchDeleted")
            "head_ref_restored" -> BidaideBundle.message("gh.timeline.branchRestored")
            "ready_for_review" -> BidaideBundle.message("gh.timeline.readyForReview")
            "convert_to_draft" -> BidaideBundle.message("gh.timeline.convertedToDraft")
            "auto_merge_enabled" -> BidaideBundle.message("gh.timeline.autoMergeEnabled")
            "auto_merge_disabled" -> BidaideBundle.message("gh.timeline.autoMergeDisabled")
            "cross-referenced" -> e.obj("source")?.obj("issue")?.let {
                BidaideBundle.message("gh.timeline.crossReferenced", it.int("number"), it.string("title").orEmpty())
            } ?: BidaideBundle.message("gh.timeline.mentioned")
            "referenced" -> BidaideBundle.message("gh.timeline.referenced", e.string("commit_id").orEmpty().take(7))
            else -> kind.replace('_', ' ')
        }
        GhTimelineEvent(kind, actor, date, summary.trim(), body)
    }

    private fun reviewer(e: com.google.gson.JsonObject): String =
        e.obj("requested_reviewer")?.string("login") ?: e.obj("requested_team")?.string("name") ?: "?"
}
