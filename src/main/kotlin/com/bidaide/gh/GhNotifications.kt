package com.bidaide.gh

import com.bidaide.BidaideBundle

/** An entry of the GitHub notifications inbox. */
data class GhNotification(
    val id: String,
    val unread: Boolean,
    val reason: String,
    val updatedAt: String?,
    val title: String,
    /** PullRequest, Issue, Commit, Release, Discussion, CheckSuite... */
    val type: String,
    /** API URL of the subject; missing for some types. */
    val subjectUrl: String?,
    val repository: String,
    /** Avatar of the repository's owner; the notifications API says nothing about who took part. */
    val ownerAvatarUrl: String? = null,
) {
    /** Issue or pull request number, when the subject is one. */
    val number: Int? get() = subjectUrl?.substringAfterLast('/')?.toIntOrNull()?.takeIf { type == "PullRequest" || type == "Issue" }

    val webUrl: String get() = GhNotifications.webUrl(subjectUrl, repository)

    val reasonLabel: String get() = GhNotifications.REASONS[reason] ?: reason.replace('_', ' ')
}

object GhNotifications {
    val REASONS: Map<String, String>
        get() = mapOf(
            "assign" to BidaideBundle.message("gh.notification.reason.assign"),
            "author" to BidaideBundle.message("gh.notification.reason.author"),
            "comment" to BidaideBundle.message("gh.notification.reason.comment"),
            "ci_activity" to BidaideBundle.message("gh.notification.reason.ciActivity"),
            "invitation" to BidaideBundle.message("gh.notification.reason.invitation"),
            "manual" to BidaideBundle.message("gh.notification.reason.manual"),
            "mention" to BidaideBundle.message("gh.notification.reason.mention"),
            "review_requested" to BidaideBundle.message("gh.notification.reason.reviewRequested"),
            "security_alert" to BidaideBundle.message("gh.notification.reason.securityAlert"),
            "state_change" to BidaideBundle.message("gh.notification.reason.stateChange"),
            "subscribed" to BidaideBundle.message("gh.notification.reason.subscribed"),
            "team_mention" to BidaideBundle.message("gh.notification.reason.teamMention"),
            "approval_requested" to BidaideBundle.message("gh.notification.reason.approvalRequested"),
        )

    /** [thisRepo] limits the inbox to the repository gh runs in; [all] includes already read notifications. */
    fun list(thisRepo: Boolean, all: Boolean) =
        listOf("api", "${if (thisRepo) "repos/{owner}/{repo}/" else ""}notifications?per_page=50&all=$all")

    /** The whole inbox, read and unread, of every repository: the views of the inbox tab are filters over this. */
    fun inbox() = listOf("api", "notifications?per_page=100&all=true")

    fun thread(id: String) = listOf("api", "notifications/threads/$id")

    fun markRead(id: String) = listOf("api", "--method", "PATCH", "notifications/threads/$id")

    /** Removes it from the inbox for good ("done" on github.com). */
    fun done(id: String) = listOf("api", "--method", "DELETE", "notifications/threads/$id")

    fun unsubscribe(id: String) = listOf("api", "--method", "DELETE", "notifications/threads/$id/subscription")

    fun markAllRead(thisRepo: Boolean) =
        listOf("api", "--method", "PUT", "${if (thisRepo) "repos/{owner}/{repo}/" else ""}notifications", "-F", "read=true")

    fun parseList(json: String): List<GhNotification> =
        com.google.gson.JsonParser.parseString(json).asJsonArray.filter { it.isJsonObject }.map { parse(it.asJsonObject) }

    fun parse(json: String): GhNotification = parse(parseObject(json))

    private fun parse(n: com.google.gson.JsonObject): GhNotification {
        val subject = n.obj("subject")
        return GhNotification(
            id = n.string("id").orEmpty(),
            unread = n.bool("unread"),
            reason = n.string("reason").orEmpty(),
            updatedAt = n.string("updated_at"),
            title = subject?.string("title").orEmpty(),
            type = subject?.string("type").orEmpty(),
            subjectUrl = subject?.string("url"),
            repository = n.obj("repository")?.string("full_name").orEmpty(),
            ownerAvatarUrl = n.obj("repository")?.obj("owner")?.string("avatar_url"),
        )
    }

    private val API_SUBJECT = Regex("""^https://api\.github\.com/repos/([^/]+/[^/]+)/(pulls|issues|commits|releases)/(.+)$""")

    /** The notification only carries API URLs; this is the page a person would open. Falls back to the repository. */
    fun webUrl(subjectUrl: String?, repository: String): String {
        val match = subjectUrl?.let(API_SUBJECT::find) ?: return "https://github.com/$repository"
        val (repo, kind, rest) = match.destructured
        return when (kind) {
            "pulls" -> "https://github.com/$repo/pull/$rest"
            "issues" -> "https://github.com/$repo/issues/$rest"
            "commits" -> "https://github.com/$repo/commit/$rest"
            // The API id of a release is not the tag its page is addressed by.
            else -> "https://github.com/$repo/releases"
        }
    }
}
