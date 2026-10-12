package com.bidaide.github.actions

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
        GhCli.async(project, "Leyendo cuentas de gh", work = { GhAccounts.parse(GhCli.run(null, GhAccounts.status())) }) { accounts ->
            if (accounts.size < 2) {
                Notifier.info(
                    project, "GitHub",
                    "gh solo tiene ${accounts.size} cuenta${accounts.firstOrNull()?.let { " (${it.login})" }.orEmpty()}. " +
                        "Añade otra con <code>gh auth login</code> en una terminal.",
                )
                return@async
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(accounts)
                .setTitle("Cuenta de GitHub activa (también en la terminal)")
                .setItemChosenCallback { account ->
                    if (account.active) return@setItemChosenCallback
                    GhCli.async(project, "Cambiando a ${account.login}", work = { GhCli.run(null, GhAccounts.switch(account)) }) {
                        Notifier.info(project, "GitHub", "gh usa ahora la cuenta ${account.login} en ${account.host}")
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
    private enum class Where(val label: String) {
        REPO("Este repositorio"), OWNER("Todo lo del propietario del repositorio"), EVERYWHERE("Todo GitHub");

        override fun toString() = label
    }

    private class Found(val repo: String?, val matches: List<com.bidaide.gh.GhCodeMatch>)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val queryField = JBTextField(e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.lineSequence()?.firstOrNull().orEmpty().trim(), 40)
        val whereCombo = ComboBox(Where.entries.toTypedArray())
        val dialog = object : DialogWrapper(project) {
            init {
                title = "Buscar código en GitHub"
                setOKButtonText("Buscar")
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row("Buscar:") { cell(queryField).align(AlignX.FILL).comment("Admite calificadores de GitHub: language:kotlin, path:app/, extension:xml...") }
                row("En:") { cell(whereCombo) }
            }

            override fun getPreferredFocusedComponent() = queryField

            override fun doValidate(): ValidationInfo? = if (queryField.text.isBlank()) ValidationInfo("Escribe qué buscar", queryField) else null
        }
        if (!dialog.showAndGet()) return
        val query = queryField.text
        val where = whereCombo.item
        GhCli.async(
            project, "Buscando «$query» en GitHub",
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
            Notifier.info(project, "GitHub", "Sin resultados para «$query». GitHub solo indexa la rama por defecto.")
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(found.matches)
            .setTitle("${found.matches.size} resultados de «$query»")
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
            project, "Buscando la PR de la línea $line",
            work = {
                val handler = GitLineHandler(project, repository.root, GitCommand.BLAME)
                handler.addParameters("-L", "$line,$line", "--porcelain", "--", path)
                val blame = Git.getInstance().runCommand(handler)
                val sha = GhSearch.blamedCommit(blame.outputAsJoinedString)
                sha to sha?.let { GhSearch.parseCommitPulls(GhCli.run(VfsUtilCore.virtualToIoFile(repository.root), GhCommands.commitPulls(it))) }
            },
        ) { (sha, pull) ->
            when {
                sha == null -> Notifier.info(project, "GitHub", "La línea $line aún no está en ningún commit.")
                pull == null -> Notifier.info(project, "GitHub", "El commit ${sha.take(7)} de la línea $line no llegó por ninguna pull request.")
                else -> NotificationGroupManager.getInstance().getNotificationGroup("Bidaide")
                    .createNotification("Línea $line: PR #${pull.number}", "${Html.esc(pull.title)} · commit ${sha.take(7)}", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple("Revisar cambios") { PrReviewDiff.reopen(project, pull.number) })
                    .addAction(NotificationAction.createSimple("Abrir en navegador") { BrowserUtil.browse(pull.url) })
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
            project, "Leyendo tus repositorios",
            work = { GhCli.run(null, GhRepos.listMine()).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() },
        ) { repos -> askAndClone(project, repos) }
    }

    private fun askAndClone(project: Project, repos: List<String>) {
        val repoCombo = ComboBox(repos.toTypedArray()).apply { isEditable = true }
        val parentField = TextFieldWithBrowseButton().apply {
            text = project.basePath?.let { File(it).parent }.orEmpty()
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Carpeta donde clonar"))
        }
        fun repo() = repoCombo.editor.item?.toString().orEmpty().trim()
        fun target() = File(parentField.text.trim(), repo().removeSuffix("/").removeSuffix(".git").substringAfterLast('/'))
        val dialog = object : DialogWrapper(project) {
            init {
                title = "Clonar repositorio de GitHub"
                setOKButtonText("Clonar")
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row("Repositorio:") { cell(repoCombo).align(AlignX.FILL).comment("propietario/nombre o URL. La lista son tus repositorios.") }
                row("Carpeta:") { cell(parentField).align(AlignX.FILL).comment("Se crea dentro una carpeta con el nombre del repositorio.") }
            }

            override fun getPreferredFocusedComponent() = repoCombo

            override fun doValidate(): ValidationInfo? = when {
                repo().isEmpty() -> ValidationInfo("Indica el repositorio", repoCombo)
                !File(parentField.text.trim()).isDirectory -> ValidationInfo("La carpeta no existe", parentField)
                target().exists() -> ValidationInfo("Ya existe ${target().path}", parentField)
                else -> null
            }
        }
        if (!dialog.showAndGet()) return
        val repo = repo()
        val target = target()
        GhCli.async(project, "Clonando $repo", work = { GhCli.run(null, GhRepos.clone(repo, target.path), GhCli.LONG_TIMEOUT_MILLIS) }) {
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
                project, "Publicar en GitHub",
                "El proyecto aún no es un repositorio git. Créalo con VCS → Create Git Repository, haz un primer commit y repite.",
            )
            return
        }
        val existing = GhRepoContext.gitHubRepos(project)
        if (existing.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project, "Este proyecto ya está enlazado a ${existing.joinToString()}. ¿Publicarlo además como un repositorio nuevo?",
                "Publicar en GitHub", Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        val remotes = repository.remotes.map { it.name }.toSet()
        val nameField = JBTextField(repository.root.name, 30)
        val descriptionField = JBTextField()
        val remoteField = JBTextField(if ("origin" in remotes) "github" else "origin", 12)
        val privateBox = JBCheckBox("Privado", true)
        val hasCommits = repository.currentRevision != null
        val pushBox = JBCheckBox("Hacer push de la rama actual", hasCommits).apply { isEnabled = hasCommits }
        val dialog = object : DialogWrapper(project) {
            init {
                title = "Publicar proyecto en GitHub"
                setOKButtonText("Publicar")
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row("Nombre:") { cell(nameField).align(AlignX.FILL).comment("nombre, o organización/nombre.") }
                row("Descripción:") { cell(descriptionField).align(AlignX.FILL) }
                row("Remoto:") { cell(remoteField) }
                row {
                    cell(privateBox)
                    cell(pushBox)
                }
                if (!hasCommits) row { comment("El repositorio local no tiene commits todavía: se creará vacío en GitHub.") }
            }

            override fun doValidate(): ValidationInfo? = when {
                nameField.text.isBlank() -> ValidationInfo("Indica el nombre", nameField)
                remoteField.text.trim() in remotes -> ValidationInfo("Ya existe un remoto con ese nombre", remoteField)
                remoteField.text.isBlank() -> ValidationInfo("Indica el nombre del remoto", remoteField)
                else -> null
            }
        }
        if (!dialog.showAndGet()) return
        val args = GhRepos.publish(nameField.text.trim(), descriptionField.text, privateBox.isSelected, remoteField.text.trim(), pushBox.isSelected)
        GhCli.async(
            project, "Publicando en GitHub",
            work = {
                val output = GhCli.run(VfsUtilCore.virtualToIoFile(repository.root), args, GhCli.LONG_TIMEOUT_MILLIS)
                repository.update()
                output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("https://") }.orEmpty()
            },
        ) { url ->
            Notifier.info(project, "Proyecto publicado en GitHub", Html.link(url, url))
            GhEvents.fire(project)
        }
    }
}
