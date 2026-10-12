package com.bidaide.github.discussions

import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhDiscussion
import com.bidaide.gh.GhDiscussionComment
import com.bidaide.gh.GhDiscussions
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Html.esc
import com.bidaide.github.ui.Markdown
import com.bidaide.github.ui.TextPromptDialog
import com.bidaide.github.ui.TitleBodyDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.io.File
import javax.swing.JButton

class DiscussionsPanel(project: Project) : GhListDetailPanel<GhDiscussion>(project, "discussion", filters = emptyList(), createText = "Nueva discussion...") {
    override fun listArgs(filter: String, scope: String, search: String) = GhDiscussions.list()

    override fun fetchList(workDir: File, filter: String, scope: String, search: String): List<GhDiscussion> {
        val needle = search.trim()
        return super.fetchList(workDir, filter, scope, search)
            .filter { needle.isEmpty() || it.title.contains(needle, true) || it.category.contains(needle, true) }
    }

    override fun parseList(json: String) = GhDiscussions.parseList(json)
    override fun detailArgs(item: GhDiscussion) = GhDiscussions.view(item.number)
    override fun parseDetail(json: String) = GhDiscussions.parse(json)
    override fun id(item: GhDiscussion) = item.number.toLong()
    override val searchHint get() = "Filtrar por título o categoría, Enter"

    override fun renderCell(renderer: ColoredListCellRenderer<GhDiscussion>, item: GhDiscussion) {
        renderer.icon = if (item.answered) AllIcons.Actions.Checked else AllIcons.General.Balloon
        renderer.append("#${item.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        renderer.append(item.title)
        renderer.append("  ${item.category} · ${item.author} · 💬 ${item.commentCount}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    override fun detailHtml(item: GhDiscussion): String = buildString {
        append("<h2>${esc(item.title)} <font color=\"gray\">#${item.number}</font></h2>")
        append("<p>${esc(item.category)} · <b>${esc(item.author)}</b> · ${Html.date(item.updatedAt)}")
        if (item.answered) append(" · <font color=\"green\">respondida</font>")
        append(" · ${Html.link(item.url, "GitHub")}</p>")
        append(Markdown.toHtml(item.body).ifEmpty { "<p><i>Sin texto.</i></p>" })
        append(Html.section("Comentarios", item.comments.joinToString("") { comment(it, nested = false) }))
    }

    private fun comment(comment: GhDiscussionComment, nested: Boolean): String = buildString {
        val answer = if (comment.isAnswer) " <font color=\"green\">✓ respuesta</font>" else ""
        val content = "<p><b>${esc(comment.author)}</b> <small>${Html.date(comment.createdAt)}</small>$answer</p>${Markdown.toHtml(comment.body)}"
        append(if (nested) "<blockquote>$content</blockquote>" else content)
        comment.replies.forEach { append(comment(it, nested = true)) }
    }

    override fun detailButtons(item: GhDiscussion): List<JButton> = listOf(
        button("Comentar") {
            val dialog = TextPromptDialog(project, "Comentar discussion #${item.number}", "Comentar", required = true)
            if (dialog.showAndGet()) {
                runAndReload("Comentando discussion #${item.number}", GhDiscussions.comment(item.id, dialog.text), "Comentario añadido a #${item.number}")
            }
        },
        button("Abrir en navegador") { BrowserUtil.browse(item.url) },
    )

    override fun create() {
        GhCli.async(
            project, "Cargando categorías",
            work = { GhDiscussions.parseSetup(GhCli.run(GhRepoContext.requireWorkDir(project), GhDiscussions.setup())) },
        ) { setup ->
            if (!setup.enabled || setup.categories.isEmpty()) {
                Notifier.warn(project, "GitHub", "Este repositorio no tiene Discussions activadas (Settings → Features en github.com).")
                return@async
            }
            val categoryCombo = ComboBox(setup.categories.toTypedArray())
            val dialog = TitleBodyDialog(project, "Nueva discussion", "Crear", extraRows = { row("Categoría:") { cell(categoryCombo) } }).apply { build() }
            if (!dialog.showAndGet()) return@async
            val args = GhDiscussions.create(setup.repositoryId, categoryCombo.item.id, dialog.itemTitle, dialog.body)
            GhCli.async(
                project, "Creando discussion",
                work = { GhDiscussions.parseCreatedUrl(GhCli.run(GhRepoContext.requireWorkDir(project), args)) },
            ) { url ->
                Notifier.info(project, "Discussion creada", Html.link(url, url))
                reload()
            }
        }
    }
}
