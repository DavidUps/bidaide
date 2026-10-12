package com.bidaide.github.projects

import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhException
import com.bidaide.gh.GhProject
import com.bidaide.gh.GhProjectItem
import com.bidaide.gh.GhProjects
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Html.esc
import com.bidaide.github.ui.KanbanBoard
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent

/** Project boards of the repository owner: cards grouped by status, add a card, move a card. */
class ProjectsPanel(project: Project) : GhListDetailPanel<GhProject>(project, "project", filters = emptyList(), createText = null) {
    override fun listArgs(filter: String, scope: String, search: String) = GhProjects.owner()

    override fun fetchList(workDir: File, filter: String, scope: String, search: String): List<GhProject> = scoped {
        val owner = GhCli.run(workDir, GhProjects.owner()).trim()
        val needle = search.trim()
        GhProjects.parseList(GhCli.run(workDir, GhProjects.list(owner)), owner).filter { needle.isEmpty() || it.title.contains(needle, true) }
    }

    override fun fetchDetail(workDir: File, item: GhProject): GhProject = scoped {
        item.copy(
            items = GhProjects.parseItems(GhCli.run(workDir, GhProjects.items(item.number, item.owner))),
            // Without the field the cards are still shown, in the order their statuses appear; they just cannot be dragged.
            statusField = runCatching { GhProjects.parseStatusField(GhCli.run(workDir, GhProjects.fields(item.number, item.owner))) }.getOrNull(),
        )
    }

    // --- The board: one column per status, cards dragged between them ---

    private var shown: GhProject? = null

    private val board by lazy {
        KanbanBoard(
            onSelect = {},
            onOpen = { card -> (card.payload as? GhProjectItem)?.url?.let(BrowserUtil::browse) },
            onMove = { card, _, to -> dropped(card.payload as? GhProjectItem, to) },
        )
    }

    override fun detailComponent(item: GhProject): JComponent {
        shown = item
        board.show(columns(item), "Este proyecto no tiene tarjetas")
        return board
    }

    private fun columns(project: GhProject): List<KanbanBoard.Column> {
        val items = project.items.orEmpty()
        val statuses = (project.statusField?.options?.map { it.name }.orEmpty() + items.map { it.status }).distinct()
            // "No status" goes first, as on GitHub, and only when something is in it.
            .sortedBy { if (it == GhProjects.NO_STATUS) 0 else 1 }
            .filter { it != GhProjects.NO_STATUS || items.any { item -> item.status == it } }
        return statuses.map { status ->
            KanbanBoard.Column(status, status, items.filter { it.status == status }.map { item ->
                val kind = when (item.type) {
                    "PullRequest" -> "PR"
                    "Issue" -> "issue"
                    else -> "borrador"
                }
                KanbanBoard.Card(item.id, item.toString(), listOfNotNull(kind, item.repository).joinToString(" · "), item)
            })
        }
    }

    private fun dropped(card: GhProjectItem?, status: String) {
        val project = shown ?: return
        val field = project.statusField
        val option = field?.options?.firstOrNull { it.name == status }
        if (card == null || field == null || option == null) {
            Notifier.warn(this.project, "GitHub", "No se puede mover a «$status»: no es una opción del campo de estado del proyecto.")
            return
        }
        runAndReload("Moviendo tarjeta", GhProjects.moveItem(project.id, card.id, field.id, option.id), "«${card.title.take(60)}» movida a ${option.name}")
    }

    /** `gh project` fails with a cryptic message when the token lacks the scope; say what to do instead. */
    private fun <R> scoped(work: () -> R): R = try {
        work()
    } catch (e: GhException) {
        throw if (GhProjects.isScopeError(e.message)) GhException(GhProjects.SCOPE_HINT) else e
    }

    override fun parseList(json: String): List<GhProject> = emptyList()
    override fun detailArgs(item: GhProject) = GhProjects.items(item.number, item.owner)
    override fun parseDetail(json: String): GhProject = error("fetchDetail is overridden")
    override fun id(item: GhProject) = item.number.toLong()
    override fun title(item: GhProject) = item.title
    override val searchHint get() = "Filtrar por título, Enter"

    override fun renderCell(renderer: ColoredListCellRenderer<GhProject>, item: GhProject) {
        renderer.icon = AllIcons.Nodes.DataTables
        renderer.append("#${item.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        renderer.append(item.title)
        renderer.append("  ${item.owner} · ${item.itemCount} tarjetas${if (item.closed) " · cerrado" else ""}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    override fun detailHtml(item: GhProject): String = buildString {
        append("<h2>${esc(item.title)} <font color=\"gray\">#${item.number}</font></h2>")
        append("<p>${esc(item.owner)} · ${Html.link(item.url, "GitHub")}</p>")
        if (item.description.isNotBlank()) append("<p>${esc(item.description)}</p>")
        val items = item.items.orEmpty()
        if (items.isEmpty()) append("<p><i>Sin tarjetas.</i></p>")
        for ((status, cards) in items.groupBy { it.status }) {
            append("<h3>${esc(status)} <font color=\"gray\">${cards.size}</font></h3>")
            for (card in cards) {
                val label = (card.number?.let { "#$it " }.orEmpty()) + card.title
                val kind = when (card.type) {
                    "PullRequest" -> "PR"
                    "Issue" -> "issue"
                    else -> "borrador"
                }
                append("<div>• ${Html.link(card.url, label)} <small><font color=\"gray\">$kind${card.repository?.let { " · ${esc(it)}" }.orEmpty()}</font></small></div>")
            }
        }
    }

    override fun detailButtons(item: GhProject): List<JButton> = buildList {
        add(button("Añadir issue o PR...") { addItem(item) })
        if (!item.items.isNullOrEmpty()) add(button("Mover tarjeta...") { moveItem(item) })
        add(button("Abrir en navegador") { BrowserUtil.browse(item.url) })
    }

    private fun addItem(board: GhProject) {
        val input = Messages.showInputDialog(
            project, "Número de issue o PR de este repositorio, o URL completa de otro.", "Añadir a «${board.title}»", null,
        )?.trim()?.removePrefix("#") ?: return
        if (input.isEmpty()) return
        GhCli.async(
            project, "Añadiendo tarjeta",
            work = {
                val workDir = GhRepoContext.requireWorkDir(project)
                val url = input.toIntOrNull()?.let { GhCli.run(workDir, GhProjects.itemUrl(it)).trim() } ?: input
                scoped { GhCli.run(workDir, GhProjects.addItem(board.number, board.owner, url)) }
            },
        ) {
            Notifier.info(project, "GitHub", "Tarjeta añadida a «${board.title}»")
            reload()
        }
    }

    private fun moveItem(board: GhProject) {
        choose("Tarjeta a mover", board.items.orEmpty()) { card ->
            GhCli.async(
                project, "Cargando columnas",
                work = { scoped { GhProjects.parseStatusField(GhCli.run(GhRepoContext.requireWorkDir(project), GhProjects.fields(board.number, board.owner))) } },
            ) { field ->
                if (field == null) {
                    Notifier.warn(project, "GitHub", "«${board.title}» no tiene ningún campo de selección por el que mover tarjetas.")
                    return@async
                }
                choose("${field.name} de «${card.title.take(40)}»", field.options) { option ->
                    runAndReload(
                        "Moviendo tarjeta", GhProjects.moveItem(board.id, card.id, field.id, option.id),
                        "«${card.title.take(60)}» movida a ${option.name}",
                    )
                }
            }
        }
    }

    private fun <E : Any> choose(title: String, items: List<E>, onChosen: (E) -> Unit) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(title)
            .setNamerForFiltering { it.toString() }
            .setItemChosenCallback(onChosen)
            .createPopup()
            .showInCenterOf(this)
    }
}
