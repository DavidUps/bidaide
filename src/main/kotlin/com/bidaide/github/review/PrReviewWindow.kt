package com.bidaide.github.review

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhCommit
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhCommands.ReviewKind
import com.bidaide.gh.GhPrFile
import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.GhReviewThread
import com.bidaide.gh.ReviewEvent
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.FrameWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Stand-alone review window: changed files and conversations on the left, the IDE diff viewer on the right,
 * review actions on top.
 */
class PrReviewWindow(private val session: PrReviewSession) {
    private val project = session.project

    private class FileNode(val index: Int, val file: GhPrFile)

    /** What the diff shows: the whole pull request, or one of its commits ([commit] non-null, read-only). */
    private class Scope(val commit: GhCommit?, val files: List<GhPrFile>, val baseSha: String?, val headSha: String) {
        val interactive: Boolean get() = commit == null
    }

    private val wholePr = Scope(null, session.files, session.mergeBaseSha, session.headSha)
    private var scope = wholePr
    private var processor = newProcessor()
    private var currentIndex = 0

    /** Bumped on every scope change so file-shown callbacks of a replaced diff are ignored. */
    private var scopeGeneration = 0

    private val fileNodes = ArrayList<DefaultMutableTreeNode>()
    private val tree = Tree(buildTreeModel())
    private val main = OnePixelSplitter(false, "Bidaide.PrReview.main", 0.24f)
    /** Combo entry standing for "the whole pull request". */
    private val allChanges = GhCommit()
    private val scopeCombo = ComboBox((listOf(allChanges) + session.pr.commits.orEmpty()).toTypedArray())
    private val threadsModel = CollectionListModel<GhReviewThread>()
    private val threadsList = JBList(threadsModel)

    private val submitButton = JButton()
    private val discardButton = JButton(BidaideBundle.message("github.review.discard"))
    private val summary = JBLabel()

    private val frame = FrameWrapper(project, "Bidaide.PrReview", false, "PR #${session.number} — ${session.pr.title}")

    fun show() {
        configureTree()
        configureThreads()

        val left = OnePixelSplitter(true, "Bidaide.PrReview.left", 0.6f).apply {
            firstComponent = titled(BidaideBundle.message("github.review.files"), JBScrollPane(tree))
            secondComponent = titled(BidaideBundle.message("gh.merge.conversations"), JBScrollPane(threadsList))
        }
        main.firstComponent = left
        main.secondComponent = processor.component
        val root = JPanel(BorderLayout()).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(main, BorderLayout.CENTER)
        }
        frame.component = root
        frame.preferredFocusedComponent = tree
        Disposer.register(frame, processor)
        session.addListener(frame) { refresh() }
        refresh()
        processor.updateRequest()
        fileNodes.firstOrNull()?.let { tree.selectionPath = TreePath(it.path) }
        frame.show()
    }

    private fun titled(title: String, content: JComponent) = JPanel(BorderLayout()).apply {
        add(JBLabel(title).apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    private fun toolbar(): JComponent {
        submitButton.addActionListener { submitReview() }
        discardButton.addActionListener { discardReview() }
        val refresh = JButton(BidaideBundle.message("github.list.refresh")).apply { addActionListener { session.refresh() } }
        val reload = JButton(BidaideBundle.message("github.review.reload")).apply {
            toolTipText = BidaideBundle.message("github.review.reload.tooltip")
            addActionListener {
                frame.close()
                PrReviewDiff.reopen(project, session.number)
            }
        }
        scopeCombo.renderer = SimpleListCellRenderer.create("") { commit ->
            if (commit === allChanges) BidaideBundle.message("github.review.allChanges") else "${commit.oid.take(7)}  ${commit.messageHeadline.take(70)}"
        }
        scopeCombo.toolTipText = BidaideBundle.message("github.review.scope.tooltip")
        scopeCombo.isVisible = scopeCombo.itemCount > 2
        scopeCombo.addActionListener { chooseScope(scopeCombo.item?.takeIf { it !== allChanges }) }
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(submitButton)
            add(discardButton)
            add(refresh)
            add(reload)
            add(scopeCombo)
            add(summary)
        }
    }

    // --- Whole pull request or a single commit ---

    private fun newProcessor(): CacheDiffRequestChainProcessor {
        val shown = scope
        val generation = scopeGeneration
        val producers = shown.files.mapIndexed { index, file ->
            PrFileDiffProducer(PrFileReview(session, file), shown.baseSha, shown.headSha, shown.interactive) {
                if (generation == scopeGeneration) fileShown(index)
            }
        }
        return CacheDiffRequestChainProcessor(project, SimpleDiffRequestChain.fromProducers(producers))
    }

    private fun chooseScope(commit: GhCommit?) {
        if (commit?.oid == scope.commit?.oid) return
        if (commit == null) {
            show(wholePr)
            return
        }
        GhCli.async(
            project, BidaideBundle.message("github.review.loadingCommit", commit.oid.take(7)),
            onFailure = { scopeCombo.selectedItem = scope.commit ?: allChanges },
            work = { GhJson.commitDetail(GhCli.run(session.workDir, GhCommands.commit(commit.oid))) },
        ) { detail ->
            // The user may have picked something else while this one loaded.
            if (scopeCombo.item === commit) show(Scope(commit, detail.files, detail.parent, commit.oid))
        }
    }

    private fun show(next: Scope) {
        scope = next
        scopeGeneration++
        val previous = processor
        processor = newProcessor()
        Disposer.register(frame, processor)
        main.secondComponent = processor.component
        Disposer.dispose(previous)
        fileNodes.clear()
        tree.model = buildTreeModel()
        TreeUtil.expandAll(tree)
        currentIndex = 0
        processor.updateRequest()
        fileNodes.firstOrNull()?.let { tree.selectionPath = TreePath(it.path) }
        refresh()
    }

    /** The diff viewer moved to another file by itself (next/previous change across files): follow it in the tree. */
    private fun fileShown(index: Int) {
        if (index == currentIndex) return
        currentIndex = index
        fileNodes.getOrNull(index)?.let {
            val path = TreePath(it.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun refresh() {
        val pending = session.pendingCount
        submitButton.text = if (session.hasPendingReview) BidaideBundle.message("github.review.submitPending", pending) else BidaideBundle.message("github.review.submit")
        discardButton.isVisible = session.hasPendingReview
        val open = session.threads.count { !it.isResolved }
        val reviewed = BidaideBundle.message("github.review.summary", session.viewedCount, session.files.size, open, session.threads.size)
        summary.text = scope.commit?.let { BidaideBundle.message("github.review.commitSummary", it.oid.take(7), scope.files.size, reviewed) } ?: reviewed
        threadsModel.replaceAll(session.threads.sortedWith(compareBy({ it.isResolved }, { it.path }, { it.line ?: it.originalLine ?: 0 })))
        tree.repaint()
    }

    // --- Files ---

    private fun buildTreeModel(): DefaultTreeModel {
        val root = DefaultMutableTreeNode("root")
        val directories = LinkedHashMap<String, DefaultMutableTreeNode>()
        scope.files.forEachIndexed { index, file ->
            val directory = file.filename.substringBeforeLast('/', "")
            val parent = if (directory.isEmpty()) root else directories.getOrPut(directory) {
                DefaultMutableTreeNode(directory).also(root::add)
            }
            DefaultMutableTreeNode(FileNode(index, file)).also {
                parent.add(it)
                fileNodes += it
            }
        }
        return DefaultTreeModel(root)
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is FileNode -> {
                        val file = item.file
                        val name = file.filename.substringAfterLast('/')
                        icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                        val status = when (file.status) {
                            "added" -> FileStatus.ADDED
                            "removed" -> FileStatus.DELETED
                            else -> FileStatus.MODIFIED
                        }
                        append(name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
                        if (scope.interactive) {
                            if (session.isViewed(file.filename)) append("  ✓ visto", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            // Partly reviewed: how many of its changes are ticked off.
                            session.hunkProgress(file.filename)?.let { append("  ◐ $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                            val count = session.threadCount(file.filename)
                            if (count > 0) append("  💬 $count", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        append("  +${file.additions} −${file.deletions}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    is String -> {
                        icon = AllIcons.Nodes.Folder
                        append(item, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }
        tree.addTreeSelectionListener {
            val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? FileNode ?: return@addTreeSelectionListener
            if (node.index != currentIndex) {
                currentIndex = node.index
                processor.setCurrentRequest(node.index)
            }
        }
        TreeUtil.expandAll(tree)
    }

    private fun selectFile(path: String): Boolean {
        val node = fileNodes.firstOrNull { (it.userObject as FileNode).file.filename == path } ?: return false
        val treePath = TreePath(node.path)
        tree.selectionPath = treePath
        tree.scrollPathToVisible(treePath)
        return true
    }

    // --- Conversations ---

    private fun configureThreads() {
        threadsList.emptyText.text = BidaideBundle.message("github.review.noThreads")
        threadsList.cellRenderer = object : ColoredListCellRenderer<GhReviewThread>() {
            override fun customizeCellRenderer(list: JList<out GhReviewThread>, value: GhReviewThread?, index: Int, selected: Boolean, hasFocus: Boolean) {
                val thread = value ?: return
                icon = if (thread.isResolved) AllIcons.Actions.Checked else AllIcons.General.Balloon
                append("${thread.path.substringAfterLast('/')}:${thread.line ?: thread.originalLine ?: "?"}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val root = thread.root
                append(root?.let { "${it.author}: ${it.body.lineSequence().firstOrNull().orEmpty().take(80)}" }.orEmpty())
                val tags = listOfNotNull(
                    "pendiente".takeIf { thread.hasPending }, "obsoleto".takeIf { thread.isOutdated },
                    "${thread.comments.size} mensajes".takeIf { thread.comments.size > 1 },
                )
                if (tags.isNotEmpty()) append("  ${tags.joinToString(" · ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                threadsList.selectedValue?.let(::openThread)
                return true
            }
        }.installOn(threadsList)
    }

    private fun openThread(thread: GhReviewThread) {
        if (thread.line == null || thread.isOutdated) {
            // The code under the thread is gone from the diff, so it cannot be shown inline.
            ThreadPopup.show(session, thread.id, threadsList)
            return
        }
        // Threads are only drawn on the diff of the whole pull request.
        if (!scope.interactive) scopeCombo.selectedItem = allChanges
        session.scrollToThreadId = thread.id
        val alreadyShown = scope.files.getOrNull(currentIndex)?.filename == thread.path
        if (!selectFile(thread.path)) return
        if (alreadyShown) session.requestRender()
    }

    // --- Review ---

    private fun submitReview() {
        val dialog = SubmitReviewDialog(session)
        if (!dialog.showAndGet()) return
        val event = dialog.event
        val args = if (session.hasPendingReview) {
            GhReviewCommands.submitPending(session.state.pullRequestId, event, dialog.body)
        } else {
            val kind = when (event) {
                ReviewEvent.APPROVE -> ReviewKind.APPROVE
                ReviewEvent.REQUEST_CHANGES -> ReviewKind.REQUEST_CHANGES
                ReviewEvent.COMMENT -> ReviewKind.COMMENT
            }
            GhCommands.prReview(session.number, kind, dialog.body)
        }
        session.mutate(BidaideBundle.message("github.pr.review.task", session.number), args) { ok ->
            if (ok) Notifier.info(project, "GitHub", BidaideBundle.message("github.review.sent", session.number, event.label.lowercase()))
        }
    }

    private fun discardReview() {
        val reviewId = session.state.pendingReviewId ?: return
        val answer = Messages.showYesNoDialog(
            project, BidaideBundle.message("github.review.discard.confirm", session.pendingCount),
            BidaideBundle.message("github.review.discard"), BidaideBundle.message("github.review.discard.ok"),
            BidaideBundle.message("common.cancel"), Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) session.mutate(BidaideBundle.message("github.review.discarding"), GhReviewCommands.discardPending(reviewId))
    }
}

private class SubmitReviewDialog(private val session: PrReviewSession) : DialogWrapper(session.project) {
    private val eventCombo = ComboBox(ReviewEvent.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val area = JBTextArea(8, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    val event: ReviewEvent get() = eventCombo.item
    val body: String get() = area.text.trim()

    init {
        title = BidaideBundle.message("github.review.submitTitle", session.number)
        setOKButtonText(BidaideBundle.message("github.review.send"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(BidaideBundle.message("github.review.verdict")) { cell(eventCombo) }
        if (session.hasPendingReview) row { label(BidaideBundle.message("github.review.willPublish", session.pendingCount)) }
        row { cell(JBScrollPane(area)).align(Align.FILL) }.resizableRow()
    }

    override fun getPreferredFocusedComponent() = area

    override fun doValidate(): ValidationInfo? = when {
        event == ReviewEvent.REQUEST_CHANGES && body.isEmpty() -> ValidationInfo(BidaideBundle.message("github.review.changesNeedComment"), area)
        // A bare "comment" review only makes sense when it publishes pending inline comments.
        event == ReviewEvent.COMMENT && body.isEmpty() && session.pendingCount == 0 -> ValidationInfo(BidaideBundle.message("github.review.writeComment"), area)
        else -> null
    }
}

/** Shows one thread in a floating popup; used for threads that no longer map to a line of the diff. */
object ThreadPopup {
    fun show(session: PrReviewSession, threadId: String, anchor: JComponent) {
        val holder = JPanel(BorderLayout())
        val ui = ThreadUiState().apply { expandedResolved += threadId }
        var popup: JBPopup? = null

        fun rebuild() {
            val thread = session.threads.firstOrNull { it.id == threadId }
            if (thread == null) {
                popup?.cancel()
                return
            }
            holder.removeAll()
            holder.add(ThreadPanels.build(session, thread, ui, JBUI.scale(560), ::rebuild), BorderLayout.CENTER)
            holder.revalidate()
            popup?.takeIf { it.isVisible }?.pack(true, true)
        }
        rebuild()
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(JBScrollPane(holder), holder)
            .setTitle(BidaideBundle.message("github.review.conversation"))
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnWindowDeactivation(false)
            .createPopup()
        popup = created
        session.addListener(created) { rebuild() }
        created.showInCenterOf(anchor)
    }
}
