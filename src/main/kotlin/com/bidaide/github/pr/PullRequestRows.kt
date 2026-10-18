package com.bidaide.github.pr

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhPrPeople
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GitHubUser
import com.bidaide.gh.display
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Pill
import com.bidaide.github.ui.RichRow
import com.bidaide.github.ui.TimeAgo
import com.bidaide.github.ui.Tones
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.time.Instant
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** How a pull request reads at a glance: in the list, and in the header of its preview. */
object PullRequestRows {
    class State(val label: String, val tone: Color)

    fun state(pr: GhPullRequest): State = when {
        pr.state.equals("MERGED", true) -> State(BidaideBundle.message("github.pr.state.merged"), Tones.PURPLE)
        !pr.isOpen -> State(BidaideBundle.message("github.pr.state.closed"), Tones.RED)
        pr.isDraft -> State(BidaideBundle.message("github.pr.state.draft"), Tones.GRAY)
        else -> State(BidaideBundle.message("github.pr.state.open"), Tones.GREEN)
    }

    private fun icon(pr: GhPullRequest): Icon = when {
        pr.state.equals("MERGED", true) -> AllIcons.Vcs.Merge
        !pr.isOpen -> AllIcons.Actions.Cancel
        pr.isDraft -> AllIcons.Actions.Edit
        else -> AllIcons.Vcs.Branch
    }

    /** Review and CI state, the two things that decide whether a pull request can move. */
    fun badges(pr: GhPullRequest): List<RichRow.Tag> = buildList {
        when (pr.reviewDecision) {
            "APPROVED" -> add(RichRow.Tag(BidaideBundle.message("github.pr.badge.approved"), Tones.GREEN))
            "CHANGES_REQUESTED" -> add(RichRow.Tag(BidaideBundle.message("github.pr.badge.changesRequested"), Tones.RED))
            "REVIEW_REQUIRED" -> add(RichRow.Tag(BidaideBundle.message("github.pr.badge.reviewRequired"), Tones.AMBER))
        }
        when {
            pr.checksFailed -> add(RichRow.Tag("✗ checks", Tones.RED))
            pr.checksPassed -> add(RichRow.Tag("✓ checks", Tones.GREEN))
            pr.checks.isNotEmpty() -> add(RichRow.Tag("… checks", Tones.AMBER))
        }
    }

    fun row(pr: GhPullRequest, now: Instant = Instant.now()): RichRow = RichRow(
        title = pr.title,
        icon = icon(pr),
        subtitle = buildString {
            append("#${pr.number} · ")
            append(if (pr.isDraft && pr.isOpen) BidaideBundle.message("github.pr.row.draft") + ", " else "")
            append(BidaideBundle.message("github.pr.row.opened", TimeAgo.format(pr.createdAt, now), pr.author.display()))
            append(" · ${pr.headRefName} → ${pr.baseRefName}")
        },
        labels = pr.labels.orEmpty().map { RichRow.Tag(it.name, Tones.ofLabel(it.color)) },
        badges = badges(pr),
        // Who it is assigned to; an unassigned pull request shows its author instead, so there is always a face.
        people = pr.assignees.orEmpty().mapNotNull { it.login }.ifEmpty { listOfNotNull(pr.author?.login) }.map { GitHubUser(it) },
        time = TimeAgo.format(pr.updatedAt, now),
    )

    /** The sentence GitHub puts under the title: who wants to merge what into where. */
    fun summary(pr: GhPullRequest, now: Instant = Instant.now()): String {
        val key = when {
            pr.state.equals("MERGED", true) -> "github.pr.summary.merged"
            !pr.isOpen -> "github.pr.summary.closed"
            else -> "github.pr.summary.open"
        }
        val lines = "<font color=\"#3fb950\">+${pr.additions}</font> <font color=\"#f85149\">−${pr.deletions}</font>"
        return "<html>" + BidaideBundle.message(
            key, "<b>${Html.esc(pr.author.display())}</b>", pr.changedFiles, lines, "<code>${Html.esc(pr.baseRefName)}</code>",
            "<code>${Html.esc(pr.headRefName)}</code>", TimeAgo.format(pr.createdAt, now),
        ) + "</html>"
    }
}

/**
 * Title, state, people and checks of a pull request, above its description: the part that should be readable at once.
 * The checks are components rather than HTML so that the running ones can show a live spinner.
 */
class PullRequestHeader(pr: GhPullRequest, people: JComponent?) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 4, 10)

        val title = JLabel("<html>${Html.esc(pr.title)} <font color=\"gray\">#${pr.number}</font></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD, UIUtil.getLabelFont().size2D + 5f)
        }
        val state = PullRequestRows.state(pr)
        val line = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(Pill(state.label, state.tone, solid = true))
            PullRequestRows.badges(pr).forEach { add(Pill(it.text, it.tone)) }
            pr.labels.orEmpty().forEach { add(Pill(it.name, Tones.ofLabel(it.color))) }
        }
        val summary = JLabel(PullRequestRows.summary(pr)).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(6, 8, 2, 0)
        }
        val checks = pr.checks.takeIf { it.isNotEmpty() }?.let(::PullRequestChecks)
        for (part in listOfNotNull(title, line, summary, people, checks)) {
            part.alignmentX = Component.LEFT_ALIGNMENT
            add(part)
        }
        title.border = JBUI.Borders.empty(0, 2, 6, 0)
    }

    companion object {
        fun peopleOf(people: GhPrPeople, build: () -> JComponent): JComponent? = if (people.isEmpty) null else build()
    }
}
