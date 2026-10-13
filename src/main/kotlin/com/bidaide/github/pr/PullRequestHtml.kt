package com.bidaide.github.pr

import com.bidaide.gh.GhMergeStatus
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.display
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Html.esc
import com.bidaide.github.ui.Markdown

object PullRequestHtml {
    /**
     * The body of the preview. Title, state, branches and people are drawn above it by [PullRequestHeader], so this
     * starts at what needs reading: can it be merged, what it says, what happened, what it touches.
     */
    fun render(pr: GhPullRequest): String = buildString {
        val meta = listOfNotNull(
            pr.assignees?.takeIf { it.isNotEmpty() }?.let { "Asignada a <b>${esc(it.joinToString())}</b>" },
            pr.milestone?.title?.let { "Milestone <b>${esc(it)}</b>" },
            "Auto-merge activado".takeIf { pr.autoMergeRequest != null },
            Html.link(pr.url, "Ver en GitHub"),
        )
        append("<p><font color=\"gray\">${meta.joinToString(" &nbsp;·&nbsp; ")}</font></p>")
        pr.mergeStatus?.let { append(mergeStatus(it)) }

        append(Html.heading("Descripción"))
        append(Markdown.toHtml(pr.body).ifEmpty { "<p><font color=\"gray\"><i>Sin descripción.</i></font></p>" })

        append(Html.section("Checks", pr.statusCheckRollup.orEmpty().joinToString("") { check ->
            val (symbol, color) = when {
                check.passed -> "✓" to "#3fb950"
                check.failed -> "✗" to "#f85149"
                else -> "…" to "#b08800"
            }
            "<div><font color=\"$color\"><b>$symbol</b></font> ${Html.link(check.url, check.displayName)} " +
                "<font color=\"gray\"><small>${esc(check.result.lowercase())}</small></font></div>"
        }))
        val timeline = pr.timeline
        if (timeline != null) {
            append(Html.section("Actividad", Html.timeline(timeline)))
        } else {
            append(Html.section("Reviews", pr.reviews.orEmpty().joinToString("") { review ->
                "<p><b>${esc(review.author.display())}</b> ${esc(review.state?.lowercase())} " +
                    "<font color=\"gray\"><small>${Html.date(review.submittedAt)}</small></font></p>${Html.quote(review.body)}"
            }))
            append(Html.section("Comentarios", Html.comments(pr.comments)))
            append(Html.section("Commits", pr.commits.orEmpty().joinToString("") {
                "<div><code>${esc(it.oid.take(7))}</code> ${esc(it.messageHeadline)}</div>"
            }))
        }
        append(Html.section("Archivos · ${pr.changedFiles}", files(pr)))
    }

    /** File name first, its folder in grey after it, and the size of the change on the right. */
    private fun files(pr: GhPullRequest): String {
        val rows = pr.files.orEmpty().joinToString("") { file ->
            val name = file.path.substringAfterLast('/')
            val folder = file.path.substringBeforeLast('/', "")
            "<tr><td><code>${esc(name)}</code> <font color=\"gray\"><small>${esc(folder)}</small></font></td>" +
                "<td align=\"right\" width=\"90\"><code><font color=\"#3fb950\">+${file.additions}</font> " +
                "<font color=\"#f85149\">−${file.deletions}</font></code></td></tr>"
        }
        return if (rows.isEmpty()) "" else "<table width=\"100%\" cellpadding=\"2\" cellspacing=\"0\">$rows</table>"
    }

    /** The checklist of what the base branch demands before merging. */
    fun mergeStatus(status: GhMergeStatus): String = buildString {
        val color = if (status.failing.isNotEmpty()) "#f85149" else if (status.pending.isNotEmpty()) "#b08800" else "#3fb950"
        append(Html.heading("Requisitos para fusionar"))
        append("<p><font color=\"$color\"><b>${esc(status.verdict)}</b></font></p>")
        append("<table cellpadding=\"2\" cellspacing=\"0\">")
        for (requirement in status.requirements) {
            append("<tr><td width=\"18\"><font color=\"${requirement.state.color}\"><b>${requirement.state.symbol}</b></font></td>")
            append("<td><b>${esc(requirement.label)}</b></td><td>${esc(requirement.detail)}</td></tr>")
        }
        append("</table>")
    }

}
