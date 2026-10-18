package com.bidaide.github.pr

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhPeople
import com.bidaide.gh.GhPrPeople
import com.bidaide.gh.GitHubUser
import com.bidaide.gh.GitHubUserResolver
import com.bidaide.gh.ReviewerStatus
import com.bidaide.github.ui.UserChip
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.awt.FlowLayout
import java.io.File
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel

/** Looks up the avatars of the people of a pull request through `gh`, off the EDT, with a session-wide cache by login. */
object GitHubUsers {
    val resolver = GitHubUserResolver { login -> GhCli.run(null, GhPeople.userArgs(login)) }

    /**
     * Hands [onResolved] (on the EDT) the same users with whatever avatars could be found. One query covers the whole
     * pull request; a user it did not bring is asked for individually; users seen before cost nothing.
     */
    fun resolveAsync(workDir: File?, number: Int, people: GhPrPeople, onResolved: (List<GitHubUser>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = resolve(resolver, people) { GhCli.run(workDir, GhPeople.avatarsQuery(number)) }
            ApplicationManager.getApplication().invokeLater({ onResolved(resolved) }, ModalityState.any())
        }
    }

    /** Blocking and never throwing: without `gh`, or when it fails, users simply come back without avatar. */
    internal fun resolve(resolver: GitHubUserResolver, people: GhPrPeople, fetchPullRequestAvatars: () -> String): List<GitHubUser> {
        val everyone = listOfNotNull(people.author) + people.reviewers.map { it.user }
        if (everyone.any { resolver.cached(it.login) == null }) {
            runCatching { resolver.remember(GhPeople.parseAvatars(fetchPullRequestAvatars())) }
        }
        val lookedUp = people.users.associate { it.login.lowercase() to resolver.resolve(it) }
        // Teams are not users: for them the pull request query is the only source.
        return everyone.map { lookedUp[it.login.lowercase()] ?: resolver.cached(it.login) ?: it }
    }
}

/**
 * The strip at the top of the pull request preview: author and reviewers, each with a round avatar. It is built
 * from data already at hand, so it shows immediately with placeholders; avatars are filled in as [resolve] and the
 * image downloads complete, and it stays as it is if they never do.
 */
class PullRequestPeoplePanel(
    people: GhPrPeople,
    resolve: (GhPrPeople, (List<GitHubUser>) -> Unit) -> Unit,
    cached: (String) -> GitHubUser? = GitHubUsers.resolver::cached,
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    val chips = ArrayList<UserChip>()

    init {
        isOpaque = false
        people.author?.let {
            add(caption(BidaideBundle.message("github.pr.people.author")))
            add(chip(cached(it.login) ?: it, ""))
        }
        if (people.reviewers.isNotEmpty()) {
            if (people.author != null) add(Box.createHorizontalStrut(12))
            add(caption(BidaideBundle.message("github.pr.people.reviewers")))
            for (reviewer in people.reviewers) {
                add(chip(cached(reviewer.user.login) ?: reviewer.user, symbol(reviewer.status) + " " + reviewer.status.label))
            }
        }
        if (!people.isEmpty) {
            resolve(people) { users ->
                val byLogin = users.associateBy { it.login.lowercase() }
                chips.forEach { chip -> byLogin[chip.login.lowercase()]?.let(chip::show) }
            }
        }
    }

    private fun caption(text: String) = JLabel("<html><font color=\"gray\">$text</font></html>")

    private fun chip(user: GitHubUser, suffix: String) = UserChip(user, suffix).also(chips::add)

    private fun symbol(status: ReviewerStatus) = when (status) {
        ReviewerStatus.APPROVED -> "✓"
        ReviewerStatus.CHANGES_REQUESTED -> "✗"
        ReviewerStatus.COMMENTED -> "💬"
        ReviewerStatus.DISMISSED -> "–"
        ReviewerStatus.REQUESTED -> "…"
    }
}
