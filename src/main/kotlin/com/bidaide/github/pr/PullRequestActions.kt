package com.bidaide.github.pr

import com.bidaide.AndroidBridge
import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhException
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.SimpleListCellRenderer

/** Pull request operations shared by the tool window and the status bar widget. */
object PullRequestActions {

    fun checkout(project: Project, pr: GhPullRequest, then: () -> Unit = {}) {
        GhCli.async(
            project, BidaideBundle.message("github.pr.checkout.task", pr.number),
            work = {
                GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.prCheckout(pr.number))
                GhRepoContext.repository(project)?.let {
                    it.update()
                    VfsUtil.markDirtyAndRefresh(false, true, true, it.root)
                }
            },
        ) {
            Notifier.info(project, "GitHub", BidaideBundle.message("github.pr.checkout.done", pr.headRefName, pr.number))
            then()
        }
    }

    /**
     * "Probar en dispositivo": asks whether to build in a separate worktree (the default, leaves the user's copy alone)
     * or to check the PR out in the user's copy. The last choice comes preselected, but the question is always asked.
     * Android Studio only: callers hide it when [AndroidBridge.isAvailable] is false.
     */
    fun testOnDevice(project: Project, pr: GhPullRequest) {
        val android = AndroidBridge.instance ?: return
        val properties = PropertiesComponent.getInstance(project)
        val hasLocalChanges = ChangeListManager.getInstance(project).allChanges.isNotEmpty()
        JBPopupFactory.getInstance().createPopupChooserBuilder(PrTestMode.entries)
            .setTitle(BidaideBundle.message("github.pr.test.title", pr.number))
            .setRenderer(SimpleListCellRenderer.create("") { it.label(hasLocalChanges) })
            .setSelectedValue(PrTestMode.parse(properties.getValue(TEST_MODE_KEY)), true)
            .setItemChosenCallback { mode ->
                properties.setValue(TEST_MODE_KEY, mode.name)
                when (mode) {
                    PrTestMode.WORKTREE -> android.runPullRequestInWorktree(project, pr)
                    PrTestMode.CHECKOUT -> if (!hasLocalChanges || confirmCheckoutWithChanges(project)) checkoutAndRun(project, pr)
                }
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private const val TEST_MODE_KEY = "bidaide.pr.testMode"

    private fun confirmCheckoutWithChanges(project: Project): Boolean =
        Messages.showOkCancelDialog(
            project,
            BidaideBundle.message("github.pr.test.uncommitted"),
            BidaideBundle.message("github.pr.test.checkoutMode"), BidaideBundle.message("github.pr.test.doCheckout"),
            BidaideBundle.message("common.cancel"), Messages.getWarningIcon(),
        ) == Messages.OK

    /** Checks the PR out and launches the selected run configuration: build, install and start on the chosen device. */
    fun checkoutAndRun(project: Project, pr: GhPullRequest) {
        val settings = RunManager.getInstance(project).selectedConfiguration
        if (settings == null) {
            GhCli.report(project, BidaideBundle.message("github.pr.test.task", pr.number), GhException(BidaideBundle.message("github.pr.test.noConfiguration")))
            return
        }
        checkout(project, pr) {
            Notifier.info(project, "PR #${pr.number}", BidaideBundle.message("github.pr.test.launching", settings.name))
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }
}
