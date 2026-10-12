package com.bidaide.github.review

import com.bidaide.Notifier
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhReviewCommands
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/** Hooks into every diff viewer; only acts on requests created by [PrReviewDiff]. */
class PrReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        request.getUserData(PrReviewDiff.ON_SHOWN)?.run()
        val review = request.getUserData(PrFileReview.KEY) ?: return
        if (viewer !is DiffViewerBase) return
        val controller = PrDiffCommentsController(viewer, review)
        if (controller.editors.isEmpty()) return
        controller.editors.forEach { it.putUserData(PrDiffCommentsController.KEY, controller) }
        // Viewers finish computing (and the unified one rebuilds its document) asynchronously; draw after each pass.
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() = controller.render()
        })
        review.session.addListener(viewer) { controller.render() }
    }
}

/** Draws review threads as inlays inside one diff viewer and starts new comments. */
class PrDiffCommentsController(private val viewer: DiffViewerBase, private val review: PrFileReview) {
    private val session = review.session
    private val ui = ThreadUiState()
    private val inlays = ArrayList<Inlay<*>>()
    private var draft: Inlay<*>? = null

    val editors: List<EditorEx> = when (viewer) {
        is TwosideTextDiffViewer -> viewer.editors
        is OnesideTextDiffViewer -> viewer.editors
        is UnifiedDiffViewer -> viewer.editors
        else -> emptyList()
    }

    fun render() {
        inlays.forEach(Disposer::dispose)
        inlays.clear()
        val scrollTarget = session.scrollToThreadId
        for (thread in session.liveThreads(review.file.filename)) {
            val (editor, line) = locate(sideOf(thread.side), thread.line ?: continue) ?: continue
            if (thread.id == scrollTarget) {
                ui.expandedResolved += thread.id
                session.scrollToThreadId = null
                editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)
            }
            addInlay(editor, line, ThreadPanels.build(session, thread, ui, cardWidth(editor), ::render))?.let(inlays::add)
        }
    }

    /** Opens a comment box under the caret line (or the selected lines) of [editor]. */
    fun startComment(editor: Editor) {
        if (editor !is EditorEx || editor !in editors) return
        val document = editor.document
        val selection = editor.selectionModel
        val caretLine = editor.caretModel.logicalPosition.line
        val firstLine = if (selection.hasSelection()) document.getLineNumber(selection.selectionStart) else caretLine
        val lastLine = if (selection.hasSelection()) {
            document.getLineNumber(maxOf(selection.selectionStart, selection.selectionEnd - 1))
        } else {
            caretLine
        }
        val end = position(editor, lastLine)
        val start = position(editor, firstLine)
        if (end == null || !review.commentable.contains(end.first, end.second)) {
            Notifier.warn(
                session.project, "PR #${session.number}",
                "GitHub solo admite comentarios en líneas cambiadas o en su contexto cercano.",
            )
            return
        }
        // A range is only valid when it stays on one side and inside the diff; otherwise comment the last line alone.
        val startLine = start?.takeIf { it.first == end.first && review.commentable.contains(it.first, it.second) }?.second
        val side = end.first.gitHubName
        val path = review.file.filename

        // A suggestion replaces head-side lines, so it is only offered there (and not in the merged unified view).
        val seed = if (end.first == Side.RIGHT && viewer !is UnifiedDiffViewer) {
            val from = if (startLine != null) firstLine else lastLine
            document.getText(TextRange(document.getLineStartOffset(from), document.getLineEndOffset(lastLine)))
        } else {
            null
        }

        fun finish(done: (Boolean) -> Unit): (Boolean) -> Unit = { ok ->
            if (ok) closeDraft()
            done(ok)
        }
        val toReview = InputPanel.Submit(if (session.hasPendingReview) "Añadir a la review" else "Iniciar review") { body, done ->
            val args = GhReviewCommands.addPendingThread(session.state.pullRequestId, path, body, side, end.second, startLine)
            session.mutate("Añadiendo comentario a la review", args, finish(done))
        }
        val now = InputPanel.Submit("Comentar ahora") { body, done ->
            val args = GhCommands.prCreateReviewComment(session.number, body, session.headSha, path, side, end.second, startLine)
            session.mutate("Comentando PR #${session.number}", args, finish(done))
        }
        // GitHub refuses standalone comments while a pending review exists.
        val submits = if (session.hasPendingReview) arrayOf(toReview) else arrayOf(now, toReview)

        closeDraft()
        val label = if (startLine != null && startLine != end.second) "Líneas $startLine–${end.second}" else "Línea ${end.second}"
        val panel = InputPanel(
            "$label · comentario nuevo", cardWidth(editor), suggestionSeed = seed, onCancel = ::closeDraft, submits = submits,
        )
        draft = addInlay(editor, lastLine, panel)
        panel.focusLater()
    }

    private fun closeDraft() {
        draft?.let(Disposer::dispose)
        draft = null
    }

    /** GitHub (side, 1-based line) → (editor, 0-based editor line). */
    private fun locate(side: Side, line: Int): Pair<EditorEx, Int>? {
        val located = when (viewer) {
            is TwosideTextDiffViewer -> viewer.getEditor(side) to line - 1
            is OnesideTextDiffViewer -> if (viewer.side == side) viewer.editor to line - 1 else null
            is UnifiedDiffViewer -> viewer.editor to viewer.transferLineToOneside(side, line - 1)
            else -> null
        } ?: return null
        return located.takeIf { it.second in 0 until maxOf(1, it.first.document.lineCount) }
    }

    /** (editor, 0-based editor line) → GitHub (side, 1-based line). */
    private fun position(editor: EditorEx, editorLine: Int): Pair<Side, Int>? = when (viewer) {
        is TwosideTextDiffViewer -> (if (editor === viewer.editor1) Side.LEFT else Side.RIGHT) to editorLine + 1
        is OnesideTextDiffViewer -> viewer.side to editorLine + 1
        is UnifiedDiffViewer -> viewer.transferLineFromOneside(editorLine).let { mapped ->
            // Lines present on both sides are commented on the head side, like GitHub does.
            val side = if (review.commentable.contains(Side.RIGHT, mapped.first[1] + 1)) Side.RIGHT else mapped.second
            side to mapped.first[side.index] + 1
        }
        else -> null
    }

    private fun addInlay(editor: EditorEx, editorLine: Int, component: JComponent): Inlay<*>? {
        val line = editorLine.coerceIn(0, maxOf(0, editor.document.lineCount - 1))
        val offset = editor.document.getLineEndOffset(line)
        val properties = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(), null, true, false, 0, offset,
        )
        return EditorEmbeddedComponentManager.getInstance().addComponent(editor, component, properties)
    }

    /** Cards adapt to the editor: side-by-side panes are half as wide as the unified one. */
    private fun cardWidth(editor: EditorEx): Int {
        val visible = editor.scrollingModel.visibleArea.width
        if (visible <= 0) return JBUI.scale(520)
        return (visible - JBUI.scale(90)).coerceIn(JBUI.scale(300), JBUI.scale(700))
    }

    companion object {
        val KEY: Key<PrDiffCommentsController> = Key.create("Bidaide.PrDiffCommentsController")
    }
}

/** "Comment on this line" for PR review diffs; lives in the diff toolbar and the diff editor context menu. */
class AddPrCommentAction : AnAction("Comentar línea en la PR", "Añade un comentario de revisión en esta línea", AllIcons.General.Balloon),
    DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.getUserData(PrDiffCommentsController.KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        editor.getUserData(PrDiffCommentsController.KEY)?.startComment(editor)
    }
}
