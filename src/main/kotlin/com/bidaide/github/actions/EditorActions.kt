package com.bidaide.github.actions

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.issues.IssuesPanel
import com.bidaide.github.ui.Html
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JComponent

/** What the user is pointing at: a file inside a git repository (or the repository itself when [path] is null). */
private class GitHubTarget(val workDir: File, val path: String?, val lines: IntRange?, val branch: String?, val commit: String?)

/** The file under the cursor/selection, falling back to the active editor tab so the main menu works from any focus. */
private fun contextFile(e: AnActionEvent): VirtualFile? =
    e.getData(CommonDataKeys.VIRTUAL_FILE) ?: e.project?.let { FileEditorManager.getInstance(it).selectedFiles.firstOrNull() }

private fun target(e: AnActionEvent): GitHubTarget? {
    val project = e.project ?: return null
    val file = contextFile(e)
    val repository = GhRepoContext.repository(project, file) ?: return null
    val path = file?.let { VfsUtilCore.getRelativePath(it, repository.root) }?.takeIf { it.isNotEmpty() }
    val editor = e.getData(CommonDataKeys.EDITOR)?.takeIf { path != null && FileDocumentManager.getInstance().getFile(it.document) == file }
    val lines = editor?.let {
        val document = editor.document
        val selection = editor.selectionModel
        if (selection.hasSelection()) {
            // A selection ending at column 0 does not really include that last line.
            val endOffset = maxOf(selection.selectionStart, selection.selectionEnd - 1)
            document.getLineNumber(selection.selectionStart) + 1..document.getLineNumber(endOffset) + 1
        } else {
            val line = editor.caretModel.logicalPosition.line + 1
            line..line
        }
    }
    return GitHubTarget(VfsUtilCore.virtualToIoFile(repository.root), path, lines, repository.currentBranchName, repository.currentRevision)
}

/** Enabled whenever the project has a git repository; without a file in context it targets the repository itself. */
abstract class GitHubFileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && GhRepoContext.repository(project, contextFile(e)) != null
    }
}

/** Opens the file (and line range) on GitHub at the current branch, via `gh browse`. */
class OpenInGitHubAction : GitHubFileAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        val args = GhCommands.browse(target.path, target.lines, target.branch, commit = null, printOnly = false)
        GhCli.async(project, BidaideBundle.message("github.action.opening"), work = { GhCli.run(target.workDir, args) }) {}
    }
}

/** Copies a permalink (pinned to the current commit) for the file and line range. */
class CopyGitHubLinkAction : GitHubFileAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        val args = GhCommands.browse(target.path, target.lines, target.branch, target.commit, printOnly = true)
        GhCli.async(project, BidaideBundle.message("github.action.gettingLink"), work = { GhCli.run(target.workDir, args).trim() }) { url ->
            CopyPasteManager.getInstance().setContents(StringSelection(url))
            Notifier.info(project, BidaideBundle.message("github.action.linkCopied"), Html.link(url, url))
        }
    }
}

/** Creates a gist from the editor selection, or from the whole file when nothing is selected. */
class CreateGistAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = contextFile(e)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = contextFile(e) ?: return
        val selection = e.getData(CommonDataKeys.EDITOR)
            ?.takeIf { FileDocumentManager.getInstance().getFile(it.document) == file }
            ?.selectionModel?.selectedText
        val dialog = GistDialog(project, file, selection != null)
        if (!dialog.showAndGet()) return
        GhCli.async(
            project, BidaideBundle.message("github.gist.creating"),
            work = { createGist(file, selection, dialog.description, dialog.isPublic) },
        ) { url ->
            CopyPasteManager.getInstance().setContents(StringSelection(url))
            Notifier.info(project, BidaideBundle.message("github.gist.created"), Html.link(url, url))
        }
    }

    private fun createGist(file: VirtualFile, selection: String?, description: String, public: Boolean): String {
        // gh names the gist file after the path it is given, so the content goes to a temp file with the original name.
        val dir = FileUtil.createTempDirectory("bidaide-gist", null, true)
        try {
            val copy = File(dir, file.name)
            if (selection != null) copy.writeText(selection) else copy.writeBytes(file.contentsToByteArray())
            val out = GhCli.run(null, GhCommands.gistCreate(copy.path, description, public))
            return out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("http") } ?: out.trim()
        } finally {
            FileUtil.delete(dir)
        }
    }
}

private class GistDialog(project: Project, file: VirtualFile, selectionOnly: Boolean) : DialogWrapper(project) {
    private val descriptionField = JBTextField()
    private val publicBox = JBCheckBox(BidaideBundle.message("github.gist.public"))
    private val subject = if (selectionOnly) BidaideBundle.message("github.gist.selectionOf", file.name) else file.name

    val description: String get() = descriptionField.text.trim()
    val isPublic: Boolean get() = publicBox.isSelected

    init {
        title = BidaideBundle.message("github.gist.title")
        setOKButtonText(BidaideBundle.message("github.pr.create.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(BidaideBundle.message("github.gist.content", subject)) }
        row(BidaideBundle.message("github.gist.description")) { cell(descriptionField).align(AlignX.FILL) }
        row { cell(publicBox) }
    }
}

/** Opens the "new issue" form prefilled from the selection (or the caret line): a TODO becomes the title, the code the body. */
class CreateIssueFromSelectionAction : GitHubFileAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val code = editor?.let {
            it.selectionModel.selectedText ?: it.document.getText(
                TextRange(it.document.getLineStartOffset(it.caretModel.logicalPosition.line), it.document.getLineEndOffset(it.caretModel.logicalPosition.line))
            )
        }.orEmpty()
        val args = GhCommands.browse(target.path, target.lines, target.branch, target.commit, printOnly = true)
        GhCli.async(project, BidaideBundle.message("github.issue.preparing"), work = { GhCli.run(target.workDir, args).trim() }) { permalink ->
            val language = target.path?.substringAfterLast('.', "").orEmpty()
            IssuesPanel.create(project, IssueDrafts.title(code), IssueDrafts.body(permalink, code, language))
        }
    }
}

object IssueDrafts {
    private val COMMENT_PREFIX = Regex("""^\s*(//+|/\*+|\*+|#+|<!--|--)\s*""")
    private val TODO_PREFIX = Regex("""^(TODO|FIXME|HACK|XXX)\b\s*(\([^)]*\))?\s*:?\s*""", RegexOption.IGNORE_CASE)

    /** First non-blank line, without comment markers or a leading TODO/FIXME tag. */
    fun title(code: String): String {
        val line = code.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return line.replace(COMMENT_PREFIX, "").replace(TODO_PREFIX, "").removeSuffix("*/").removeSuffix("-->").trim().take(120)
    }

    /** GitHub expands a permalink on its own line into an embedded snippet, so the code block is only a fallback. */
    fun body(permalink: String, code: String, language: String): String = buildString {
        append(permalink).append("\n")
        if (code.isNotBlank()) append("\n```").append(language).append("\n").append(code.trimEnd()).append("\n```\n")
    }
}

/** Opens the tool window with the pull requests and issues tabs. */
class ShowGitHubToolWindowAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Bidaide")?.activate(null)
    }
}
