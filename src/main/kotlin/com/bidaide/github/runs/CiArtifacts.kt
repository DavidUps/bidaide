package com.bidaide.github.runs

import com.bidaide.BidaideBundle
import com.bidaide.Downloads
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhException
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.array
import com.bidaide.gh.bool
import com.bidaide.gh.parseObject
import com.bidaide.gh.string
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.io.File

/** Build outputs uploaded by GitHub Actions runs: download them and, when they hold an APK, install it on a device. */
object CiArtifacts {
    fun listArgs(runId: Long) = listOf("api", "repos/{owner}/{repo}/actions/runs/$runId/artifacts?per_page=100")

    fun downloadArgs(runId: Long, name: String, dir: File) = listOf("run", "download", runId.toString(), "--name", name, "--dir", dir.path)

    /** Names of the artifacts that can still be downloaded (GitHub expires them after a while). */
    fun parseNames(json: String): List<String> =
        parseObject(json).array("artifacts").filterNot { it.bool("expired") }.mapNotNull { it.string("name") }

    private class Found(val runId: Long, val names: List<String>)

    /** Lets the user pick an artifact of the run and downloads it. */
    fun choose(project: Project, runId: Long) {
        GhCli.async(
            project, BidaideBundle.message("github.ci.searching"),
            work = { Found(runId, parseNames(GhCli.run(GhRepoContext.requireWorkDir(project), listArgs(runId)))) },
        ) { pick(project, it) }
    }

    /** Same, for the newest finished run of [branch] that has artifacts. */
    fun forBranch(project: Project, branch: String) {
        GhCli.async(
            project, BidaideBundle.message("github.ci.searchingBranch", branch),
            work = {
                val workDir = GhRepoContext.requireWorkDir(project)
                GhJson.runs(GhCli.run(workDir, GhCommands.runList(branch, ""))).asSequence()
                    .filter { it.isFinished }
                    .take(MAX_RUNS_CHECKED)
                    .map { Found(it.databaseId, parseNames(GhCli.run(workDir, listArgs(it.databaseId)))) }
                    .firstOrNull { it.names.isNotEmpty() }
                    ?: throw GhException(BidaideBundle.message("github.ci.noneForBranch", branch))
            },
        ) { pick(project, it) }
    }

    private fun pick(project: Project, found: Found) {
        when (found.names.size) {
            0 -> Notifier.info(project, "GitHub Actions", BidaideBundle.message("github.ci.none"))
            1 -> download(project, found.runId, found.names.single())
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(found.names)
                .setTitle(BidaideBundle.message("github.ci.choose"))
                .setItemChosenCallback { download(project, found.runId, it) }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    private fun download(project: Project, runId: Long, name: String) {
        GhCli.async(
            project, BidaideBundle.message("github.ci.downloading", name),
            work = {
                val dir = File(Downloads.captureDir(),"bidaide-artifacts/$runId/${name.replace(Regex("[^A-Za-z0-9._-]+"), "_")}")
                // gh refuses to overwrite files from a previous download.
                dir.deleteRecursively()
                dir.mkdirs()
                GhCli.run(GhRepoContext.requireWorkDir(project), downloadArgs(runId, name, dir), GhCli.LONG_TIMEOUT_MILLIS)
                dir
            },
        ) { dir -> Downloads.offer(project, dir, BidaideBundle.message("github.ci.artifact", name)) }
    }

    private const val MAX_RUNS_CHECKED = 5
}
