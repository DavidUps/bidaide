package com.bidaide.github.review

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.GhReviewThread
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffViewer
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
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.util.ui.JBUI
import javax.swing.Icon
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
        controller.installHover()
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
        threadMarks.forEach { it.dispose() }
        threadMarks.clear()
        clearHover()
        val scrollTarget = session.scrollToThreadId
        val byLine = LinkedHashMap<Pair<EditorEx, Int>, MutableList<GhReviewThread>>()
        for (thread in session.liveThreads(review.file.filename)) {
            val (editor, line) = locate(sideOf(thread.side), thread.line ?: continue) ?: continue
            byLine.getOrPut(editor to line) { ArrayList() } += thread
            if (thread.id == scrollTarget) {
                ui.expandedResolved += thread.id
                ui.collapsed -= thread.id
                session.scrollToThreadId = null
                editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)
            }
            if (thread.id in ui.collapsed) continue
            addInlay(editor, line, ThreadPanels.build(session, thread, ui, cardWidth(editor), ::render))?.let(inlays::add)
        }
        for ((place, threads) in byLine) addThreadMark(place.first, place.second, threads)
        renderHunks()
    }

    // --- A balloon in the gutter of every line with comments, to fold them away and back ---

    private val threadMarks = ArrayList<RangeHighlighter>()

    private fun addThreadMark(editor: EditorEx, line: Int, threads: List<GhReviewThread>) {
        if (editor.isDisposed) return
        editor.settings.isLineMarkerAreaShown = true
        val ids = threads.map { it.id }
        val folded = ids.all { it in ui.collapsed }
        val mark = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null)
        mark.gutterIconRenderer = GutterAction(
            AllIcons.General.Balloon,
            BidaideBundle.message(if (folded) "github.review.thread.show" else "github.review.thread.hide", threads.sumOf { it.comments.size }),
            GutterIconRenderer.Alignment.CENTER, "thread:$line:$folded",
        ) {
            if (folded) ui.collapsed -= ids.toSet() else ui.collapsed += ids
            render()
        }
        threadMarks += mark
    }

    // --- A "+" in the gutter of the commentable line under the mouse, as on github.com ---

    private var hoverMark: RangeHighlighter? = null
    private var hoverAt: Pair<EditorEx, Int>? = null

    fun installHover() {
        for (editor in editors) {
            editor.settings.isLineMarkerAreaShown = true
            editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
                override fun mouseMoved(e: EditorMouseEvent) = hover(editor, e)
            }, viewer)
            editor.addEditorMouseListener(object : EditorMouseListener {
                override fun mouseExited(e: EditorMouseEvent) = clearHover()
            }, viewer)
        }
    }

    private fun hover(editor: EditorEx, e: EditorMouseEvent) {
        val line = e.logicalPosition.line
        if (hoverAt == editor to line) return
        clearHover()
        if (line !in 0 until editor.document.lineCount) return
        val target = position(editor, line) ?: return
        if (!review.commentable.contains(target.first, target.second)) return
        val mark = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null)
        mark.gutterIconRenderer = GutterAction(
            AllIcons.General.InlineAdd, BidaideBundle.message("action.Bidaide.Pr.AddComment.text"), GutterIconRenderer.Alignment.RIGHT, "add:$line",
        ) { startComment(editor, line) }
        hoverMark = mark
        hoverAt = editor to line
    }

    private fun clearHover() {
        hoverMark?.dispose()
        hoverMark = null
        hoverAt = null
    }

    private class GutterAction(
        private val icon: Icon, private val tooltip: String, private val alignment: GutterIconRenderer.Alignment, private val id: String, private val onClick: () -> Unit,
    ) : GutterIconRenderer() {
        override fun getIcon() = icon

        override fun getTooltipText() = tooltip

        override fun isNavigateAction() = true

        override fun getAlignment() = alignment

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = onClick()
        }

        override fun equals(other: Any?) = other is GutterAction && other.id == id

        override fun hashCode() = id.hashCode()
    }

    // --- A checkbox in the gutter beside every change, as in the commit dialog, to tick changes off one by one ---

    private val hunkMarks = ArrayList<RangeHighlighter>()

    /** One change of the file as this viewer shows it: where its checkbox goes and what identifies it. */
    private class Hunk(val editor: EditorEx, val line: Int, val key: String)

    private fun hunks(): List<Hunk> = when (viewer) {
        // The checkbox sits on the head side: that is the code being reviewed.
        is SimpleDiffViewer -> viewer.diffChanges.map {
            Hunk(viewer.editor2, it.getStartLine(Side.RIGHT), ViewedHunks.key(it.fragment.startLine1, it.fragment.startLine2))
        }
        is UnifiedDiffViewer -> viewer.diffChanges.orEmpty().map {
            Hunk(viewer.editor, it.line1, ViewedHunks.key(it.lineFragment.startLine1, it.lineFragment.startLine2))
        }
        is OnesideTextDiffViewer -> listOf(Hunk(viewer.editor, 0, ViewedHunks.WHOLE_FILE))
        else -> emptyList()
    }

    private fun renderHunks() {
        hunkMarks.forEach { it.dispose() }
        hunkMarks.clear()
        val path = review.file.filename
        val all = hunks()
        val keys = all.map { it.key }
        session.hunksShown(path, keys)
        for (hunk in all) {
            val editor = hunk.editor
            if (editor.isDisposed || editor.document.lineCount == 0) continue
            editor.settings.isLineMarkerAreaShown = true
            val line = hunk.line.coerceIn(0, editor.document.lineCount - 1)
            val mark = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null)
            mark.gutterIconRenderer = HunkCheckbox(hunk.key, session.isHunkViewed(path, hunk.key)) { viewed ->
                session.setHunkViewed(path, hunk.key, viewed, keys)
            }
            hunkMarks += mark
        }
    }

    private class HunkCheckbox(private val key: String, private val viewed: Boolean, private val onToggle: (Boolean) -> Unit) : GutterIconRenderer() {
        override fun getIcon(): Icon = if (viewed) AllIcons.Diff.GutterCheckBoxSelected else AllIcons.Diff.GutterCheckBox

        override fun getTooltipText() =
            if (viewed) BidaideBundle.message("github.review.hunk.viewed") else BidaideBundle.message("github.review.hunk.markViewed")

        override fun isNavigateAction() = true

        override fun getAlignment() = Alignment.LEFT

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = onToggle(!viewed)
        }

        override fun equals(other: Any?) = other is HunkCheckbox && other.key == key && other.viewed == viewed

        override fun hashCode() = key.hashCode() * 31 + viewed.hashCode()
    }

    /**
     * Opens a comment box under [line] (by default the caret line) of [editor], or under the selected lines when the
     * selection covers it.
     */
    fun startComment(editor: Editor, line: Int? = null) {
        if (editor !is EditorEx || editor !in editors) return
        val document = editor.document
        val selection = editor.selectionModel
        val selected = if (selection.hasSelection()) {
            document.getLineNumber(selection.selectionStart)..document.getLineNumber(maxOf(selection.selectionStart, selection.selectionEnd - 1))
        } else {
            null
        }
        val target = line ?: editor.caretModel.logicalPosition.line
        val lines = selected?.takeIf { target in it } ?: target..target
        val firstLine = lines.first
        val lastLine = lines.last
        val end = position(editor, lastLine)
        val start = position(editor, firstLine)
        if (end == null || !review.commentable.contains(end.first, end.second)) {
            Notifier.warn(
                session.project, "PR #${session.number}",
                BidaideBundle.message("github.review.notCommentable"),
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
        val toReview = InputPanel.Submit(
            if (session.hasPendingReview) BidaideBundle.message("github.review.addToReview") else BidaideBundle.message("github.review.startReview"),
        ) { body, done ->
            val args = GhReviewCommands.addPendingThread(session.state.pullRequestId, path, body, side, end.second, startLine)
            session.mutate(BidaideBundle.message("github.review.addingComment"), args, finish(done))
        }
        val now = InputPanel.Submit(BidaideBundle.message("github.review.commentNow")) { body, done ->
            val args = GhCommands.prCreateReviewComment(session.number, body, session.headSha, path, side, end.second, startLine)
            session.mutate(BidaideBundle.message("github.pr.comment.task", session.number), args, finish(done))
        }
        // GitHub refuses standalone comments while a pending review exists.
        val submits = if (session.hasPendingReview) arrayOf(toReview) else arrayOf(toReview, now)

        closeDraft()
        val label = if (startLine != null && startLine != end.second) {
            BidaideBundle.message("github.review.lines", startLine, end.second)
        } else {
            BidaideBundle.message("github.review.line", end.second)
        }
        val panel = InputPanel(
            cardWidth(editor), session.viewerLogin, BidaideBundle.message("github.review.newComment", label),
            suggestionSeed = seed, onCancel = ::closeDraft, submits = submits,
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
class AddPrCommentAction : AnAction(
    BidaideBundle.message("action.Bidaide.Pr.AddComment.text"), BidaideBundle.message("action.Bidaide.Pr.AddComment.description"),
    AllIcons.General.Balloon,
),
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
