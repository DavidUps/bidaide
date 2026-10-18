package com.bidaide.github.inbox

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhNotification

/** The views of the inbox: which notifications each one shows, and how many unread ones it holds. */
object Inbox {
    /** One entry of the sidebar. [repository] is set for the per-repository entries. */
    class View(val label: String, val section: String?, val repository: String? = null, private val accepts: (GhNotification) -> Boolean) {
        fun matches(notification: GhNotification): Boolean = accepts(notification)

        /** Stable across reloads, to keep the same view selected. */
        val key: String get() = repository?.let { "repo:$it" } ?: label
    }

    /** Reasons that mean the user is personally involved, as opposed to just watching the repository. */
    private val PARTICIPATING = setOf("author", "comment", "mention", "team_mention", "review_requested", "assign", "state_change", "manual", "approval_requested")

    val FILTERS: String get() = BidaideBundle.message("github.inbox.filters")
    val REPOSITORIES: String get() = BidaideBundle.message("github.inbox.repositories")

    /** A single instance, so the panel can tell it apart; its label is the one of the language at startup. */
    val ALL = View(BidaideBundle.message("github.inbox.all"), null) { true }

    private val FIXED: List<View>
        get() = listOf(
            ALL,
            View(BidaideBundle.message("github.inbox.assigned"), FILTERS) { it.reason == "assign" },
            View(BidaideBundle.message("github.inbox.participating"), FILTERS) { it.reason in PARTICIPATING },
            View(BidaideBundle.message("github.inbox.mentioned"), FILTERS) { it.reason == "mention" },
            View(BidaideBundle.message("github.inbox.teamMentioned"), FILTERS) { it.reason == "team_mention" },
            View(BidaideBundle.message("github.inbox.reviewRequested"), FILTERS) { it.reason == "review_requested" },
        )

    /** The fixed views followed by one per repository that has notifications, busiest first. */
    fun views(notifications: List<GhNotification>): List<View> {
        val repositories = notifications.groupingBy { it.repository }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { (repository, _) -> View(repository, REPOSITORIES, repository) { it.repository == repository } }
        return FIXED + repositories
    }

    fun unread(view: View, notifications: List<GhNotification>): Int = notifications.count { it.unread && view.matches(it) }

    /** What the list shows: the view, optionally only unread, optionally narrowed by text in title or repository. */
    fun filter(notifications: List<GhNotification>, view: View, unreadOnly: Boolean, text: String): List<GhNotification> {
        val needle = text.trim()
        return notifications.filter { n ->
            view.matches(n) && (!unreadOnly || n.unread) &&
                (needle.isEmpty() || n.title.contains(needle, ignoreCase = true) || n.repository.contains(needle, ignoreCase = true))
        }
    }

    /** GitHub's wording for why a notification arrived. */
    fun reason(notification: GhNotification): String = when (notification.reason) {
        "assign" -> BidaideBundle.message("github.inbox.reason.assign")
        "author" -> BidaideBundle.message("github.inbox.reason.author")
        "comment" -> BidaideBundle.message("github.inbox.reason.comment")
        "ci_activity" -> "CI"
        "invitation" -> BidaideBundle.message("github.inbox.reason.invitation")
        "manual" -> BidaideBundle.message("github.inbox.reason.manual")
        "mention" -> BidaideBundle.message("github.inbox.reason.mention")
        "team_mention" -> BidaideBundle.message("github.inbox.reason.teamMention")
        "review_requested" -> BidaideBundle.message("github.inbox.reason.reviewRequested")
        "security_alert" -> BidaideBundle.message("github.inbox.reason.securityAlert")
        "state_change" -> BidaideBundle.message("github.inbox.reason.stateChange")
        "subscribed" -> BidaideBundle.message("github.inbox.reason.subscribed")
        "approval_requested" -> BidaideBundle.message("github.inbox.reason.approvalRequested")
        else -> notification.reason.replace('_', ' ')
    }
}
