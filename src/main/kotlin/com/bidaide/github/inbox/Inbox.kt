package com.bidaide.github.inbox

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

    const val FILTERS = "Filtros"
    const val REPOSITORIES = "Repositorios"

    val ALL = View("Bandeja", null) { true }

    private val FIXED = listOf(
        ALL,
        View("Asignadas", FILTERS) { it.reason == "assign" },
        View("Participando", FILTERS) { it.reason in PARTICIPATING },
        View("Menciones", FILTERS) { it.reason == "mention" },
        View("Menciones a equipo", FILTERS) { it.reason == "team_mention" },
        View("Review solicitada", FILTERS) { it.reason == "review_requested" },
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
        "assign" -> "asignada"
        "author" -> "autor"
        "comment" -> "comentaste"
        "ci_activity" -> "CI"
        "invitation" -> "invitación"
        "manual" -> "suscrito"
        "mention" -> "mención"
        "team_mention" -> "mención a equipo"
        "review_requested" -> "review solicitada"
        "security_alert" -> "alerta de seguridad"
        "state_change" -> "cambio de estado"
        "subscribed" -> "siguiendo"
        "approval_requested" -> "aprobación pedida"
        else -> notification.reason.replace('_', ' ')
    }
}
