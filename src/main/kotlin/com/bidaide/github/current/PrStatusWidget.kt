package com.bidaide.github.current

import com.bidaide.gh.GhCli
import com.bidaide.gh.GhMergeRules
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.pr.PullRequestHtml
import com.bidaide.github.pr.PullRequestActions
import com.bidaide.github.review.PrReviewDiff
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import javax.swing.Icon

class PrStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = PrStatusWidget.ID
    override fun getDisplayName() = "Bidaide: pull request de la rama"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = PrStatusWidget(project)
}

/** Shows the pull request of the current branch with its checks and review state; click for actions. */
class PrStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    private val service get() = project.service<CurrentBranchPrService>()

    override fun ID() = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        project.messageBus.connect(this).subscribe(
            CurrentBranchPrService.TOPIC, CurrentBranchPrService.Listener { statusBar.updateWidget(ID) },
        )
    }

    override fun dispose() {}

    override fun getIcon(): Icon? = service.pullRequest?.let { AllIcons.Vcs.Vendors.Github }

    /** An empty value hides the widget, which is what we want on branches without a pull request. */
    override fun getSelectedValue(): String? = service.pullRequest?.let { text(it, openThreads()) }

    override fun getTooltipText(): String? = service.pullRequest?.let { "PR #${it.number}: ${it.title}" }

    private fun openThreads(): Int = service.session?.threads?.count { !it.isResolved } ?: 0

    override fun getPopup(): JBPopup? {
        val pr = service.pullRequest ?: return null
        val group = DefaultActionGroup(
            action("¿Cumple los requisitos de merge?") { showMergeStatus(pr) },
            action("Revisar cambios") { PrReviewDiff.open(project, pr) },
            action("Probar en dispositivo") { PullRequestActions.testOnDevice(project, pr) },
            action("Ver en Bidaide") { ToolWindowManager.getInstance(project).getToolWindow("Bidaide")?.activate(null) },
            action("Abrir en navegador") { BrowserUtil.browse(pr.url) },
            action("Actualizar estado") { service.refresh() },
        )
        val context = DataManager.getInstance().getDataContext(WindowManager.getInstance().getStatusBar(project)?.component)
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "PR #${pr.number}: ${pr.title.take(60)}", group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        )
    }

    private fun showMergeStatus(pr: GhPullRequest) {
        GhCli.async(
            project, "Comprobando requisitos de PR #${pr.number}",
            work = { GhMergeRules.load(GhRepoContext.requireWorkDir(project), pr.number) },
        ) { status ->
            val html = "<html>${PullRequestHtml.mergeStatus(status)}</html>"
            when {
                status.failing.isNotEmpty() -> Messages.showErrorDialog(project, html, "PR #${pr.number} → ${pr.baseRefName}")
                status.pending.isNotEmpty() -> Messages.showWarningDialog(project, html, "PR #${pr.number} → ${pr.baseRefName}")
                else -> Messages.showInfoMessage(project, html, "PR #${pr.number} → ${pr.baseRefName}")
            }
        }
    }

    private fun action(text: String, run: () -> Unit) = object : DumbAwareAction(text) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    companion object {
        const val ID = "Bidaide.PrStatus"

        fun text(pr: GhPullRequest, openThreads: Int): String = buildString {
            append("#${pr.number}")
            if (!pr.isOpen) {
                append(" ").append(pr.state.lowercase())
                return@buildString
            }
            if (pr.isDraft) append(" borrador")
            when {
                pr.checksFailed -> append(" ✗ checks")
                pr.checksPassed -> append(" ✓ checks")
                pr.checks.isNotEmpty() -> append(" … checks")
            }
            when (pr.reviewDecision) {
                "APPROVED" -> append(" · aprobada")
                "CHANGES_REQUESTED" -> append(" · cambios pedidos")
                "REVIEW_REQUIRED" -> append(" · falta review")
            }
            if (openThreads > 0) append(" · 💬 $openThreads")
        }
    }
}
