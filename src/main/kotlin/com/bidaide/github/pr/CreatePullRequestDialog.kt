package com.bidaide.github.pr

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhException
import com.bidaide.github.ui.TitleBodyDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.io.File

class CreatePullRequestDialog(project: Project, branch: String, defaultBranch: String, draft: PullRequestDraft) {
    private val baseField = JBTextField(defaultBranch, 20)
    private val reviewersField = JBTextField()
    private val assigneesField = JBTextField()
    private val labelsField = JBTextField()
    private val draftBox = JBCheckBox(BidaideBundle.message("github.pr.create.draft"))
    private val pushBox = JBCheckBox(BidaideBundle.message("github.pr.create.push", branch), true)

    private val dialog = TitleBodyDialog(project, BidaideBundle.message("github.pr.create.title", branch), BidaideBundle.message("github.pr.create.ok"), draft.title, draft.body) {
        row(BidaideBundle.message("github.pr.create.base")) { cell(baseField) }
        row(BidaideBundle.message("github.pr.create.reviewers")) { cell(reviewersField).align(AlignX.FILL).comment(BidaideBundle.message("github.pr.create.reviewers.comment")) }
        row(BidaideBundle.message("github.metadata.assignees")) { cell(assigneesField).align(AlignX.FILL).comment(BidaideBundle.message("github.pr.create.assignees.comment")) }
        row(BidaideBundle.message("github.metadata.labels")) { cell(labelsField).align(AlignX.FILL) }
        row {
            cell(draftBox)
            cell(pushBox)
        }
    }.apply { build() }

    val itemTitle: String get() = dialog.itemTitle
    val body: String get() = dialog.body
    val base: String get() = baseField.text.trim()
    val reviewers: String get() = reviewersField.text
    val assignees: String get() = assigneesField.text
    val labels: String get() = labelsField.text
    val draft: Boolean get() = draftBox.isSelected
    val pushFirst: Boolean get() = pushBox.isSelected

    fun showAndGet(): Boolean = dialog.showAndGet()
}

/** Suggested title and body for a new pull request. */
class PullRequestDraft(val title: String, val body: String)

object PullRequestGit {
    private val TEMPLATE_LOCATIONS = listOf(".github", "", "docs")
    private const val TEMPLATE_NAME = "pull_request_template.md"

    /**
     * Blocking. Title: the commit subject when the branch has a single commit, else the branch name made readable.
     * Body: the repository's PR template if it has one, else the list of commits.
     */
    fun draft(project: Project, repository: GitRepository, branch: String, base: String): PullRequestDraft {
        val subjects = commitSubjects(project, repository, base)
        val title = subjects.singleOrNull() ?: humanize(branch)
        val body = template(VfsUtilCore.virtualToIoFile(repository.root))
            ?: subjects.takeIf { it.size > 1 }?.reversed()?.joinToString("\n") { "- $it" }.orEmpty()
        return PullRequestDraft(title, body)
    }

    internal fun humanize(branch: String): String =
        branch.substringAfterLast('/').replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    internal fun template(root: File): String? =
        TEMPLATE_LOCATIONS.asSequence()
            .mapNotNull { File(root, it).listFiles { file -> file.isFile && file.name.equals(TEMPLATE_NAME, ignoreCase = true) }?.firstOrNull() }
            .firstOrNull()?.readText()

    /** Subjects of the commits in HEAD that are not in the base branch, newest first; empty if that cannot be told. */
    private fun commitSubjects(project: Project, repository: GitRepository, base: String): List<String> {
        val remote = remote(repository)?.name ?: return emptyList()
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG)
        handler.addParameters("--format=%s", "--no-merges", "$remote/$base..HEAD")
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.output.map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    private fun remote(repository: GitRepository) =
        repository.remotes.firstOrNull { it.name == "origin" } ?: repository.remotes.firstOrNull()

    /** `gh pr create` cannot push without a prompt, so the branch is pushed with the IDE's git first. Blocking. */
    fun pushCurrentBranch(project: Project, repository: GitRepository, branch: String) {
        val remote = remote(repository) ?: throw GhException(BidaideBundle.message("github.pr.create.noRemotes"))
        val handler = GitLineHandler(project, repository.root, GitCommand.PUSH)
        handler.setUrls(remote.urls)
        handler.addParameters("--set-upstream", remote.name, branch)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw GhException(BidaideBundle.message("github.pr.create.pushFailed", result.errorOutputAsJoinedString))
        repository.update()
    }
}
