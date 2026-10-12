package com.bidaide.github.current

import com.bidaide.gh.GhReviewThread
import com.bidaide.github.review.PrReviewSession
import com.bidaide.github.review.ThreadPopup
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import javax.swing.Icon

/**
 * With a PR branch checked out, marks in the gutter of regular editors the lines that have review threads;
 * clicking a marker opens the conversation. Lines come from the PR head, so they drift if the local file differs.
 */
@Service(Service.Level.PROJECT)
class PrEditorComments(private val project: Project) : Disposable {

    init {
        project.messageBus.connect(this).subscribe(CurrentBranchPrService.TOPIC, CurrentBranchPrService.Listener { applyToAll() })
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) apply(event.editor)
            }
        }, this)
    }

    private fun applyToAll() = EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::apply)

    private fun apply(editor: Editor) {
        editor.getUserData(HIGHLIGHTERS)?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(HIGHLIGHTERS, null)
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val session = project.service<CurrentBranchPrService>().session ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = FileUtil.getRelativePath(session.workDir.path, file.path, '/') ?: return

        val lineCount = editor.document.lineCount
        val highlighters = session.liveThreads(path)
            .filter { it.side == "RIGHT" && (it.line ?: 0) in 1..lineCount }
            .groupBy { it.line!! }
            .map { (line, threads) ->
                editor.markupModel.addLineHighlighter(line - 1, HighlighterLayer.LAST, null).apply {
                    gutterIconRenderer = ThreadMarker(session, threads, editor)
                }
            }
        if (highlighters.isNotEmpty()) editor.putUserData(HIGHLIGHTERS, highlighters)
    }

    override fun dispose() {}

    private class ThreadMarker(
        private val session: PrReviewSession,
        private val threads: List<GhReviewThread>,
        private val editor: Editor,
    ) : GutterIconRenderer() {
        private val ids = threads.map { it.id }
        private val resolved = threads.all { it.isResolved }

        override fun getIcon(): Icon = if (resolved) AllIcons.Actions.Checked else AllIcons.General.Balloon

        override fun getTooltipText(): String = threads.joinToString("\n") { thread ->
            val root = thread.root
            "${root?.author.orEmpty()}: ${root?.body?.lineSequence()?.firstOrNull().orEmpty().take(90)}" +
                if (thread.comments.size > 1) " (+${thread.comments.size - 1})" else ""
        }

        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                // Several threads on one line are rare; open the first unresolved one.
                val thread = threads.firstOrNull { !it.isResolved } ?: threads.first()
                ThreadPopup.show(session, thread.id, editor.component)
            }
        }

        override fun equals(other: Any?) = other is ThreadMarker && other.ids == ids && other.resolved == resolved

        override fun hashCode() = ids.hashCode()
    }

    private companion object {
        val HIGHLIGHTERS: Key<List<RangeHighlighter>> = Key.create("Bidaide.PrThreadHighlighters")
    }
}
