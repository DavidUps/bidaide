package com.bidaide.github.inbox

import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhNotification
import com.bidaide.gh.GhNotifications
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GitHubUser
import com.bidaide.github.review.PrReviewDiff
import com.bidaide.github.ui.LazyTab
import com.bidaide.github.ui.RichRow
import com.bidaide.github.ui.RichRowRenderer
import com.bidaide.github.ui.TimeAgo
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The GitHub notifications inbox, laid out like github.com/notifications: views on the left (inbox, filters by
 * reason, one per repository), the notifications in the middle, and "all / unread" plus a search box on top.
 * Everything is fetched once per reload; the views are filters over that, so switching between them is instant.
 */
class InboxPanel(private val project: Project) : JPanel(BorderLayout()), LazyTab {
    override var loaded = false
        private set

    private var notifications: List<GhNotification> = emptyList()

    /** `owner/name` gh resolves in this project; only its pull requests can be reviewed in the IDE. */
    private var currentRepo: String? = null

    private val viewsModel = CollectionListModel<Inbox.View>(Inbox.views(emptyList()))
    private val views: JBList<Inbox.View> = JBList(viewsModel)
    private val listModel = CollectionListModel<GhNotification>()
    private val list: JBList<GhNotification> = JBList(listModel)
    private val allToggle = JToggleButton("Todas", true)
    private val unreadToggle = JToggleButton("Sin leer")
    private val search = SearchTextField(false)

    private val openButton = JButton("Abrir")
    private val reviewButton = JButton("Revisar cambios")
    private val readButton = JButton("Marcar leída")
    private val doneButton = JButton("Hecho")
    private val unsubscribeButton = JButton("Dejar de seguir")

    private val selectedView: Inbox.View get() = views.selectedValue ?: Inbox.ALL

    init {
        views.selectionMode = ListSelectionModel.SINGLE_SELECTION
        views.cellRenderer = ViewRenderer()
        views.selectedIndex = 0
        views.addListSelectionListener { if (!it.valueIsAdjusting) refresh() }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RichRowRenderer(::row)
        list.emptyText.text = "Cargando..."
        list.addListSelectionListener { if (!it.valueIsAdjusting) updateButtons() }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let(::open)
                return true
            }
        }.installOn(list)

        ButtonGroup().apply {
            add(allToggle)
            add(unreadToggle)
        }
        allToggle.addActionListener { refresh() }
        unreadToggle.addActionListener { refresh() }
        search.textEditor.emptyText.text = "Buscar notificaciones"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })

        openButton.addActionListener { list.selectedValue?.let(::open) }
        reviewButton.addActionListener { list.selectedValue?.number?.let { PrReviewDiff.reopen(project, it) } }
        readButton.addActionListener { list.selectedValue?.let { mutate("Marcando notificación", GhNotifications.markRead(it.id)) } }
        doneButton.addActionListener { list.selectedValue?.let { mutate("Archivando notificación", GhNotifications.done(it.id)) } }
        unsubscribeButton.addActionListener { list.selectedValue?.let { mutate("Cancelando suscripción", GhNotifications.unsubscribe(it.id)) } }

        val actions = DefaultActionGroup(
            object : DumbAwareAction("Actualizar", null, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = reload()
            },
            object : DumbAwareAction("Marcar todas como leídas", null, AllIcons.Actions.Checked) {
                override fun actionPerformed(e: AnActionEvent) = markAllRead()
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("Bidaide.inbox", actions, true)
        toolbar.targetComponent = this

        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 6)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(toolbar.component)
                add(allToggle)
                add(unreadToggle)
            }, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                for (button in listOf(openButton, reviewButton, readButton, doneButton, unsubscribeButton)) add(button)
            }, BorderLayout.EAST)
        }
        val splitter = OnePixelSplitter(false, "Bidaide.inbox.splitter", 0.2f).apply {
            firstComponent = JBScrollPane(views).apply { border = JBUI.Borders.empty() }
            secondComponent = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
        }
        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        updateButtons()
    }

    override fun reload() {
        loaded = true
        GhCli.async(
            project, "Cargando notificaciones",
            onFailure = { list.emptyText.text = "No se pudo cargar. Revisa gh y pulsa Actualizar." },
            work = {
                val workDir = GhRepoContext.workDir(project)
                val repo = workDir?.let { runCatching { GhCli.run(it, GhCommands.repoName()).trim() }.getOrNull() }
                repo to GhNotifications.parseList(GhCli.run(workDir, GhNotifications.inbox()))
            },
        ) { (repo, loadedNotifications) ->
            currentRepo = repo
            notifications = loadedNotifications
            val keep = selectedView.key
            val fresh = Inbox.views(notifications)
            viewsModel.replaceAll(fresh)
            views.selectedIndex = fresh.indexOfFirst { it.key == keep }.coerceAtLeast(0)
            refresh()
        }
    }

    /** Re-applies view, "unread only" and search to what is already loaded. */
    private fun refresh() {
        val selectedId = list.selectedValue?.id
        val shown = Inbox.filter(notifications, selectedView, unreadToggle.isSelected, search.text)
        listModel.replaceAll(shown)
        list.emptyText.text = if (loaded) "Nada por aquí" else "Cargando..."
        shown.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        views.repaint()
        updateButtons()
    }

    private fun updateButtons() {
        val selected = list.selectedValue
        for (button in listOf(openButton, doneButton, unsubscribeButton)) button.isEnabled = selected != null
        readButton.isEnabled = selected?.unread == true
        reviewButton.isVisible = selected != null && reviewable(selected)
    }

    private fun reviewable(notification: GhNotification): Boolean =
        notification.type == "PullRequest" && notification.number != null && notification.repository.equals(currentRepo, ignoreCase = true)

    /** A pull request of this project opens in the review window; anything else on github.com. */
    private fun open(notification: GhNotification) {
        val number = notification.number
        if (number != null && reviewable(notification)) PrReviewDiff.reopen(project, number) else BrowserUtil.browse(notification.webUrl)
    }

    private fun mutate(title: String, args: List<String>) {
        GhCli.async(project, title, work = { GhCli.run(GhRepoContext.workDir(project), args) }) { reload() }
    }

    private fun markAllRead() {
        val answer = Messages.showYesNoDialog(
            project, "Se marcarán como leídas todas tus notificaciones, de todos los repositorios.", "Marcar todas como leídas",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        GhCli.async(project, "Marcando notificaciones", work = { GhCli.run(GhRepoContext.workDir(project), GhNotifications.markAllRead(false)) }) {
            Notifier.info(project, "GitHub", "Notificaciones marcadas como leídas")
            reload()
        }
    }

    private fun row(notification: GhNotification): RichRow = RichRow(
        title = notification.title,
        icon = icon(notification),
        unread = notification.unread,
        bold = notification.unread,
        overline = notification.repository + (notification.number?.let { " #$it" }.orEmpty()),
        note = Inbox.reason(notification),
        people = listOf(GitHubUser(notification.repository.substringBefore('/'), notification.ownerAvatarUrl)),
        time = TimeAgo.format(notification.updatedAt),
    )

    private fun icon(notification: GhNotification): Icon = when (notification.type) {
        "PullRequest" -> AllIcons.Vcs.Merge
        "Issue" -> AllIcons.General.TodoDefault
        "Release" -> AllIcons.Nodes.Tag
        "Discussion" -> AllIcons.General.Balloon
        "RepositoryVulnerabilityAlert", "RepositoryAdvisory" -> AllIcons.General.Warning
        "CheckSuite" -> AllIcons.RunConfigurations.TestState.Run
        else -> AllIcons.General.Information
    }

    /** Sidebar entries: a section title above the first entry of each section, and the unread count on the right. */
    private inner class ViewRenderer : DefaultListCellRenderer() {
        private val panel = JPanel(BorderLayout())
        private val header = JLabel()
        private val count = JLabel()
        private val line = JPanel(BorderLayout())

        init {
            header.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            header.border = JBUI.Borders.empty(10, 8, 2, 8)
            count.font = JBUI.Fonts.smallFont()
            count.border = JBUI.Borders.emptyRight(8)
            line.add(this, BorderLayout.CENTER)
            line.add(count, BorderLayout.EAST)
            panel.add(header, BorderLayout.NORTH)
            panel.add(line, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component {
            val view = value as Inbox.View
            super.getListCellRendererComponent(list, view.label, index, selected, false)
            border = JBUI.Borders.empty(4, 8)
            icon = when {
                view === Inbox.ALL -> AllIcons.Nodes.Folder
                view.repository != null -> AllIcons.Vcs.Vendors.Github
                else -> null
            }
            val unread = Inbox.unread(view, notifications)
            count.text = if (unread > 0) unread.toString() else ""
            count.foreground = if (selected) foreground else UIUtil.getContextHelpForeground()
            line.background = background
            line.isOpaque = true
            // A section title sits above the first view of each section, and belongs to the list, not to the entry.
            val previous = if (index > 0) viewsModel.getElementAt(index - 1).section else null
            header.isVisible = view.section != null && view.section != previous
            header.text = view.section.orEmpty()
            header.foreground = UIUtil.getContextHelpForeground()
            panel.background = list.background
            return panel
        }
    }
}
