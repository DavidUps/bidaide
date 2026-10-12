package com.bidaide.gh

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
            "commented" -> "comentó"
            "reviewed" -> when (e.string("state")?.lowercase()) {
                "approved" -> "aprobó"
                "changes_requested" -> "pidió cambios"
                "dismissed" -> "review descartada"
                "pending" -> "review pendiente (solo la ves tú)"
                else -> "revisó"
            }
            "committed" -> "commit ${e.string("sha").orEmpty().take(7)} ${e.string("message").orEmpty().lineSequence().firstOrNull().orEmpty()}"
            "labeled" -> "añadió la label ${e.obj("label")?.string("name").orEmpty()}"
            "unlabeled" -> "quitó la label ${e.obj("label")?.string("name").orEmpty()}"
            "assigned" -> "asignó a ${e.obj("assignee")?.string("login").orEmpty()}"
            "unassigned" -> "desasignó a ${e.obj("assignee")?.string("login").orEmpty()}"
            "review_requested" -> "pidió review a ${reviewer(e)}"
            "review_request_removed" -> "retiró la petición de review a ${reviewer(e)}"
            "renamed" -> "cambió el título: «${e.obj("rename")?.string("from").orEmpty()}» → «${e.obj("rename")?.string("to").orEmpty()}»"
            "milestoned" -> "añadió al milestone ${e.obj("milestone")?.string("title").orEmpty()}"
            "demilestoned" -> "quitó del milestone ${e.obj("milestone")?.string("title").orEmpty()}"
            "closed" -> "cerró"
            "reopened" -> "reabrió"
            "merged" -> "fusionó"
            "head_ref_force_pushed" -> "hizo force-push"
            "head_ref_deleted" -> "borró la rama"
            "head_ref_restored" -> "restauró la rama"
            "ready_for_review" -> "marcó como lista para revisión"
            "convert_to_draft" -> "convirtió en borrador"
            "auto_merge_enabled" -> "activó auto-merge"
            "auto_merge_disabled" -> "desactivó auto-merge"
            "cross-referenced" -> e.obj("source")?.obj("issue")?.let { "mencionó esto en #${it.int("number")} ${it.string("title").orEmpty()}" } ?: "mencionó esto"
            "referenced" -> "referenció esto en el commit ${e.string("commit_id").orEmpty().take(7)}"
            else -> kind.replace('_', ' ')
        }
        GhTimelineEvent(kind, actor, date, summary.trim(), body)
    }

    private fun reviewer(e: com.google.gson.JsonObject): String =
        e.obj("requested_reviewer")?.string("login") ?: e.obj("requested_team")?.string("name") ?: "?"
}
