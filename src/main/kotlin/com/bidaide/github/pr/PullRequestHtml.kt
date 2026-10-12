package com.bidaide.github.pr

import com.bidaide.gh.GhMergeStatus
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.display
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Html.esc
import com.bidaide.github.ui.Markdown

object PullRequestHtml {
    fun render(pr: GhPullRequest): String = buildString {
        append("<h2>${esc(pr.title)} <font color=\"gray\">#${pr.number}</font></h2>")
        append("<p>${esc(stateLabel(pr))} · <b>${esc(pr.author.display())}</b> · ")
        append("<code>${esc(pr.headRefName)}</code> → <code>${esc(pr.baseRefName)}</code> · ")
        append("<font color=\"green\">+${pr.additions}</font> <font color=\"red\">−${pr.deletions}</font> en ${pr.changedFiles} archivos")
        append(" · ${Html.link(pr.url, "GitHub")}</p>")
        val meta = listOfNotNull(
            pr.reviewDecision?.takeIf { it.isNotBlank() }?.let { "Review: ${esc(it)}" },
            pr.mergeable?.takeIf { it.isNotBlank() }?.let { "Mergeable: ${esc(it)}" },
            pr.assignees?.takeIf { it.isNotEmpty() }?.let { "Asignada a: ${esc(it.joinToString())}" },
            pr.milestone?.title?.let { "Milestone: ${esc(it)}" },
            "Auto-merge activado".takeIf { pr.autoMergeRequest != null },
            Html.labels(pr.labels).takeIf { it.isNotBlank() },
        )
        if (meta.isNotEmpty()) append("<p>${meta.joinToString(" · ")}</p>")
        pr.mergeStatus?.let { append(mergeStatus(it)) }
        append(Markdown.toHtml(pr.body).ifEmpty { "<p><i>Sin descripción.</i></p>" })

        append(Html.section("Checks", pr.statusCheckRollup.orEmpty().joinToString("") { check ->
            val color = when {
                check.passed -> "green"
                check.failed -> "red"
                else -> "gray"
            }
            "<div><font color=\"$color\">${esc(check.result)}</font> ${Html.link(check.url, check.displayName)}</div>"
        }))
        val timeline = pr.timeline
        if (timeline != null) {
            append(Html.section("Actividad", Html.timeline(timeline)))
        } else {
            append(Html.section("Reviews", pr.reviews.orEmpty().joinToString("") { review ->
                val body = Markdown.toHtml(review.body)
                "<p><b>${esc(review.author.display())}</b> ${esc(review.state)} <small>${Html.date(review.submittedAt)}</small>$body</p>"
            }))
            append(Html.section("Comentarios", Html.comments(pr.comments)))
            append(Html.section("Commits", pr.commits.orEmpty().joinToString("") {
                "<div><code>${esc(it.oid.take(7))}</code> ${esc(it.messageHeadline)}</div>"
            }))
        }
        append(Html.section("Archivos", pr.files.orEmpty().joinToString("") {
            "<div><code>${esc(it.path)}</code> <font color=\"green\">+${it.additions}</font> <font color=\"red\">−${it.deletions}</font></div>"
        }))
    }

    /** The checklist of what the base branch demands before merging. */
    fun mergeStatus(status: GhMergeStatus): String = buildString {
        val color = if (status.failing.isNotEmpty()) "red" else if (status.pending.isNotEmpty()) "#b08800" else "green"
        append("<h3>Requisitos para fusionar: <font color=\"$color\">${esc(status.verdict)}</font></h3>")
        for (requirement in status.requirements) {
            append("<div><font color=\"${requirement.state.color}\"><b>${requirement.state.symbol}</b></font> ")
            append("<b>${esc(requirement.label)}</b>: ${esc(requirement.detail)}</div>")
        }
    }

    private fun stateLabel(pr: GhPullRequest) = if (pr.isDraft && pr.isOpen) "DRAFT" else pr.state
}
