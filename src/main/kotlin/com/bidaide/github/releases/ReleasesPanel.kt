package com.bidaide.github.releases

import com.bidaide.Notifier
import com.bidaide.adb.AdbFiles
import com.bidaide.adb.ApkInstaller
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhRelease
import com.bidaide.gh.GhReleases
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.display
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Html.esc
import com.bidaide.github.ui.Markdown
import com.bidaide.github.ui.TitleBodyDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import java.io.File
import javax.swing.JButton

class ReleasesPanel(project: Project) : GhListDetailPanel<GhRelease>(project, "release", filters = emptyList(), createText = "Nueva release...") {
    override fun listArgs(filter: String, scope: String, search: String) = GhReleases.list()

    /** Releases have no server-side search here, so the box filters the list by tag or title. */
    override fun fetchList(workDir: File, filter: String, scope: String, search: String): List<GhRelease> {
        val all = GhReleases.parseList(GhCli.run(workDir, GhReleases.list()))
        val needle = search.trim()
        return if (needle.isEmpty()) all else all.filter { it.tagName.contains(needle, true) || it.title.contains(needle, true) }
    }

    override fun parseList(json: String) = GhReleases.parseList(json)
    override fun detailArgs(item: GhRelease) = GhReleases.view(item.tagName)
    override fun parseDetail(json: String) = GhReleases.parse(json)
    override fun id(item: GhRelease) = item.tagName.hashCode().toLong()
    override fun title(item: GhRelease) = item.tagName
    override val searchHint get() = "Filtrar por tag o título, Enter"

    override fun renderCell(renderer: ColoredListCellRenderer<GhRelease>, item: GhRelease) {
        renderer.icon = if (item.isDraft) AllIcons.Actions.Edit else AllIcons.Nodes.Tag
        renderer.append(item.title)
        val tags = listOfNotNull(
            item.tagName.takeIf { it != item.title }, "borrador".takeIf { item.isDraft }, "pre-release".takeIf { item.isPrerelease },
            "última".takeIf { item.isLatest }, Html.date(item.publishedAt ?: item.createdAt).take(10),
        )
        renderer.append("  ${tags.joinToString(" · ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    override fun detailHtml(item: GhRelease): String = buildString {
        append("<h2>${esc(item.title)} <font color=\"gray\">${esc(item.tagName)}</font></h2>")
        val meta = listOfNotNull(
            "BORRADOR".takeIf { item.isDraft }, "PRE-RELEASE".takeIf { item.isPrerelease },
            "<b>${esc(item.author.display())}</b>", Html.date(item.publishedAt ?: item.createdAt),
            item.targetCommitish?.takeIf { it.isNotBlank() }?.let { "<code>${esc(it)}</code>" }, Html.link(item.url, "GitHub"),
        )
        append("<p>${meta.joinToString(" · ")}</p>")
        append(Markdown.toHtml(item.body).ifEmpty { "<p><i>Sin notas.</i></p>" })
        append(Html.section("Archivos", item.assets.orEmpty().joinToString("") {
            "<div><code>${esc(it.name)}</code> ${StringUtil.formatFileSize(it.size)} · ${it.downloadCount} descargas</div>"
        }))
    }

    override fun detailButtons(item: GhRelease): List<JButton> = buildList {
        val tag = item.tagName
        if (!item.assets.isNullOrEmpty()) add(button("Descargar archivos") { download(item) })
        if (item.isDraft) add(button("Publicar") { runAndReload("Publicando $tag", GhReleases.publish(tag), "Release $tag publicada") })
        add(button("Borrar") {
            val answer = Messages.showYesNoDialog(
                project, "Se borrará la release «${item.title}». El tag $tag de git se conserva.", "Borrar release", "Borrar", "Cancelar",
                Messages.getWarningIcon(),
            )
            if (answer == Messages.YES) runAndReload("Borrando release $tag", GhReleases.delete(tag), "Release $tag borrada")
        })
        item.url?.let { url -> add(button("Abrir en navegador") { BrowserUtil.browse(url) }) }
    }

    private fun download(release: GhRelease) {
        GhCli.async(
            project, "Descargando archivos de ${release.tagName}",
            work = {
                val dir = File(AdbFiles.captureDir(), "bidaide-releases/${release.tagName.replace(Regex("[^A-Za-z0-9._-]+"), "_")}").apply { mkdirs() }
                GhCli.run(GhRepoContext.requireWorkDir(project), GhReleases.download(release.tagName, dir), GhCli.LONG_TIMEOUT_MILLIS)
                dir
            },
        ) { dir -> ApkInstaller.offer(project, dir, "Release ${release.tagName}") }
    }

    override fun create() {
        val tagField = JBTextField(20)
        val targetField = JBTextField(20)
        val generateBox = JBCheckBox("Generar notas automáticamente desde las PR fusionadas", true)
        val draftBox = JBCheckBox("Borrador")
        val prereleaseBox = JBCheckBox("Pre-release")
        val dialog = object : TitleBodyDialog(project, "Nueva release", "Crear", extraRows = {
            row("Tag:") { cell(tagField).comment("Se crea si no existe, p. ej. v1.2.0.") }
            row("Desde:") { cell(targetField).align(AlignX.FILL).comment("Rama o commit del tag nuevo. Vacío = rama por defecto.") }
            row { cell(generateBox) }
            row {
                cell(draftBox)
                cell(prereleaseBox)
            }
        }) {
            // The title is optional for releases: GitHub falls back to the tag.
            override fun doValidate() = if (tagField.text.isBlank()) com.intellij.openapi.ui.ValidationInfo("El tag es obligatorio", tagField) else null
        }.apply { build() }
        if (!dialog.showAndGet()) return
        val args = GhReleases.create(
            tagField.text.trim(), dialog.itemTitle, dialog.body, generateBox.isSelected, draftBox.isSelected, prereleaseBox.isSelected, targetField.text,
        )
        GhCli.async(project, "Creando release", work = { GhCli.run(GhRepoContext.requireWorkDir(project), args).trim() }) { output ->
            val url = output.lines().lastOrNull().orEmpty()
            Notifier.info(project, "Release creada", Html.link(url, url))
            reload()
        }
    }
}
