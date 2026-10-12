package com.bidaide.github.pr

import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhException
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil

/** Pull request operations shared by the tool window and the status bar widget. */
object PullRequestActions {

    fun checkout(project: Project, pr: GhPullRequest, then: () -> Unit = {}) {
        GhCli.async(
            project, "Checkout de PR #${pr.number}",
            work = {
                GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.prCheckout(pr.number))
                GhRepoContext.repository(project)?.let {
                    it.update()
                    VfsUtil.markDirtyAndRefresh(false, true, true, it.root)
                }
            },
        ) {
            Notifier.info(project, "GitHub", "Rama ${pr.headRefName} (PR #${pr.number}) lista en local")
            then()
        }
    }

    /** Checks the PR out and launches the selected run configuration: build, install and start on the chosen device. */
    fun checkoutAndRun(project: Project, pr: GhPullRequest) {
        val settings = RunManager.getInstance(project).selectedConfiguration
        if (settings == null) {
            GhCli.report(project, "Probar PR #${pr.number}", GhException("No hay ninguna run configuration seleccionada."))
            return
        }
        checkout(project, pr) {
            Notifier.info(project, "PR #${pr.number}", "Lanzando «${settings.name}» con el código de la PR")
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }
}
