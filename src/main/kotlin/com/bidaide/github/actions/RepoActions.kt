package com.bidaide.github.actions

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhAccounts
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhEvents
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GhRepos
import com.bidaide.gh.GhSearch
import com.bidaide.github.current.CurrentBranchPrService
import com.bidaide.github.review.PrReviewDiff
import com.bidaide.github.ui.Html
import com.intellij.ide.BrowserUtil
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.io.File
import java.util.function.Supplier
import javax.swing.JComponent
import javax.swing.JList

/** Base for actions that only need an open project. */
abstract class GitHubProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/** Picks which of the accounts known to `gh` is the active one. */
class SwitchGitHubAccountAction : GitHubProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GhCli.async(project, BidaideBundle.message("github.account.reading"), work = { GhAccounts.parse(GhCli.run(null, GhAccounts.status())) }) { accounts ->
            if (accounts.size < 2) {
                Notifier.info(
                    project, "GitHub",
                    if (accounts.isEmpty()) {
                        BidaideBundle.message("github.account.none")
                    } else {
                        BidaideBundle.message("github.account.onlyOne", accounts.first().login)
                    },
                )
                return@async
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(accounts)
                .setTitle(BidaideBundle.message("github.account.choose"))
                .setItemChosenCallback { account ->
                    if (account.active) return@setItemChosenCallback
                    GhCli.async(project, BidaideBundle.message("github.account.switching", account.login), work = { GhCli.run(null, GhAccounts.switch(account)) }) {
                        Notifier.info(project, "GitHub", BidaideBundle.message("github.account.switched", account.login, account.host))
                        GhEvents.fire(project)
                        project.service<CurrentBranchPrService>().refresh()
                    }
                }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }
}

/** Searches code on GitHub and opens the hit locally when it belongs to this repository. */
class SearchGitHubCodeAction : GitHubProjectAction() {
    private enum class Where(private val text: Supplier<String>) {
        REPO(BidaideBundle.lazyMessage("github.search.repo")), OWNER(BidaideBundle.lazyMessage("github.search.owner")),
        EVERYWHERE(BidaideBundle.lazyMessage("github.search.everywhere"));

        override fun toString(): String = text.get()
    }

    private class Found(val repo: String?, val matches: List<com.bidaide.gh.GhCodeMatch>)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val queryField = JBTextField(e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.lineSequence()?.firstOrNull().orEmpty().trim(), 40)
        val whereCombo = ComboBox(Where.entries.toTypedArray())
        val dialog = object : DialogWrapper(project) {
            init {
                title = BidaideBundle.message("github.search.title")
                setOKButtonText(BidaideBundle.message("github.search.ok"))
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row(BidaideBundle.message("github.search.query")) { cell(queryField).align(AlignX.FILL).comment(BidaideBundle.message("github.search.query.comment")) }
                row(BidaideBundle.message("github.search.where")) { cell(whereCombo) }
            }

            override fun getPreferredFocusedComponent() = queryField

            override fun doValidate(): ValidationInfo? = if (queryField.text.isBlank()) ValidationInfo(BidaideBundle.message("github.search.empty"), queryField) else null
        }
        if (!dialog.showAndGet()) return
        val query = queryField.text
        val where = whereCombo.item
        GhCli.async(
            project, BidaideBundle.message("github.search.searching", query),
            work = {
                // Outside a GitHub repository the search still works, just without narrowing.
                val repo = GhRepoContext.workDir(project)?.let { dir -> runCatching { GhCli.run(dir, GhCommands.repoName()).trim() }.getOrNull() }
                val args = when (where) {
                    Where.REPO -> GhSearch.code(query, repo, null)
                    Where.OWNER -> GhSearch.code(query, null, repo?.substringBefore('/'))
                    else -> GhSearch.code(query, null, null)
                }
                Found(repo, GhSearch.parseCode(GhCli.run(null, args)))
            },
        ) { found -> showMatches(project, query, found) }
    }

    private fun showMatches(project: Project, query: String, found: Found) {
        if (found.matches.isEmpty()) {
            Notifier.info(project, "GitHub", BidaideBundle.message("github.search.noResults", query))
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(found.matches)
            .setTitle(BidaideBundle.message("github.search.results", found.matches.size, query))
            .setRenderer(object : ColoredListCellRenderer<com.bidaide.gh.GhCodeMatch>() {
                override fun customizeCellRenderer(
                    list: JList<out com.bidaide.gh.GhCodeMatch>, value: com.bidaide.gh.GhCodeMatch?, index: Int, selected: Boolean, hasFocus: Boolean,
                ) {
                    val match = value ?: return
                    append(match.path.substringAfterLast('/'))
                    append("  ${match.fragment.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(70)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${match.repository} · ${match.path.substringBeforeLast('/', "")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            })
            .setNamerForFiltering { "${it.path} ${it.fragment}" }
            .setItemChosenCallback { open(project, it, found.repo) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun open(project: Project, match: com.bidaide.gh.GhCodeMatch, currentRepo: String?) {
        val local = GhRepoContext.repository(project)?.root?.takeIf { match.repository.equals(currentRepo, ignoreCase = true) }
            ?.findFileByRelativePath(match.path)
        if (local == null) {
            BrowserUtil.browse(match.url)
            return
        }
        // The hit is from the default branch on GitHub; locally the text may have moved, so look for it.
        val needle = match.fragment.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val offset = FileDocumentManager.getInstance().getDocument(local)?.text?.indexOf(needle)?.takeIf { needle.isNotEmpty() && it >= 0 } ?: 0
        OpenFileDescriptor(project, local, offset).navigate(true)
    }
}

/** From a line of code to the pull request that introduced it: `git blame`, then GitHub's commit → PR lookup. */
class FindPullRequestForLineAction : GitHubFileAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.getData(CommonDataKeys.EDITOR) == null) e.presentation.isEnabledAndVisible = false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val repository = GhRepoContext.repository(project, file) ?: return
        val path = VfsUtilCore.getRelativePath(file, repository.root) ?: return
        val line = editor.caretModel.logicalPosition.line + 1
        GhCli.async(
            project, BidaideBundle.message("github.line.searching", line),
            work = {
                val handler = GitLineHandler(project, repository.root, GitCommand.BLAME)
                handler.addParameters("-L", "$line,$line", "--porcelain", "--", path)
                val blame = Git.getInstance().runCommand(handler)
                val sha = GhSearch.blamedCommit(blame.outputAsJoinedString)
                sha to sha?.let { GhSearch.parseCommitPulls(GhCli.run(VfsUtilCore.virtualToIoFile(repository.root), GhCommands.commitPulls(it))) }
            },
        ) { (sha, pull) ->
            when {
                sha == null -> Notifier.info(project, "GitHub", BidaideBundle.message("github.line.uncommitted", line))
                pull == null -> Notifier.info(project, "GitHub", BidaideBundle.message("github.line.noPullRequest", sha.take(7), line))
                else -> NotificationGroupManager.getInstance().getNotificationGroup("Bidaide")
                    .createNotification(BidaideBundle.message("github.line.found", line, pull.number), "${Html.esc(pull.title)} · commit ${sha.take(7)}", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple(BidaideBundle.message("github.pr.button.review")) { PrReviewDiff.reopen(project, pull.number) })
                    .addAction(NotificationAction.createSimple(BidaideBundle.message("github.pr.button.browse")) { BrowserUtil.browse(pull.url) })
                    .notify(project)
            }
        }
    }
}

/** `gh repo clone` into a folder of the user's choice, then opens it as a project. */
class CloneGitHubRepositoryAction : GitHubProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GhCli.async(
            project, BidaideBundle.message("github.clone.reading"),
            work = { GhCli.run(null, GhRepos.listMine()).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() },
        ) { repos -> askAndClone(project, repos) }
    }

    private fun askAndClone(project: Project, repos: List<String>) {
        val repoCombo = ComboBox(repos.toTypedArray()).apply { isEditable = true }
        val parentField = TextFieldWithBrowseButton().apply {
            text = project.basePath?.let { File(it).parent }.orEmpty()
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(BidaideBundle.message("github.clone.folderTitle")))
        }
        fun repo() = repoCombo.editor.item?.toString().orEmpty().trim()
        fun target() = File(parentField.text.trim(), repo().removeSuffix("/").removeSuffix(".git").substringAfterLast('/'))
        val dialog = object : DialogWrapper(project) {
            init {
                title = BidaideBundle.message("github.clone.title")
                setOKButtonText(BidaideBundle.message("github.clone.ok"))
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row(BidaideBundle.message("github.clone.repository")) { cell(repoCombo).align(AlignX.FILL).comment(BidaideBundle.message("github.clone.repository.comment")) }
                row(BidaideBundle.message("github.clone.folder")) { cell(parentField).align(AlignX.FILL).comment(BidaideBundle.message("github.clone.folder.comment")) }
            }

            override fun getPreferredFocusedComponent() = repoCombo

            override fun doValidate(): ValidationInfo? = when {
                repo().isEmpty() -> ValidationInfo(BidaideBundle.message("github.clone.noRepository"), repoCombo)
                !File(parentField.text.trim()).isDirectory -> ValidationInfo(BidaideBundle.message("github.clone.noFolder"), parentField)
                target().exists() -> ValidationInfo(BidaideBundle.message("github.clone.exists", target().path), parentField)
                else -> null
            }
        }
        if (!dialog.showAndGet()) return
        val repo = repo()
        val target = target()
        GhCli.async(project, BidaideBundle.message("github.clone.cloning", repo), work = { GhCli.run(null, GhRepos.clone(repo, target.path), GhCli.LONG_TIMEOUT_MILLIS) }) {
            VfsUtil.markDirtyAndRefresh(true, false, false, target)
            ProjectUtil.openOrImport(target.toPath(), project, true)
        }
    }
}

/** Creates a GitHub repository from the local git repository of the project (`gh repo create --source`). */
class PublishToGitHubAction : GitHubProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repository = GhRepoContext.repository(project)
        if (repository == null) {
            Notifier.warn(
                project, BidaideBundle.message("github.publish.title"),
                BidaideBundle.message("github.publish.noGit"),
            )
            return
        }
        val existing = GhRepoContext.gitHubRepos(project)
        if (existing.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project, BidaideBundle.message("github.publish.alreadyLinked", existing.joinToString()),
                BidaideBundle.message("github.publish.title"), Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        val remotes = repository.remotes.map { it.name }.toSet()
        val nameField = JBTextField(repository.root.name, 30)
        val descriptionField = JBTextField()
        val remoteField = JBTextField(if ("origin" in remotes) "github" else "origin", 12)
        val privateBox = JBCheckBox(BidaideBundle.message("github.publish.private"), true)
        val hasCommits = repository.currentRevision != null
        val pushBox = JBCheckBox(BidaideBundle.message("github.publish.push"), hasCommits).apply { isEnabled = hasCommits }
        val dialog = object : DialogWrapper(project) {
            init {
                title = BidaideBundle.message("github.publish.dialogTitle")
                setOKButtonText(BidaideBundle.message("github.release.publish"))
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row(BidaideBundle.message("github.publish.name")) { cell(nameField).align(AlignX.FILL).comment(BidaideBundle.message("github.publish.name.comment")) }
                row(BidaideBundle.message("github.gist.description")) { cell(descriptionField).align(AlignX.FILL) }
                row(BidaideBundle.message("github.publish.remote")) { cell(remoteField) }
                row {
                    cell(privateBox)
                    cell(pushBox)
                }
                if (!hasCommits) row { comment(BidaideBundle.message("github.publish.noCommits")) }
            }

            override fun doValidate(): ValidationInfo? = when {
                nameField.text.isBlank() -> ValidationInfo(BidaideBundle.message("github.publish.noName"), nameField)
                remoteField.text.trim() in remotes -> ValidationInfo(BidaideBundle.message("github.publish.remoteExists"), remoteField)
                remoteField.text.isBlank() -> ValidationInfo(BidaideBundle.message("github.publish.noRemote"), remoteField)
                else -> null
            }
        }
        if (!dialog.showAndGet()) return
        val args = GhRepos.publish(nameField.text.trim(), descriptionField.text, privateBox.isSelected, remoteField.text.trim(), pushBox.isSelected)
        GhCli.async(
            project, BidaideBundle.message("github.publish.publishing"),
            work = {
                val output = GhCli.run(VfsUtilCore.virtualToIoFile(repository.root), args, GhCli.LONG_TIMEOUT_MILLIS)
                repository.update()
                output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("https://") }.orEmpty()
            },
        ) { url ->
            Notifier.info(project, BidaideBundle.message("github.publish.done"), Html.link(url, url))
            GhEvents.fire(project)
        }
    }
}
