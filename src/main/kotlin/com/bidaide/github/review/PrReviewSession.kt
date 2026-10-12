package com.bidaide.github.review

import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhPrFile
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.GhReviewState
import com.bidaide.gh.GhReviewThread
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything needed to review one pull request: changed files, review threads, the viewer's pending review and
 * "viewed" marks. Mutations go through [mutate], which re-reads the threads and notifies listeners on the EDT.
 */
class PrReviewSession(
    val project: Project,
    val workDir: File,
    val pr: GhPullRequest,
    val headSha: String,
    val mergeBaseSha: String,
    val files: List<GhPrFile>,
    @Volatile var state: GhReviewState,
    viewedPaths: Set<String>,
) {
    val number: Int get() = pr.number
    val threads: List<GhReviewThread> get() = state.threads
    val hasPendingReview: Boolean get() = state.pendingReviewId != null
    val pendingCount: Int get() = threads.sumOf { thread -> thread.comments.count { it.pending } }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Thread the diff viewer should scroll to as soon as its file is shown. */
    @Volatile var scrollToThreadId: String? = null

    /** [listener] runs on the EDT after threads or viewed marks change. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun fireChanged() = listeners.forEach { it() }

    /** Asks the views to redraw without touching the server (e.g. to honour [scrollToThreadId]). */
    fun requestRender() = fireChanged()

    /** Threads of [path] that still map to a line of the current diff. */
    fun liveThreads(path: String): List<GhReviewThread> = threads.filter { it.path == path && it.line != null && !it.isOutdated }

    fun threadCount(path: String): Int = threads.count { it.path == path }

    /** Runs [args] with `gh`, refreshes the threads and notifies. [onDone] reports success on the EDT. */
    fun mutate(title: String, args: List<String>, onDone: (Boolean) -> Unit = {}) = mutate(title, onDone) { GhCli.run(workDir, args) }

    fun mutate(title: String, onDone: (Boolean) -> Unit = {}, work: () -> Unit) {
        GhCli.async(
            project, title,
            onFailure = { onDone(false) },
            work = {
                work()
                state = loadState(workDir, number)
            },
        ) {
            onDone(true)
            fireChanged()
        }
    }

    fun refresh() = mutate("Actualizando revisión de PR #$number") {}

    /** Blocking re-read of the threads, for background pollers. Listeners are notified on the EDT. */
    fun reloadBlocking() {
        state = loadState(workDir, number)
        ApplicationManager.getApplication().invokeLater { fireChanged() }
    }

    // --- "Viewed" marks, the same ones as the checkbox on github.com ---

    private val viewed: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(viewedPaths) }

    fun isViewed(path: String): Boolean = path in viewed

    val viewedCount: Int get() = files.count { it.filename in viewed }

    /** Updates the mark right away and syncs it to GitHub in the background, undoing it if that fails. */
    fun setViewed(path: String, value: Boolean) {
        if (value) viewed += path else viewed -= path
        fireChanged()
        GhCli.async(
            project, "Marcando archivo como ${if (value) "visto" else "no visto"}",
            onFailure = {
                if (value) viewed -= path else viewed += path
                fireChanged()
            },
            work = { GhCli.run(workDir, GhCommands.prSetFileViewed(state.pullRequestId, path, value)) },
        ) {}
    }

    companion object {
        /** Blocking: gathers merge base, changed files, threads and viewed marks. */
        fun load(project: Project, workDir: File, pr: GhPullRequest, baseSha: String, headSha: String): PrReviewSession {
            val mergeBase = GhCli.run(workDir, GhCommands.mergeBase(baseSha, headSha)).trim()
            val files = GhJson.prFiles(GhCli.run(workDir, GhCommands.prFiles(pr.number)))
            val viewed = GhJson.viewedStates(GhCli.run(workDir, GhCommands.prViewedStates(pr.number)))
            return PrReviewSession(project, workDir, pr, headSha, mergeBase, files, loadState(workDir, pr.number), viewed.viewedPaths)
        }

        private fun loadState(workDir: File, number: Int): GhReviewState =
            GhReviewCommands.parseState(GhCli.run(workDir, GhReviewCommands.state(number)))
    }
}

/** One file of a [PrReviewSession]; attached to its diff request so the diff extension can find it. */
class PrFileReview(val session: PrReviewSession, val file: GhPrFile) {
    val commentable: CommentableLines = CommentableLines.fromPatch(file.patch)

    companion object {
        val KEY: Key<PrFileReview> = Key.create("Bidaide.PrFileReview")
    }
}

/** GitHub only accepts inline comments on lines that are part of the diff hunks (changed lines plus context). */
class CommentableLines(private val left: Set<Int>, private val right: Set<Int>) {
    fun contains(side: Side, line: Int): Boolean = line in (if (side == Side.LEFT) left else right)

    companion object {
        private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

        fun fromPatch(patch: String?): CommentableLines {
            val left = HashSet<Int>()
            val right = HashSet<Int>()
            var leftLine = 0
            var rightLine = 0
            for (line in patch.orEmpty().lineSequence()) {
                val hunk = HUNK.find(line)
                when {
                    hunk != null -> {
                        leftLine = hunk.groupValues[1].toInt()
                        rightLine = hunk.groupValues[2].toInt()
                    }
                    line.startsWith("+") -> right += rightLine++
                    line.startsWith("-") -> left += leftLine++
                    line.startsWith(" ") -> {
                        left += leftLine++
                        right += rightLine++
                    }
                    // "\ No newline at end of file" and anything else carries no line.
                }
            }
            return CommentableLines(left, right)
        }
    }
}

val Side.gitHubName: String get() = if (this == Side.LEFT) "LEFT" else "RIGHT"

fun sideOf(gitHubName: String?): Side = if (gitHubName == "LEFT") Side.LEFT else Side.RIGHT
