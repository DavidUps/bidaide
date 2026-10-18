package com.bidaide.github.current

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.review.PrReviewDiff
import com.bidaide.settings.BidaideSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.io.File

/** What changed between two polls. Pure, so it can be unit tested. */
object PrWatch {
    class Snapshot(val number: Int, val title: String, val reviewDecision: String?, val checksFailed: Boolean, val checksPassed: Boolean)

    class Event(val number: Int, val message: String)

    fun snapshot(pr: GhPullRequest) = Snapshot(pr.number, pr.title, pr.reviewDecision, pr.checksFailed, pr.checksPassed)

    /** New review requests. A null [previous] is the first poll, which only records what is already there. */
    fun newReviewRequests(previous: Set<Int>?, current: List<Snapshot>): List<Event> =
        if (previous == null) emptyList() else current.filter { it.number !in previous }
            .map { Event(it.number, BidaideBundle.message("github.watch.reviewRequested", it.number, it.title)) }

    /** Review verdicts and check results of the user's own PRs that are new since [previous]. */
    fun ownPrChanges(previous: Map<Int, Snapshot>?, current: List<Snapshot>): List<Event> {
        if (previous == null) return emptyList()
        val events = ArrayList<Event>()
        for (now in current) {
            val before = previous[now.number] ?: continue
            if (now.reviewDecision != before.reviewDecision) {
                when (now.reviewDecision) {
                    "APPROVED" -> events += Event(now.number, BidaideBundle.message("github.watch.approved", now.number, now.title))
                    "CHANGES_REQUESTED" -> events += Event(now.number, BidaideBundle.message("github.watch.changesRequested", now.number, now.title))
                }
            }
            if (now.checksFailed && !before.checksFailed) events += Event(now.number, BidaideBundle.message("github.watch.checksFailed", now.number, now.title))
            if (now.checksPassed && !before.checksPassed) events += Event(now.number, BidaideBundle.message("github.watch.checksPassed", now.number, now.title))
        }
        return events
    }
}

/** Polls GitHub for review requests and for news on the user's own pull requests of this repository. */
@Service(Service.Level.PROJECT)
class PrWatchService(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var reviewRequests: Set<Int>? = null
    private var ownPrs: Map<Int, PrWatch.Snapshot>? = null
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        schedule(FIRST_POLL_MILLIS)
    }

    private fun schedule(delayMillis: Int) {
        if (!project.isDisposed) alarm.addRequest({ poll() }, delayMillis)
    }

    private fun poll() {
        try {
            if (BidaideSettings.getInstance().notificationsEnabled) {
                val workDir = GhRepoContext.workDir(project) ?: return
                val requested = list(workDir, "review-requested:@me")
                val mine = list(workDir, "author:@me")
                val events = PrWatch.newReviewRequests(reviewRequests, requested) + PrWatch.ownPrChanges(ownPrs, mine)
                reviewRequests = requested.map { it.number }.toSet()
                ownPrs = mine.associateBy { it.number }
                events.forEach(::notify)
            }
        } catch (e: Exception) {
            // gh missing or offline: stay quiet, the tool window already reports those problems.
            thisLogger().debug(e)
        } finally {
            schedule(BidaideSettings.getInstance().pollMinutes * 60 * 1000)
        }
    }

    private fun list(workDir: File, search: String): List<PrWatch.Snapshot> {
        val args = listOf(
            "pr", "list", "--state", "open", "--limit", "50", "--search", search,
            "--json", "number,title,reviewDecision,statusCheckRollup,baseRefOid,headRefOid,url,state,isDraft,headRefName,baseRefName",
        )
        return GhJson.pullRequests(GhCli.run(workDir, args)).also { it.forEach { pr -> known[pr.number] = pr } }.map(PrWatch::snapshot)
    }

    private val known = HashMap<Int, GhPullRequest>()

    private fun notify(event: PrWatch.Event) {
        val pr = known[event.number]
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Bidaide")
            .createNotification("GitHub", event.message, NotificationType.INFORMATION)
        if (pr != null) notification.addAction(NotificationAction.createSimpleExpiring(BidaideBundle.message("github.watch.review")) { PrReviewDiff.open(project, pr) })
        notification.notify(project)
    }

    override fun dispose() {}

    private companion object {
        const val FIRST_POLL_MILLIS = 20_000
    }
}
