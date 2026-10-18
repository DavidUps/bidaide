package com.bidaide.github.ui

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhLabel
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GhUser
import com.intellij.openapi.project.Project

/** Loads the repository's labels and milestones, shows [EditMetadataDialog] and returns the `gh ... edit` command. */
object MetadataEditor {
    /** [kind] is "issue" or "pr". [onCommand] receives the edit command, or is not called if nothing changed. */
    fun edit(
        project: Project, kind: String, number: Int,
        labels: List<GhLabel>?, assignees: List<GhUser>?, milestone: String?,
        onCommand: (List<String>) -> Unit,
    ) {
        GhCli.async(
            project, BidaideBundle.message("github.metadata.loading"),
            work = {
                val workDir = GhRepoContext.requireWorkDir(project)
                lines(GhCli.run(workDir, GhCommands.labelList())) to lines(GhCli.run(workDir, GhCommands.milestoneList()))
            },
        ) { (allLabels, milestones) ->
            val dialog = EditMetadataDialog(
                project, if (kind == "pr") BidaideBundle.message("github.metadata.editPr", number) else BidaideBundle.message("github.metadata.editIssue", number), allLabels,
                labels.orEmpty().map { it.name }.toSet(), assignees.orEmpty().mapNotNull { it.login }.toSet(),
                milestones, milestone, withReviewers = kind == "pr",
            )
            if (!dialog.showAndGet()) return@async
            val command = GhCommands.editMetadata(
                kind, number, dialog.addLabels, dialog.removeLabels, dialog.addAssignees, dialog.removeAssignees,
                dialog.milestone, if (kind == "pr") dialog.addReviewers else emptyList(),
            )
            // Just "<kind> edit <number>" means nothing was changed.
            if (command.size > 3) onCommand(command)
        }
    }

    private fun lines(output: String): List<String> = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
}
