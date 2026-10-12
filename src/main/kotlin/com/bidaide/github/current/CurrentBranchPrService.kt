package com.bidaide.github.current

import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.review.PrReviewSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Tracks the pull request of the checked-out branch, for the status bar widget and the in-editor comment markers.
 * Refreshes when the repository changes (checkout, commit, fetch) and every few minutes.
 */
@Service(Service.Level.PROJECT)
class CurrentBranchPrService(private val project: Project) : Disposable {

    fun interface Listener {
        /** Called on the EDT when the PR, its checks or its review threads may have changed. */
        fun currentPrChanged()
    }

    @Volatile var pullRequest: GhPullRequest? = null
        private set

    /** Review data of [pullRequest]; null when there is no open PR for the branch. */
    @Volatile var session: PrReviewSession? = null
        private set

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var sessionLifetime: Disposable? = null

    init {
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh(REPO_CHANGE_DELAY) })
    }

    /** Schedules a refresh; calls made while one is pending collapse into it. */
    fun refresh(delayMillis: Int = 0) {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ reload() }, delayMillis)
    }

    private fun reload() {
        try {
            val workDir = GhRepoContext.workDir(project)
            // Any failure here (no gh, no session, no PR for the branch) just means "nothing to show".
            val pr = workDir?.let { runCatching { GhJson.pullRequest(GhCli.run(it, GhCommands.prForCurrentBranch())) }.getOrNull() }
            val base = pr?.baseRefOid
            val head = pr?.headRefOid
            val current = session
            val next = when {
                workDir == null || pr == null || !pr.isOpen || base == null || head == null -> null
                current != null && current.number == pr.number && current.headSha == head -> current.also { it.reloadBlocking() }
                else -> PrReviewSession.load(project, workDir, pr, base, head)
            }
            pullRequest = pr
            ApplicationManager.getApplication().invokeLater({ publish(next) }, project.disposed)
        } catch (e: Exception) {
            thisLogger().info("Could not refresh the pull request of the current branch", e)
        } finally {
            if (!project.isDisposed) alarm.addRequest({ reload() }, PERIODIC_MILLIS)
        }
    }

    private fun publish(next: PrReviewSession?) {
        if (next !== session) {
            sessionLifetime?.let(Disposer::dispose)
            sessionLifetime = next?.let { newSession ->
                Disposer.newDisposable(this, "current PR session").also { lifetime ->
                    newSession.addListener(lifetime) { fire() }
                }
            }
            session = next
        }
        fire()
    }

    private fun fire() = project.messageBus.syncPublisher(TOPIC).currentPrChanged()

    override fun dispose() {}

    /** Warms the service up when the project opens. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            project.service<PrEditorComments>()
            project.service<PrWatchService>().start()
            project.service<CurrentBranchPrService>().refresh(STARTUP_DELAY)
        }
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("Bidaide current pull request", Listener::class.java)

        private const val STARTUP_DELAY = 5_000
        private const val REPO_CHANGE_DELAY = 2_000
        private const val PERIODIC_MILLIS = 3 * 60 * 1000
    }
}
