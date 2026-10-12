package com.bidaide.github.inbox

import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhNotification
import com.bidaide.gh.GhNotifications
import com.bidaide.github.review.PrReviewDiff
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Html.esc
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.io.File
import javax.swing.JButton

/** The GitHub notifications inbox, for this repository or for everything. */
class InboxPanel(project: Project) : GhListDetailPanel<GhNotification>(
    project, "notificación",
    filters = listOf(FilterOption("Sin leer", UNREAD), FilterOption("Todas", ALL)),
    scopes = listOf(FilterOption("Este repositorio", THIS_REPO), FilterOption("Todos los repositorios", "")),
    createText = null,
) {
    /** `owner/name` gh resolves here; a pull request can only be reviewed in the IDE when it belongs to it. */
    @Volatile private var currentRepo: String? = null
    @Volatile private var thisRepoOnly = true

    override fun listArgs(filter: String, scope: String, search: String) = GhNotifications.list(scope == THIS_REPO, filter == ALL)

    override fun fetchList(workDir: File, filter: String, scope: String, search: String): List<GhNotification> {
        thisRepoOnly = scope == THIS_REPO
        currentRepo = runCatching { GhCli.run(workDir, GhCommands.repoName()).trim() }.getOrNull()
        val needle = search.trim()
        return super.fetchList(workDir, filter, scope, search).filter { needle.isEmpty() || it.title.contains(needle, true) || it.repository.contains(needle, true) }
    }

    override fun parseList(json: String) = GhNotifications.parseList(json)
    override fun detailArgs(item: GhNotification) = GhNotifications.thread(item.id)
    override fun parseDetail(json: String) = GhNotifications.parse(json)
    override fun id(item: GhNotification) = item.id.toLongOrNull() ?: item.id.hashCode().toLong()
    override fun title(item: GhNotification) = item.title
    override val searchHint get() = "Filtrar por título o repositorio, Enter"

    override fun extraActions(): List<AnAction> = listOf(
        object : DumbAwareAction("Marcar todas como leídas", null, AllIcons.Actions.Checked) {
            override fun actionPerformed(e: AnActionEvent) {
                val where = if (thisRepoOnly) "de este repositorio" else "de todos tus repositorios"
                val answer = Messages.showYesNoDialog(
                    project, "Se marcarán como leídas todas las notificaciones $where.", "Marcar todas como leídas", Messages.getQuestionIcon(),
                )
                if (answer == Messages.YES) runAndReload("Marcando notificaciones", GhNotifications.markAllRead(thisRepoOnly), "Notificaciones marcadas como leídas")
            }
        },
    )

    override fun renderCell(renderer: ColoredListCellRenderer<GhNotification>, item: GhNotification) {
        renderer.icon = when (item.type) {
            "PullRequest" -> AllIcons.Vcs.Branch
            "Issue" -> AllIcons.General.TodoDefault
            "Release" -> AllIcons.Nodes.Tag
            "Discussion" -> AllIcons.General.Balloon
            else -> AllIcons.General.Information
        }
        val style = if (item.unread) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        item.number?.let { renderer.append("#$it ", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        renderer.append(item.title, style)
        renderer.append("  ${item.repository} · ${item.reasonLabel} · ${Html.date(item.updatedAt)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    override fun detailHtml(item: GhNotification): String = buildString {
        append("<h2>${esc(item.title)}</h2>")
        append("<p>${esc(item.type)} en <code>${esc(item.repository)}</code> · ${esc(item.reasonLabel)} · ${Html.date(item.updatedAt)}")
        append(" · ${if (item.unread) "<b>sin leer</b>" else "leída"}</p>")
        append("<p>${Html.link(item.webUrl, item.webUrl)}</p>")
    }

    override fun detailButtons(item: GhNotification): List<JButton> = buildList {
        val number = item.number
        if (item.type == "PullRequest" && number != null && item.repository.equals(currentRepo, ignoreCase = true)) {
            add(button("Revisar cambios") { PrReviewDiff.reopen(project, number) })
        }
        add(button("Abrir en navegador") { BrowserUtil.browse(item.webUrl) })
        if (item.unread) add(button("Marcar leída") { runAndReload("Marcando notificación", GhNotifications.markRead(item.id), "Notificación leída") })
        add(button("Hecho") { runAndReload("Archivando notificación", GhNotifications.done(item.id), "Notificación archivada") })
        add(button("Dejar de seguir") {
            runAndReload("Cancelando suscripción", GhNotifications.unsubscribe(item.id), "Ya no recibirás avisos de «${item.title}»")
        })
    }

    private companion object {
        const val UNREAD = "unread"
        const val ALL = "all"
        const val THIS_REPO = "repo"
    }
}
