package com.bidaide.github.runs

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GhRun
import com.bidaide.gh.RunResults
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Html.esc
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import javax.swing.JButton

/** GitHub Actions runs: live status, re-run, cancel and logs with links into the project. */
class RunsPanel(project: Project) : GhListDetailPanel<GhRun>(
    project, "run",
    filters = listOf(
        FilterOption(BidaideBundle.message("github.runs.currentBranch"), CURRENT_BRANCH), FilterOption(BidaideBundle.message("github.runs.allBranches"), ""),
    ),
    createText = null,
) {
    override fun listArgs(filter: String, scope: String, search: String): List<String> {
        val branch = if (filter == CURRENT_BRANCH) GhRepoContext.repository(project)?.currentBranchName else null
        // The search box narrows by workflow name here; runs have no free-text search.
        return GhCommands.runList(branch, search)
    }

    override fun parseList(json: String) = GhJson.runs(json)
    override fun detailArgs(item: GhRun) = GhCommands.runView(item.databaseId)
    override fun parseDetail(json: String) = GhJson.run(json)
    override fun id(item: GhRun) = item.databaseId
    override fun title(item: GhRun) = item.workflowName
    override val searchHint get() = BidaideBundle.message("github.runs.searchHint")

    /** Keep polling while something is still running. */
    override fun autoRefreshMillis(items: List<GhRun>): Int? = REFRESH_MILLIS.takeIf { items.any { run -> !run.isFinished } }

    override fun extraActions(): List<AnAction> = listOf(
        object : DumbAwareAction(
            BidaideBundle.message("github.runs.dispatch"), BidaideBundle.message("github.runs.dispatch.description"),
            AllIcons.Actions.Execute,
        ) {
            override fun actionPerformed(e: AnActionEvent) = WorkflowDispatch.start(project) { reload() }
        },
    )

    override fun renderCell(renderer: ColoredListCellRenderer<GhRun>, item: GhRun) {
        renderer.icon = when {
            !item.isFinished -> AnimatedIcon.Default.INSTANCE
            RunResults.passed(item.conclusion) -> AllIcons.RunConfigurations.TestPassed
            item.failed -> AllIcons.RunConfigurations.TestFailed
            else -> AllIcons.RunConfigurations.TestIgnored
        }
        renderer.append(item.displayTitle)
        renderer.append(
            "  ${item.workflowName} · ${item.headBranch.orEmpty()} · ${Html.date(item.createdAt)}",
            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
        )
    }

    override fun detailHtml(item: GhRun): String = buildString {
        append("<h2>${esc(item.displayTitle)}</h2>")
        append("<p>${colored(item.result, item.conclusion)} · ${esc(item.workflowName)} · <code>${esc(item.headBranch)}</code> ")
        append("<code>${esc(item.headSha?.take(7))}</code> · ${esc(item.event)} · ${Html.date(item.createdAt)} · ${Html.link(item.url, "GitHub")}</p>")
        for (job in item.jobs.orEmpty()) {
            append("<h3>${colored(RunResults.label(job.status, job.conclusion), job.conclusion)} ${Html.link(job.url, job.name)}</h3>")
            for (step in job.steps.orEmpty()) {
                append("<div>&nbsp;&nbsp;${colored(RunResults.label(step.status, step.conclusion), step.conclusion)} ${esc(step.name)}</div>")
            }
        }
    }

    private fun colored(label: String, conclusion: String?): String {
        val color = when {
            RunResults.passed(conclusion) -> "green"
            RunResults.failed(conclusion) -> "red"
            else -> "gray"
        }
        return "<font color=\"$color\">${esc(label)}</font>"
    }

    override fun detailButtons(item: GhRun): List<JButton> = buildList {
        val id = item.databaseId
        if (item.isFinished) {
            if (item.failed) {
                add(button(BidaideBundle.message("github.runs.rerunFailed")) { runAndReload(BidaideBundle.message("github.runs.rerunningFailed"), GhCommands.runRerun(id, true), BidaideBundle.message("github.runs.rerunFailed.done")) })
                add(button(BidaideBundle.message("github.runs.failedLogs")) { showLog(item, failedOnly = true) })
            }
            add(button(BidaideBundle.message("github.runs.rerunAll")) { runAndReload(BidaideBundle.message("github.runs.rerunning"), GhCommands.runRerun(id, false), BidaideBundle.message("github.runs.rerun.done")) })
            add(button(BidaideBundle.message("github.runs.fullLog")) { showLog(item, failedOnly = false) })
        } else {
            add(button(BidaideBundle.message("common.cancel")) { runAndReload(BidaideBundle.message("github.runs.cancelling"), GhCommands.runCancel(id), BidaideBundle.message("github.runs.cancelled")) })
        }
        if (item.isFinished) add(button("Artifacts...") { CiArtifacts.choose(project, item.databaseId) })
        add(button(BidaideBundle.message("github.pr.button.browse")) { BrowserUtil.browse(item.url) })
    }

    /** Opens the log in its own closable tab, with file paths turned into links to the local sources. */
    private fun showLog(run: GhRun, failedOnly: Boolean) {
        GhCli.async(
            project, BidaideBundle.message("github.runs.downloadingLog", run.workflowName),
            work = { GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.runLog(run.databaseId, failedOnly)) },
        ) { log ->
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Bidaide") ?: return@async
            val root = GhRepoContext.repository(project)?.root?.let(VfsUtilCore::virtualToIoFile)
            val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
            if (root != null) builder.addFilter(CiPathFilter(project, root))
            val console = builder.console
            console.print(log.ifBlank { BidaideBundle.message("github.runs.emptyLog") + "\n" }, ConsoleViewContentType.NORMAL_OUTPUT)
            val name = "${if (failedOnly) BidaideBundle.message("github.runs.failures") else "Log"}: ${run.workflowName}"
            val content = ContentFactory.getInstance().createContent(console.component, name, false)
            content.isCloseable = true
            Disposer.register(content, console)
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
            console.scrollTo(0)
        }
    }

    private companion object {
        const val CURRENT_BRANCH = "current"
        const val REFRESH_MILLIS = 15_000
    }
}
