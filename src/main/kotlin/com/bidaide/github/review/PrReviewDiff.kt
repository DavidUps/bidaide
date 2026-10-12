package com.bidaide.github.review

import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhException
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder

object PrReviewDiff {
    const val ADD_COMMENT_ACTION_ID = "Bidaide.Pr.AddComment"

    /** Run by the diff extension when the request reaches the screen, whichever way the user navigated to it. */
    val ON_SHOWN: Key<Runnable> = Key.create("Bidaide.PrReview.onShown")

    /** Opens the review window for [pr]: file tree, threads and the IDE diff viewer with inline comments. */
    fun open(project: Project, pr: GhPullRequest) {
        val base = pr.baseRefOid
        val head = pr.headRefOid
        if (base.isNullOrBlank() || head.isNullOrBlank()) {
            Notifier.warn(project, "GitHub", "No se pudieron leer los commits de la PR #${pr.number}.")
            return
        }
        GhCli.async(
            project, "Preparando revisión de PR #${pr.number}",
            work = { PrReviewSession.load(project, GhRepoContext.requireWorkDir(project), pr, base, head) },
        ) { session ->
            if (session.files.isEmpty()) {
                Notifier.info(project, "GitHub", "La PR #${pr.number} no tiene archivos cambiados.")
            } else {
                PrReviewWindow(session).show()
            }
        }
    }

    /** Re-reads the PR (its head may have moved) and opens a fresh review window. */
    fun reopen(project: Project, number: Int) {
        GhCli.async(
            project, "Actualizando PR #$number",
            work = { GhJson.pullRequest(GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.prView(number))) },
        ) { open(project, it) }
    }
}

/**
 * Fetches both sides of one file lazily, when the user reaches it. [baseSha] → [headSha] is the whole pull request
 * or a single commit of it; inline comments are only [interactive] for the whole pull request, the one diff
 * GitHub's line numbers refer to.
 */
class PrFileDiffProducer(
    private val review: PrFileReview,
    private val baseSha: String?,
    private val headSha: String,
    private val interactive: Boolean,
    private val onShown: Runnable,
) : DiffRequestProducer {
    private val session = review.session
    private val file = review.file

    override fun getName(): String = file.filename

    override fun getContentType(): FileType = FileTypeManager.getInstance().getFileTypeByFileName(file.filename)

    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
        val type = contentType
        if (type.isBinary) return MessageDiffRequest("${file.filename}: archivo binario, sin vista de diferencias.")
        try {
            val left = if (file.status == "added" || baseSha == null) empty() else content(file.previousFilename ?: file.filename, baseSha, type)
            indicator.checkCanceled()
            val right = if (file.status == "removed") empty() else content(file.filename, headSha, type)
            val request = SimpleDiffRequest(
                "PR #${session.number} — ${file.filename}", left, right,
                if (interactive) "Base (${baseSha?.take(7)})" else "Antes (${baseSha?.take(7) ?: "vacío"})",
                if (interactive) "PR #${session.number} (${headSha.take(7)})" else "Commit ${headSha.take(7)}",
            )
            request.putUserData(PrReviewDiff.ON_SHOWN, onShown)
            if (interactive) {
                request.putUserData(PrFileReview.KEY, review)
                val addComment = ActionManager.getInstance().getAction(PrReviewDiff.ADD_COMMENT_ACTION_ID)
                request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOfNotNull(ViewedFileAction(review), addComment))
            }
            return request
        } catch (e: GhException) {
            throw DiffRequestProducerException(e.message.orEmpty(), e)
        }
    }

    private fun empty(): DiffContent = DiffContentFactory.getInstance().createEmpty()

    private fun content(path: String, ref: String, type: FileType): DiffContent =
        DiffContentFactory.getInstance().create(session.project, GhCli.run(session.workDir, GhCommands.fileContent(path, ref)), type)
}

/** The "Viewed" checkbox of github.com, in the diff toolbar. */
private class ViewedFileAction(private val review: PrFileReview) : CheckboxAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = "Visto (${review.session.viewedCount}/${review.session.files.size})"
        e.presentation.description = "Marca este archivo como visto en la pull request"
    }

    override fun isSelected(e: AnActionEvent): Boolean = review.session.isViewed(review.file.filename)

    override fun setSelected(e: AnActionEvent, state: Boolean) = review.session.setViewed(review.file.filename, state)
}
