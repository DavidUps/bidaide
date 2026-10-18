package com.bidaide.github.ui

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhEvents
import com.bidaide.gh.GhRepoContext
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * List on the left, details of the selected item on the right, everything fed by `gh ... --json`.
 * Shared by the pull requests and issues tabs.
 */
abstract class GhListDetailPanel<T : Any>(
    protected val project: Project,
    private val noun: String,
    /** First combo, usually the state (open/closed...). */
    filters: List<FilterOption>,
    /** Optional second combo with quick filters such as "mine"; hidden when empty. */
    scopes: List<FilterOption> = emptyList(),
    private val createText: String? = BidaideBundle.message("github.list.new", noun),
) : JPanel(BorderLayout()), LazyTab, Disposable {

    /** A combo entry: [label] is shown, [value] is handed to [listArgs]. */
    class FilterOption(val label: String, val value: String)

    private val model = CollectionListModel<T>()
    private val list = JBList(model)
    private val stateCombo = ComboBox(filters.toTypedArray())
    private val scopeCombo = ComboBox(scopes.toTypedArray())
    private var reloading = false
    private val searchField = SearchTextField(false)
    private val detailPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    }
    private val buttons = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))

    private val detail = JPanel(BorderLayout())
    private val detailScroll = JBScrollPane(detailPane)
    private val listScroll = JBScrollPane(list)
    private val splitter = OnePixelSplitter(false, "Bidaide.$noun.splitter", 0.4f)

    /** Holds the list or, in board mode, the board, so the list spinner covers whichever is shown. */
    private val listHolder = JPanel(BorderLayout()).apply { add(listScroll, BorderLayout.CENTER) }
    private val listLoading = GhLoadingPanel(listHolder, this)
    private val detailLoading = GhLoadingPanel(detail, this)

    private val headerHolder = JPanel(BorderLayout())

    /** What currently fills the detail area instead of the HTML pane, if [detailComponent] provided something. */
    private var customDetail: JComponent? = null

    /** Bumped on every detail request so a slow response for a previous selection is dropped. */
    private var detailRequest = 0

    protected abstract fun listArgs(filter: String, scope: String, search: String): List<String>
    protected abstract fun parseList(json: String): List<T>
    protected abstract fun detailArgs(item: T): List<String>
    protected abstract fun parseDetail(json: String): T

    /** Blocking, off the EDT. Override when the list needs more than one `gh` call. */
    protected open fun fetchList(workDir: File, filter: String, scope: String, search: String): List<T> =
        parseList(GhCli.run(workDir, listArgs(filter, scope, search)))

    /** Blocking, off the EDT. Override to enrich the detail with further `gh` calls. */
    protected open fun fetchDetail(workDir: File, item: T): T = parseDetail(GhCli.run(workDir, detailArgs(item)))

    protected open val searchHint: String get() = BidaideBundle.message("github.list.searchHint")

    /** False until the first [reload]; tabs are loaded lazily, when first shown. */
    final override var loaded = false
        private set
    /** Stable identity, used to keep the selection across reloads. */
    protected abstract fun id(item: T): Long

    /** How the item is called in progress messages, e.g. "#12". */
    protected open fun title(item: T): String = "#${id(item)}"
    protected abstract fun renderCell(renderer: ColoredListCellRenderer<T>, item: T)

    /**
     * Two-line rows with labels, status and avatars instead of the single line of [renderCell]; null keeps that
     * line. Called while this class is still being constructed: do not touch subclass state here, only when the
     * returned row is asked for.
     */
    protected open fun richRow(): ((T) -> RichRow)? = null

    /** How many of [detailButtons] are shown as buttons; the rest go into a "More" menu. The first one is highlighted. */
    protected open val visibleButtons: Int get() = Int.MAX_VALUE
    protected abstract fun detailHtml(item: T): String
    protected abstract fun detailButtons(item: T): List<JButton>
    protected open fun create() {}

    /** Called on the EDT with the freshly loaded items, before the selection is restored. */
    protected open fun onItemsLoaded(items: List<T>) {}

    /** An optional strip between the buttons and the detail of [item], for what HTML cannot show (live images, say). */
    protected open fun detailHeader(item: T): JComponent? = null

    /** A component to show for [item] instead of the HTML of [detailHtml]; null (the default) keeps the HTML. */
    protected open fun detailComponent(item: T): JComponent? = null

    /**
     * Swaps the list for [board] (stacked above the detail, since a board needs the width), or back to the list
     * with null. The hidden list still drives the detail: see [select].
     */
    protected fun showBoard(board: JComponent?) {
        splitter.orientation = board != null
        listHolder.removeAll()
        listHolder.add(board ?: listScroll, BorderLayout.CENTER)
        listHolder.revalidate()
        listHolder.repaint()
        splitter.proportion = if (board != null) 0.6f else 0.4f
    }

    /** Selects the item with this id, which loads its detail. */
    protected fun select(id: Long) {
        val index = model.items.indexOfFirst { id(it) == id }
        if (index >= 0) list.selectedIndex = index
    }

    /** Milliseconds until the list reloads by itself given what it shows now, or null for no auto-refresh. */
    protected open fun autoRefreshMillis(items: List<T>): Int? = null

    private val autoRefresh = Timer(0) { if (isShowing) reload() }.apply { isRepeats = false }

    /** Extra toolbar actions, after refresh and create. */
    protected open fun extraActions(): List<AnAction> = emptyList()

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // While loading, the spinner says so; the empty text is only for what came back.
        list.emptyText.text = ""
        list.cellRenderer = richRow()?.let { RichRowRenderer(it) } ?: object : ColoredListCellRenderer<T>() {
            override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value?.let { renderCell(this, it) }
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting && !reloading) loadDetail() }

        for (combo in listOf(stateCombo, scopeCombo)) {
            combo.renderer = SimpleListCellRenderer.create("") { it.label }
            combo.addActionListener { reload() }
        }
        stateCombo.isVisible = filters.isNotEmpty()
        scopeCombo.isVisible = scopes.isNotEmpty()
        searchField.textEditor.emptyText.text = searchHint
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    searchField.addCurrentTextToHistory()
                    reload()
                }
            }
        })

        val actions = DefaultActionGroup()
        actions.add(object : DumbAwareAction(BidaideBundle.message("github.list.refresh"), null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = reload()
        })
        if (createText != null) {
            actions.add(object : DumbAwareAction(createText, null, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = create()
            })
        }
        actions.addAll(extraActions())
        actions.add(object : DumbAwareAction(BidaideBundle.message("github.list.chooseRepository"), null, AllIcons.Vcs.Vendors.Github) {
            override fun actionPerformed(e: AnActionEvent) = chooseRepository()
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("Bidaide.$noun", actions, true)
        toolbar.targetComponent = this

        val header = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(toolbar.component, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                add(scopeCombo)
                add(stateCombo)
            }, BorderLayout.EAST)
        }
        detail.add(JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.NORTH)
            add(headerHolder, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        detail.add(detailScroll, BorderLayout.CENTER)
        splitter.firstComponent = listLoading.component
        splitter.secondComponent = detailLoading.component
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    /** With several GitHub remotes `gh` needs to be told which one to use; this stores the choice via `gh repo set-default`. */
    private fun chooseRepository() {
        val repos = GhRepoContext.gitHubRepos(project)
        if (repos.isEmpty()) {
            Notifier.warn(project, "GitHub", BidaideBundle.message("github.list.noRemotes"))
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(repos)
            .setTitle(BidaideBundle.message("github.list.repositoryTitle"))
            .setItemChosenCallback { repo ->
                GhCli.async(
                    project, BidaideBundle.message("github.list.selectingRepository", repo),
                    work = { GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.repoSetDefault(repo)) },
                ) {
                    Notifier.info(project, "GitHub", BidaideBundle.message("github.list.repositorySelected", repo))
                    GhEvents.fire(project)
                }
            }
            .createPopup()
            .showInCenterOf(this)
    }

    final override fun reload() {
        loaded = true
        val filter = stateCombo.item?.value.orEmpty()
        val scope = scopeCombo.item?.value.orEmpty()
        val search = searchField.text
        if (model.isEmpty) list.emptyText.text = ""
        listLoading.async(
            project, BidaideBundle.message("github.list.loadingList", noun),
            onFailure = {
                model.removeAll()
                list.emptyText.text = BidaideBundle.message("github.list.loadFailed")
            },
            work = { fetchList(GhRepoContext.requireWorkDir(project), filter, scope, search) },
        ) { items ->
            // Read the selection now, not when the request started: the user may have moved meanwhile.
            val selectedId = list.selectedValue?.let(::id)
            reloading = true
            try {
                model.replaceAll(items)
                list.emptyText.text = BidaideBundle.message("github.list.noResults")
                onItemsLoaded(items)
                val index = items.indexOfFirst { id(it) == selectedId }
                if (index >= 0) list.selectedIndex = index else list.clearSelection()
            } finally {
                reloading = false
            }
            loadDetail()
            autoRefresh.stop()
            autoRefreshMillis(items)?.let {
                autoRefresh.initialDelay = it
                autoRefresh.start()
            }
        }
    }

    private fun loadDetail() {
        val item = list.selectedValue
        val request = ++detailRequest
        if (item == null) {
            showDetail(null)
            return
        }
        detailLoading.async(
            project, BidaideBundle.message("github.list.loadingItem", noun, title(item)),
            work = { fetchDetail(GhRepoContext.requireWorkDir(project), item) },
        ) { detail ->
            if (request == detailRequest) showDetail(detail)
        }
    }

    private var shownId: Long? = null

    private fun showDetail(item: T?) {
        // Periodic refreshes re-render the same item; keep the reader's scroll position then.
        val sameItem = item != null && shownId == id(item)
        val scroll = detailPane.visibleRect
        shownId = item?.let(::id)
        buttons.removeAll()
        headerHolder.removeAll()
        item?.let(::detailHeader)?.let { headerHolder.add(it, BorderLayout.CENTER) }
        headerHolder.revalidate()
        val custom = item?.let(::detailComponent)
        if (custom !== customDetail) {
            detail.remove(customDetail ?: detailScroll)
            detail.add(custom ?: detailScroll, BorderLayout.CENTER)
            customDetail = custom
            detail.revalidate()
            detail.repaint()
        }
        if (item == null) {
            detailPane.text = ""
        } else {
            showButtons(detailButtons(item))
            if (custom == null) {
                detailPane.text = Html.page(detailHtml(item))
                detailPane.caretPosition = 0
                if (sameItem) SwingUtilities.invokeLater { detailPane.scrollRectToVisible(scroll) }
            }
        }
        buttons.revalidate()
        buttons.repaint()
    }

    protected val selectedItem: T? get() = list.selectedValue

    /** The main action stands out, a few more sit beside it, and everything else is one click away under "More". */
    private fun showButtons(all: List<JButton>) {
        // A menu holding a single action would only hide it, so it takes at least two to be worth one.
        // (Written as a subtraction: visibleButtons is Int.MAX_VALUE for panels that never fold their buttons.)
        if (!foldsButtons(all.size, visibleButtons)) {
            all.forEach(buttons::add)
            return
        }
        all.first().putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        all.take(visibleButtons).forEach(buttons::add)
        val rest = all.drop(visibleButtons)
        lateinit var more: JButton
        more = JButton(BidaideBundle.message("github.list.more")).apply {
            addActionListener {
                val menu = JBPopupMenu()
                // Each entry presses the real button, so there is one place where an action is defined.
                rest.forEach { hidden -> menu.add(JBMenuItem(hidden.text).apply { addActionListener { hidden.doClick() } }) }
                menu.show(more, 0, more.height)
            }
        }
        buttons.add(more)
    }

    companion object {
        /** Whether [total] buttons, of which [visible] may show, leave enough over to deserve a "More" menu. */
        internal fun foldsButtons(total: Int, visible: Int): Boolean = total - visible >= 2
    }

    override fun dispose() = autoRefresh.stop()

    protected fun button(text: String, onClick: () -> Unit) = JButton(text).apply { addActionListener { onClick() } }

    /** Runs a mutating `gh` command, tells the user, and refreshes list and detail. */
    protected fun runAndReload(title: String, args: List<String>, done: String) {
        GhCli.async(project, title, work = { GhCli.run(GhRepoContext.requireWorkDir(project), args) }) {
            Notifier.info(project, "GitHub", done)
            reload()
        }
    }
}
