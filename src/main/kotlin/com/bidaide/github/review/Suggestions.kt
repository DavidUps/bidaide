package com.bidaide.github.review

import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhException
import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.GhReviewThread
import com.bidaide.Notifier
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import java.util.Base64

/** GitHub "suggested changes": ```suggestion blocks inside review comments. */
object Suggestions {
    private val BLOCK = Regex("```suggestion[^\\n]*\\r?\\n(.*?)\\r?\\n?```", RegexOption.DOT_MATCHES_ALL)

    /** Replacement text of the first suggestion block in [body]; empty string means "delete these lines". */
    fun extract(body: String): String? = BLOCK.find(body)?.groupValues?.get(1)

    /** Replaces 1-based lines [startLine]..[endLine] of [content] with [replacement], keeping the file's line endings. */
    fun replaceLines(content: String, startLine: Int, endLine: Int, replacement: String): String {
        val separator = if (content.contains("\r\n")) "\r\n" else "\n"
        val lines = content.split(separator).toMutableList()
        require(startLine in 1..endLine && endLine <= lines.size) { "Líneas $startLine-$endLine fuera del archivo" }
        val newLines = if (replacement.isEmpty()) emptyList() else replacement.replace("\r\n", "\n").split("\n")
        return (lines.subList(0, startLine - 1) + newLines + lines.subList(endLine, lines.size)).joinToString(separator)
    }

    /** Commits the suggestion to the PR branch through the GitHub contents API, after asking for confirmation. */
    fun apply(session: PrReviewSession, thread: GhReviewThread, replacement: String) {
        val endLine = thread.line ?: return
        val startLine = thread.startLine ?: endLine
        val repository = session.state.headRepository
        val branch = session.state.headRefName
        if (repository == null) {
            Notifier.warn(session.project, "PR #${session.number}", "El repositorio de origen de la PR ya no existe.")
            return
        }
        val answer = Messages.showYesNoDialog(
            session.project,
            "Se creará un commit en la rama «$branch» de $repository que sustituye " +
                (if (startLine == endLine) "la línea $endLine" else "las líneas $startLine–$endLine") + " de ${thread.path}.",
            "Aplicar sugerencia", "Crear Commit", "Cancelar", Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        session.mutate("Aplicando sugerencia", onDone = { ok ->
            if (ok) {
                Notifier.info(
                    session.project, "Sugerencia aplicada",
                    "Commit creado en $branch. Vuelve a abrir la revisión para ver la PR actualizada.",
                )
            }
        }) {
            val endpoint = "repos/$repository/contents/${GhCommands.encodePath(thread.path)}"
            val current = JsonParser.parseString(GhCli.run(session.workDir, listOf("api", "$endpoint?ref=$branch"))).asJsonObject
            val tip = String(Base64.getMimeDecoder().decode(current.get("content").asString), Charsets.UTF_8)
            val reviewed = GhCli.run(session.workDir, GhCommands.fileContent(thread.path, session.headSha))
            // Line numbers come from the commit under review; refuse if the branch has moved on for this file.
            if (tip != reviewed) throw GhException("${thread.path} ha cambiado en $branch desde que abriste la revisión. Ábrela de nuevo.")

            val updated = replaceLines(tip, startLine, endLine, replacement)
            val payload = mapOf(
                "message" to "Apply suggestion from code review",
                "content" to Base64.getEncoder().encodeToString(updated.toByteArray(Charsets.UTF_8)),
                "sha" to current.get("sha").asString,
                "branch" to branch,
            )
            val input = FileUtil.createTempFile("bidaide-suggestion", ".json", true)
            try {
                input.writeText(Gson().toJson(payload))
                GhCli.run(session.workDir, listOf("api", "--method", "PUT", endpoint, "--input", input.path))
            } finally {
                FileUtil.delete(input)
            }
            if (thread.canResolve) GhCli.run(session.workDir, GhReviewCommands.setResolved(thread.id, true))
        }
    }
}
