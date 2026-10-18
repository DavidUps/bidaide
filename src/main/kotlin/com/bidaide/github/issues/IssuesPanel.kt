package com.bidaide.github.issues

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhCommands.StateFilter
import com.bidaide.gh.GhIssue
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GhTimeline
import com.bidaide.gh.display
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.KanbanBoard
import com.bidaide.github.ui.Html.esc
import com.bidaide.github.ui.Markdown
import com.bidaide.github.ui.RichRow
import com.bidaide.github.ui.MetadataEditor
import com.bidaide.github.ui.TextPromptDialog
import com.bidaide.github.ui.TitleBodyDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent

class IssuesPanel(project: Project) : GhListDetailPanel<GhIssue>(
    project, "issue",
    createText = BidaideBundle.message("github.issue.new"),
    filters = listOf(StateFilter.OPEN, StateFilter.CLOSED, StateFilter.ALL).map { FilterOption(it.label, it.name) },
    scopes = GhCommands.ISSUE_SCOPES.map { FilterOption(it.first, it.second) },
) {
    override fun listArgs(filter: String, scope: String, search: String) =
        GhCommands.issueList(StateFilter.valueOf(filter), GhCommands.combineSearch(scope, search))

    override fun parseList(json: String) = GhJson.issues(json)
    override fun detailArgs(item: GhIssue) = GhCommands.issueView(item.number)
    override fun parseDetail(json: String) = GhJson.issue(json)
    override fun id(item: GhIssue) = item.number.toLong()

    // --- Board view: the same issues as columns of cards, grouped by label, assignee, milestone or state ---

    private val properties get() = PropertiesComponent.getInstance(project)
    private var boardMode = false
    private var groupBy = IssueBoard.GroupBy.LABEL
    private var issues: List<GhIssue> = emptyList()

    /** Open milestones of the repository, so the milestone board has a column for each even when empty. */
    @Volatile private var milestones: List<String> = emptyList()

    private val board = KanbanBoard(
        onSelect = { card -> select(card.id.toLong()) },
        onOpen = { card -> (card.payload as? GhIssue)?.let { BrowserUtil.browse(it.url) } },
        onMove = { card, from, to ->
            val number = card.id.toInt()
            runAndReload(BidaideBundle.message("github.issue.moving", number), IssueBoard.moveCommand(number, groupBy, from, to), IssueBoard.moveDescription(number, groupBy, to))
        },
    )

    init {
        groupBy = IssueBoard.GroupBy.entries.firstOrNull { it.name == properties.getValue(GROUP_KEY) } ?: IssueBoard.GroupBy.LABEL
        boardMode = properties.getBoolean(BOARD_KEY, false)
        if (boardMode) showBoard(board)
    }

    override fun extraActions(): List<AnAction> = listOf(
        object : ToggleAction(
            BidaideBundle.message("github.issue.board"), BidaideBundle.message("github.issue.board.description"),
            AllIcons.Actions.SplitVertically,
        ), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = boardMode
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                boardMode = state
                properties.setValue(BOARD_KEY, state, false)
                showBoard(if (state) board else null)
                if (state) refreshBoard()
            }
        },
        object : DumbAwareAction(BidaideBundle.message("github.issue.groupBy"), null, AllIcons.Actions.GroupBy) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = boardMode
            }

            override fun actionPerformed(e: AnActionEvent) {
                JBPopupFactory.getInstance().createPopupChooserBuilder(IssueBoard.GroupBy.entries)
                    .setTitle(BidaideBundle.message("github.issue.groupBy.title"))
                    .setItemChosenCallback {
                        groupBy = it
                        properties.setValue(GROUP_KEY, it.name)
                        // Milestone columns need the repository's milestones, which only a reload fetches.
                        if (it == IssueBoard.GroupBy.MILESTONE) reload() else refreshBoard()
                    }
                    .createPopup()
                    .showInCenterOf(this@IssuesPanel)
            }
        },
    )

    override fun fetchList(workDir: File, filter: String, scope: String, search: String): List<GhIssue> {
        if (boardMode && groupBy == IssueBoard.GroupBy.MILESTONE) {
            milestones = runCatching { GhCli.run(workDir, GhCommands.milestoneList()).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() }
                .getOrDefault(emptyList())
        }
        return super.fetchList(workDir, filter, scope, search)
    }

    override fun onItemsLoaded(items: List<GhIssue>) {
        issues = items
        if (boardMode) refreshBoard()
    }

    private fun refreshBoard() {
        val known = if (groupBy == IssueBoard.GroupBy.MILESTONE) milestones else emptyList()
        board.show(IssueBoard.columns(issues, groupBy, known), BidaideBundle.message("github.issue.board.empty"))
    }

    override fun fetchDetail(workDir: File, item: GhIssue): GhIssue {
        val issue = super.fetchDetail(workDir, item)
        // The feed is a nicety: without it the plain comment list is shown.
        return issue.copy(timeline = runCatching { GhTimeline.parse(GhCli.run(workDir, GhTimeline.args(issue.number))) }.getOrNull())
    }

    override fun renderCell(renderer: ColoredListCellRenderer<GhIssue>, item: GhIssue) {
        renderer.icon = if (item.isOpen) AllIcons.General.TodoDefault else AllIcons.Actions.Checked
        renderer.append("#${item.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        renderer.append(item.title)
        val labels = item.labels.orEmpty().joinToString(", ") { it.name }
        renderer.append("  ${item.author.display()}${if (labels.isEmpty()) "" else " · $labels"}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    override fun richRow(): (GhIssue) -> RichRow = { IssueRows.row(it) }

    /** "Comment" stands out, two more stay at hand and the rest go under "More". */
    override val visibleButtons get() = 3

    override fun detailHeader(item: GhIssue): JComponent = IssueHeader(item)

    /** Title, state, labels and people are drawn above by [IssueHeader]; this is what there is to read. */
    override fun detailHtml(item: GhIssue): String = buildString {
        val linked = item.closedByPullRequestsReferences.orEmpty()
        val meta = listOfNotNull(
            linked.takeIf { it.isNotEmpty() }?.let { prs ->
                BidaideBundle.message("github.issue.closedBy", prs.joinToString(", ") { Html.link(it.url, "PR #${it.number}") })
            },
            Html.link(item.url, BidaideBundle.message("github.html.viewOnGitHub")),
        )
        append("<p><font color=\"gray\">${meta.joinToString(" &nbsp;·&nbsp; ")}</font></p>")
        append(Html.heading(BidaideBundle.message("github.html.description")))
        append(Markdown.toHtml(item.body).ifEmpty { "<p><font color=\"gray\"><i>${Html.esc(BidaideBundle.message("github.html.noDescription"))}</i></font></p>" })
        val timeline = item.timeline
        append(if (timeline != null) Html.section(BidaideBundle.message("github.html.activity"), Html.timeline(timeline)) else Html.section(BidaideBundle.message("github.html.comments"), Html.comments(item.comments)))
    }

    override fun detailButtons(item: GhIssue): List<JButton> = buildList {
        val n = item.number
        add(button(BidaideBundle.message("github.pr.button.comment")) {
            val dialog = TextPromptDialog(project, BidaideBundle.message("github.issue.comment.title", n), BidaideBundle.message("github.pr.button.comment"), required = true)
            if (dialog.showAndGet()) runAndReload(
                    BidaideBundle.message("github.issue.comment.task", n), GhCommands.issueComment(n, dialog.text),
                    BidaideBundle.message("github.issue.comment.done", n),
                )
        })
        if (item.isOpen) add(button(BidaideBundle.message("github.issue.button.develop")) { develop(item) })
        add(button(BidaideBundle.message("github.pr.button.edit")) {
            MetadataEditor.edit(project, "issue", n, item.labels, item.assignees, item.milestone?.title) { command ->
                runAndReload(BidaideBundle.message("github.issue.edit.task", n), command, BidaideBundle.message("github.issue.edit.done", n))
            }
        })
        if (item.isOpen) {
            add(button(BidaideBundle.message("github.pr.button.close")) { runAndReload(BidaideBundle.message("github.issue.close.task", n), GhCommands.issueSetOpen(n, false), BidaideBundle.message("github.issue.close.done", n)) })
        } else {
            add(button(BidaideBundle.message("github.pr.button.reopen")) { runAndReload(BidaideBundle.message("github.issue.reopen.task", n), GhCommands.issueSetOpen(n, true), BidaideBundle.message("github.issue.reopen.done", n)) })
        }
        add(button(BidaideBundle.message("github.pr.button.browse")) { BrowserUtil.browse(item.url) })
    }

    /** Creates a branch linked to the issue on GitHub and checks it out locally. */
    private fun develop(issue: GhIssue) {
        GhCli.async(
            project, BidaideBundle.message("github.issue.develop.task", issue.number),
            work = {
                GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.issueDevelop(issue.number))
                GhRepoContext.repository(project)?.let {
                    it.update()
                    VfsUtil.markDirtyAndRefresh(false, true, true, it.root)
                    it.currentBranchName
                }
            },
        ) { branch ->
            Notifier.info(project, "GitHub", BidaideBundle.message("github.issue.develop.done", branch ?: BidaideBundle.message("github.issue.develop.newBranch"), issue.number))
        }
    }

    override fun create() = create(project, "", "") { reload() }

    companion object {
        private const val BOARD_KEY = "Bidaide.Issues.board"
        private const val GROUP_KEY = "Bidaide.Issues.groupBy"

        /** Shows the "new issue" form, optionally prefilled, and creates the issue. */
        fun create(project: Project, title: String, body: String, onCreated: () -> Unit = {}) {
            val dialog = TitleBodyDialog(project, BidaideBundle.message("github.issue.create.title"), BidaideBundle.message("github.pr.create.ok"), title, body).apply { build() }
            if (!dialog.showAndGet()) return
            GhCli.async(
                project, BidaideBundle.message("github.issue.create.creating"),
                work = { GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.issueCreate(dialog.itemTitle, dialog.body)).trim() },
            ) { output ->
                val url = output.lines().lastOrNull().orEmpty()
                Notifier.info(project, BidaideBundle.message("github.issue.create.done"), Html.link(url, url))
                onCreated()
            }
        }
    }
}
