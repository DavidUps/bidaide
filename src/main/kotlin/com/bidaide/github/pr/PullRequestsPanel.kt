package com.bidaide.github.pr

import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhCommands.MergeMethod
import com.bidaide.gh.GhCommands.ReviewKind
import com.bidaide.gh.GhCommands.StateFilter
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhMergeRules
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GhTimeline
import com.bidaide.gh.GhUserResolver
import com.bidaide.gh.display
import com.bidaide.github.review.PrReviewDiff
import com.bidaide.github.runs.CiArtifacts
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.MetadataEditor
import com.bidaide.github.ui.TextPromptDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent

class PullRequestsPanel(project: Project) : GhListDetailPanel<GhPullRequest>(
    project, "pull request",
    filters = StateFilter.entries.map { FilterOption(it.label, it.name) },
    scopes = GhCommands.PR_SCOPES.map { FilterOption(it.first, it.second) },
) {
    private val userResolver = GhUserResolver()

    override fun listArgs(filter: String, scope: String, search: String) =
        GhCommands.prList(StateFilter.valueOf(filter), GhCommands.combineSearch(scope, search))

    override fun parseList(json: String) = GhJson.pullRequests(json)
    override fun detailArgs(item: GhPullRequest) = GhCommands.prView(item.number)
    override fun parseDetail(json: String) = GhJson.pullRequest(json)
    override fun id(item: GhPullRequest) = item.number.toLong()

    override fun fetchDetail(workDir: File, item: GhPullRequest): GhPullRequest {
        val pr = userResolver.resolvePullRequestUsers(workDir, super.fetchDetail(workDir, item))
        // The feed is a nicety: without it reviews, comments and commits are listed separately.
        return pr.copy(
            timeline = runCatching { GhTimeline.parse(GhCli.run(workDir, GhTimeline.args(pr.number))) }.getOrNull(),
            mergeStatus = if (pr.isOpen) runCatching { GhMergeRules.load(workDir, pr.number) }.getOrNull() else null,
        )
    }

    override fun renderCell(renderer: ColoredListCellRenderer<GhPullRequest>, item: GhPullRequest) {
        renderer.icon = when {
            item.state.equals("MERGED", true) -> AllIcons.Vcs.Merge
            !item.isOpen -> AllIcons.Actions.Cancel
            item.isDraft -> AllIcons.Actions.Edit
            else -> AllIcons.Vcs.Branch
        }
        renderer.append("#${item.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        renderer.append(item.title)
        renderer.append("  ${item.author.display()} · ${item.headRefName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    override fun detailHtml(item: GhPullRequest): String = PullRequestHtml.render(item)
    override fun detailComponent(item: GhPullRequest): JComponent = PullRequestPreviewComponent(item)

    override fun detailButtons(item: GhPullRequest): List<JButton> = buildList {
        val n = item.number
        add(button("Revisar cambios") { PrReviewDiff.open(project, item) })
        add(button("Checkout") { PullRequestActions.checkout(project, item) })
        add(button("Probar en dispositivo") { PullRequestActions.checkoutAndRun(project, item) })
        add(button("APK del CI") { CiArtifacts.forBranch(project, item.headRefName) })
        if (item.isOpen) {
            add(button("Aprobar") { review(item, ReviewKind.APPROVE) })
            add(button("Pedir cambios") { review(item, ReviewKind.REQUEST_CHANGES) })
            add(button("Comentar") { comment(item) })
            if (item.isDraft) {
                add(button("Marcar lista") { runAndReload("Marcando PR #$n como lista", GhCommands.prReady(n), "PR #$n lista para revisión") })
            }
            add(button("Actualizar rama") { updateBranch(item) })
            add(button("Merge...") { merge(item) })
            if (item.autoMergeRequest != null) {
                add(button("Quitar auto-merge") {
                    runAndReload("Quitando auto-merge de PR #$n", GhCommands.prDisableAutoMerge(n), "Auto-merge desactivado en PR #$n")
                })
            }
            add(button("Editar...") { editMetadata(item) })
            add(button("Cerrar") { runAndReload("Cerrando PR #$n", GhCommands.prSetOpen(n, false), "PR #$n cerrada") })
        } else if (item.state.equals("CLOSED", true)) {
            add(button("Reabrir") { runAndReload("Reabriendo PR #$n", GhCommands.prSetOpen(n, true), "PR #$n reabierta") })
        }
        add(button("Abrir en navegador") { BrowserUtil.browse(item.url) })
    }

    override fun create() {
        val repository = GhRepoContext.repository(project)
        val branch = repository?.currentBranchName
        if (repository == null || branch == null) {
            Notifier.warn(project, "GitHub", "No hay rama actual desde la que crear la pull request.")
            return
        }
        GhCli.async(
            project, "Preparando pull request",
            work = {
                val base = GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.defaultBranch()).trim()
                base to PullRequestGit.draft(project, repository, branch, base)
            },
        ) { (defaultBranch, draft) ->
            val dialog = CreatePullRequestDialog(project, branch, defaultBranch, draft)
            if (!dialog.showAndGet()) return@async
            GhCli.async(
                project, "Creando pull request",
                work = {
                    if (dialog.pushFirst) PullRequestGit.pushCurrentBranch(project, repository, branch)
                    val args = GhCommands.prCreate(
                        dialog.itemTitle, dialog.body, dialog.base, dialog.draft, dialog.reviewers, dialog.assignees, dialog.labels,
                    )
                    GhCli.run(GhRepoContext.requireWorkDir(project), args).trim()
                },
            ) { output ->
                val url = output.lines().lastOrNull().orEmpty()
                Notifier.info(project, "Pull request creada", Html.link(url, url))
                reload()
            }
        }
    }

    private fun review(pr: GhPullRequest, kind: ReviewKind) {
        // GitHub rejects "request changes" and "comment" reviews without a body.
        val dialog = TextPromptDialog(project, "${kind.label} — PR #${pr.number}", kind.label, required = kind != ReviewKind.APPROVE)
        if (!dialog.showAndGet()) return
        runAndReload("Enviando review de PR #${pr.number}", GhCommands.prReview(pr.number, kind, dialog.text), "Review enviada a PR #${pr.number}")
    }

    private fun comment(pr: GhPullRequest) {
        val dialog = TextPromptDialog(project, "Comentar PR #${pr.number}", "Comentar", required = true)
        if (!dialog.showAndGet()) return
        runAndReload("Comentando PR #${pr.number}", GhCommands.prComment(pr.number, dialog.text), "Comentario añadido a PR #${pr.number}")
    }

    private fun merge(pr: GhPullRequest) {
        val dialog = MergeDialog(project, pr)
        if (!dialog.showAndGet()) return
        runAndReload(
            if (dialog.auto) "Activando auto-merge en PR #${pr.number}" else "Fusionando PR #${pr.number}",
            GhCommands.prMerge(pr.number, dialog.method, dialog.deleteBranch, dialog.auto),
            if (dialog.auto) "PR #${pr.number} se fusionará sola cuando pasen los checks" else "PR #${pr.number} fusionada",
        )
    }

    private fun updateBranch(pr: GhPullRequest) {
        val options = arrayOf("Merge", "Rebase", "Cancelar")
        val choice = Messages.showDialog(
            project, "Actualizar «${pr.headRefName}» con los últimos cambios de «${pr.baseRefName}».",
            "Actualizar rama de PR #${pr.number}", options, 0, Messages.getQuestionIcon(),
        )
        if (choice != 0 && choice != 1) return
        runAndReload("Actualizando rama de PR #${pr.number}", GhCommands.prUpdateBranch(pr.number, rebase = choice == 1), "Rama de PR #${pr.number} actualizada")
    }

    private fun editMetadata(pr: GhPullRequest) {
        MetadataEditor.edit(project, "pr", pr.number, pr.labels, pr.assignees, pr.milestone?.title) { command ->
            runAndReload("Editando PR #${pr.number}", command, "PR #${pr.number} actualizada")
        }
    }
}

private class MergeDialog(project: Project, private val pr: GhPullRequest) : DialogWrapper(project) {
    private val methodCombo = ComboBox(MergeMethod.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val deleteBranchBox = JBCheckBox("Borrar la rama tras fusionar")
    private val autoBox = JBCheckBox("Auto-merge: fusionar cuando pasen checks y reviews obligatorias")

    val method: MergeMethod get() = methodCombo.item
    val deleteBranch: Boolean get() = deleteBranchBox.isSelected
    val auto: Boolean get() = autoBox.isSelected

    init {
        title = "Merge PR #${pr.number}"
        setOKButtonText("Merge")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("${pr.headRefName} → ${pr.baseRefName}") }
        row("Método:") { cell(methodCombo) }
        row { cell(deleteBranchBox) }
        row { cell(autoBox) }
    }
}
