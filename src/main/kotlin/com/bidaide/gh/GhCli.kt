package com.bidaide.gh

import com.bidaide.Notifier
import com.bidaide.settings.BidaideConfigurable
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

open class GhException(message: String) : Exception(message)
class GhNotInstalledException : GhException("GitHub CLI (gh) no está instalado o no se encuentra.")
class GhNotAuthenticatedException(details: String) : GhException(details)

/** Runs the GitHub CLI. Authentication is whatever `gh auth login` left on this machine; no tokens are handled here. */
object GhCli {
    private val LOG = logger<GhCli>()
    private const val TIMEOUT_MILLIS = 120_000

    /** For downloads and clones, which can take much longer than an API call. */
    const val LONG_TIMEOUT_MILLIS = 15 * 60 * 1000

    /** Blocking; never call from the EDT. Returns stdout, throws [GhException] on a non-zero exit. */
    fun run(workDir: File?, args: List<String>, timeoutMillis: Int = TIMEOUT_MILLIS): String {
        val gh = GhExecutable.find() ?: throw GhNotInstalledException()
        val commandLine = GeneralCommandLine(listOf(gh) + args)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(
                mapOf(
                    "GH_PROMPT_DISABLED" to "1",
                    "GH_NO_UPDATE_NOTIFIER" to "1",
                    "NO_COLOR" to "1",
                    "CLICOLOR" to "0",
                    "GH_PAGER" to "cat",
                )
            )
        workDir?.let(commandLine::withWorkDirectory)
        LOG.debug("gh " + args.joinToString(" "))
        val output = CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
        if (output.isTimeout) throw GhException("gh ${args.firstOrNull().orEmpty()} agotó el tiempo de espera")
        if (output.exitCode != 0) {
            val message = output.stderr.trim().ifEmpty { output.stdout.trim() }.ifEmpty { "gh terminó con código ${output.exitCode}" }
            throw if (isAuthError(message)) GhNotAuthenticatedException(message) else GhException(message)
        }
        return output.stdout
    }

    internal fun isAuthError(message: String): Boolean =
        message.contains("gh auth login") || message.contains("not logged into any", ignoreCase = true) ||
            message.contains("authentication required", ignoreCase = true)

    /**
     * Runs [work] off the EDT and delivers the result to [onSuccess] on the EDT. Failures are shown as notifications,
     * then [onFailure] runs on the EDT.
     */
    fun <T> async(
        project: Project,
        title: String,
        onFailure: () -> Unit = {},
        work: () -> T,
        onSuccess: (T) -> Unit,
    ) {
        object : Task.Backgroundable(project, title, false) {
            private var result: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                result = runCatching(work)
            }

            override fun onFinished() {
                val outcome = result ?: return
                outcome.onSuccess(onSuccess).onFailure {
                    report(project, title, it)
                    onFailure()
                }
            }
        }.queue()
    }

    fun report(project: Project, title: String, error: Throwable) {
        when (error) {
            is GhNotInstalledException -> Notifier.error(
                project, "GitHub CLI no encontrado",
                "Instálalo con <code>brew install gh</code> y después ejecuta <code>gh auth login</code>.",
                NotificationAction.createSimple("Configurar ruta...") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, BidaideConfigurable::class.java)
                },
            )
            is GhNotAuthenticatedException -> Notifier.error(
                project, "GitHub CLI sin sesión",
                "Ejecuta <code>gh auth login</code> en una terminal.<br>${error.message.orEmpty()}",
            )
            is GhException -> Notifier.error(project, title, error.message.orEmpty())
            else -> {
                LOG.warn(error)
                Notifier.error(project, title, error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
