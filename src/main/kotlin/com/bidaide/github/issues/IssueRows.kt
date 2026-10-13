package com.bidaide.github.issues

import com.bidaide.gh.GhIssue
import com.bidaide.gh.GitHubUser
import com.bidaide.gh.display
import com.bidaide.github.ui.AvatarImages
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Pill
import com.bidaide.github.ui.RichRow
import com.bidaide.github.ui.TimeAgo
import com.bidaide.github.ui.Tones
import com.bidaide.github.ui.UserChip
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/** How an issue reads at a glance: in the list, and in the header of its detail. */
object IssueRows {
    class State(val label: String, val tone: Color)

    /** GitHub paints "completed" purple and "not planned" grey; both are closed. */
    fun state(issue: GhIssue): State = when {
        issue.isOpen -> State("Abierta", Tones.GREEN)
        issue.stateReason.equals("NOT_PLANNED", ignoreCase = true) -> State("Descartada", Tones.GRAY)
        else -> State("Cerrada", Tones.PURPLE)
    }

    fun row(issue: GhIssue, now: Instant = Instant.now()): RichRow {
        val linked = issue.closedByPullRequestsReferences.orEmpty().size
        return RichRow(
            title = issue.title,
            icon = if (issue.isOpen) AllIcons.General.TodoDefault else AllIcons.Actions.Checked,
            subtitle = buildString {
                append("#${issue.number} · ${issue.author.display()} la abrió ${TimeAgo.format(issue.createdAt, now)}")
                issue.milestone?.title?.let { append(" · $it") }
            },
            labels = issue.labels.orEmpty().map { RichRow.Tag(it.name, Tones.ofLabel(it.color)) },
            // Pull requests that will close it: the sign that somebody is already on it.
            badges = if (linked > 0) listOf(RichRow.Tag(if (linked == 1) "1 PR" else "$linked PR", Tones.BLUE)) else emptyList(),
            people = issue.assignees.orEmpty().mapNotNull { it.login }.map { GitHubUser(it) },
            time = TimeAgo.format(issue.updatedAt, now),
        )
    }

    fun summary(issue: GhIssue, now: Instant = Instant.now()): String {
        val comments = issue.comments.orEmpty().size
        return "<html><b>${Html.esc(issue.author.display())}</b> abrió esta issue ${TimeAgo.format(issue.createdAt, now)}" +
            (if (comments > 0) " · $comments ${if (comments == 1) "comentario" else "comentarios"}" else "") +
            (issue.milestone?.title?.let { " · milestone <b>${Html.esc(it)}</b>" }.orEmpty()) + "</html>"
    }
}

/** Title, state, labels and people of an issue, above its description. */
class IssueHeader(issue: GhIssue) : JPanel() {
    val chips = ArrayList<UserChip>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 4, 10)

        val title = JLabel("<html>${Html.esc(issue.title)} <font color=\"gray\">#${issue.number}</font></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D + 5f)
            border = JBUI.Borders.empty(0, 2, 6, 0)
        }
        val state = IssueRows.state(issue)
        val pills = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(Pill(state.label, state.tone, solid = true))
            issue.labels.orEmpty().forEach { add(Pill(it.name, Tones.ofLabel(it.color))) }
        }
        val summary = JLabel(IssueRows.summary(issue)).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(6, 8, 2, 0)
        }
        val people = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false
            issue.author?.login?.let {
                add(caption("Autor"))
                add(chip(it))
            }
            val assignees = issue.assignees.orEmpty().mapNotNull { it.login }
            if (assignees.isNotEmpty()) {
                add(Box.createHorizontalStrut(12))
                add(caption("Asignada a"))
                assignees.forEach { add(chip(it)) }
            }
        }
        for (part in listOf(title, pills, summary, people)) {
            part.alignmentX = Component.LEFT_ALIGNMENT
            add(part)
        }
    }

    private fun caption(text: String) = JLabel("<html><font color=\"gray\">$text</font></html>")

    /** Only the login is known here; GitHub serves any account's picture by name, so no API call is needed. */
    private fun chip(login: String) = UserChip(GitHubUser(login, AvatarImages.profilePicture(login))).also(chips::add)
}
