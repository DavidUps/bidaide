package com.bidaide.gh

import com.bidaide.BidaideBundle
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/** A GitHub account as the UI needs it. [avatarUrl] is external data: validate it before loading anything from it. */
data class GitHubUser(val login: String, val avatarUrl: String? = null)

/** An entry of `reviewRequests` in `gh pr view --json`: a user (login) or a team (name and slug). */
data class GhReviewRequest(
    @SerializedName("__typename") val typename: String? = null,
    val login: String? = null,
    val name: String? = null,
    val slug: String? = null,
)

enum class ReviewerStatus(private val text: Supplier<String>) {
    REQUESTED(BidaideBundle.lazyMessage("gh.reviewer.requested")), APPROVED(BidaideBundle.lazyMessage("gh.timeline.approved")),
    CHANGES_REQUESTED(BidaideBundle.lazyMessage("gh.timeline.changesRequested")), COMMENTED(BidaideBundle.lazyMessage("gh.timeline.commented")),
    DISMISSED(BidaideBundle.lazyMessage("gh.timeline.dismissed"));

    val label: String get() = text.get()
}

/** Someone asked to review, or who already did. Teams have no user profile, so they are never looked up as users. */
data class GhReviewer(val user: GitHubUser, val status: ReviewerStatus, val isTeam: Boolean = false)

/** The people shown at the top of a pull request: who wrote it and who reviews it. */
class GhPrPeople(val author: GitHubUser?, val reviewers: List<GhReviewer>) {
    val isEmpty: Boolean get() = author == null && reviewers.isEmpty()

    /** Everyone whose profile can be looked up, author first. */
    val users: List<GitHubUser> get() = listOfNotNull(author) + reviewers.filterNot { it.isTeam }.map { it.user }
}

object GhPeople {
    private fun key(login: String) = login.lowercase()

    /**
     * Author and reviewers from what `gh pr view` already returned, without avatars. A reviewer appears once: the
     * author is never listed as a reviewer, and a pending request wins over an older review (a re-request).
     */
    fun of(pr: GhPullRequest): GhPrPeople {
        val author = pr.author?.login?.takeIf { it.isNotBlank() }?.let { GitHubUser(it) }
        val reviewers = LinkedHashMap<String, GhReviewer>()
        for (review in pr.latestReviews.orEmpty()) {
            val login = review.author?.login?.takeIf { it.isNotBlank() } ?: continue
            val status = when (review.state?.uppercase()) {
                "APPROVED" -> ReviewerStatus.APPROVED
                "CHANGES_REQUESTED" -> ReviewerStatus.CHANGES_REQUESTED
                "COMMENTED" -> ReviewerStatus.COMMENTED
                "DISMISSED" -> ReviewerStatus.DISMISSED
                // A pending review is a draft only its author can see: not a review yet.
                else -> continue
            }
            reviewers[key(login)] = GhReviewer(GitHubUser(login), status)
        }
        for (request in pr.reviewRequests.orEmpty()) {
            val isTeam = request.typename == "Team" || (request.login == null && (request.slug != null || request.name != null))
            val login = (if (isTeam) request.slug ?: request.name else request.login)?.takeIf { it.isNotBlank() } ?: continue
            reviewers[key(login)] = GhReviewer(GitHubUser(login), ReviewerStatus.REQUESTED, isTeam)
        }
        author?.let { reviewers.remove(key(it.login)) }
        return GhPrPeople(author, reviewers.values.toList())
    }

    // --- Avatars. `gh pr view` gives logins only, so the URLs come from one GraphQL query for the whole pull request. ---

    private const val AVATARS_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){" +
        "pullRequest(number:\$number){author{login avatarUrl} " +
        "reviewRequests(first:50){nodes{requestedReviewer{__typename ... on Actor{login avatarUrl} ... on Team{name slug avatarUrl}}}} " +
        "latestReviews(first:50){nodes{author{login avatarUrl}}}}}}"

    fun avatarsQuery(number: Int) =
        listOf("api", "graphql", "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$AVATARS_QUERY")

    /** Everyone the query mentions that came with an avatar, teams included (under their slug). */
    fun parseAvatars(json: String): List<GitHubUser> {
        val pr = parseObject(json).obj("data")?.obj("repository")?.obj("pullRequest") ?: return emptyList()
        val nodes = listOfNotNull(pr.obj("author")) +
            pr.nodes("reviewRequests").mapNotNull { it.obj("requestedReviewer") } +
            pr.nodes("latestReviews").mapNotNull { it.obj("author") }
        return nodes.mapNotNull { node ->
            val login = node.string("login") ?: node.string("slug") ?: node.string("name") ?: return@mapNotNull null
            node.string("avatarUrl")?.takeIf { it.isNotBlank() }?.let { GitHubUser(login, it) }
        }.distinctBy { key(it.login) }
    }

    /** Logins as GitHub issues them, bots included (`dependabot[bot]`). Anything else is never put in an API path. */
    private val LOGIN = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\[bot])?""")

    fun isValidLogin(login: String): Boolean = LOGIN.matches(login)

    /** The fallback for a user whose avatar the pull request query did not bring. */
    fun userArgs(login: String) = listOf("api", "users/${GhCommands.encodePath(login)}")

    /** `{"login": ..., "avatar_url": ...}` of the users API; throws on anything that is not that. */
    fun parseUser(json: String): GitHubUser {
        val user = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: throw GhException(BidaideBundle.message("gh.people.badUser"))
        val login = user.string("login")?.takeIf { it.isNotBlank() } ?: throw GhException(BidaideBundle.message("gh.people.noLogin"))
        return GitHubUser(login, user.string("avatar_url")?.takeIf { it.isNotBlank() })
    }
}

/**
 * Finds the avatar of a user and remembers it by login for the rest of the session, so a user is asked for at most
 * once however many pull requests they appear in. [fetchUser] returns the JSON of `gh api users/<login>`; `gh` does
 * the authentication, no token is handled here.
 */
class GitHubUserResolver(private val fetchUser: (login: String) -> String) {
    private val cache = ConcurrentHashMap<String, GitHubUser>()

    fun cached(login: String): GitHubUser? = cache[login.lowercase()]

    /** Stores users whose avatar is already known, e.g. from the query for a whole pull request. */
    fun remember(users: Collection<GitHubUser>) {
        users.filter { it.avatarUrl != null }.forEach { cache[it.login.lowercase()] = it }
    }

    /**
     * Blocking; call off the EDT. Never throws: when `gh` is missing, fails or answers nonsense the user comes back
     * without avatar and nothing is cached, so a later attempt can still succeed.
     */
    fun resolve(user: GitHubUser): GitHubUser {
        if (user.avatarUrl != null) {
            remember(listOf(user))
            return user
        }
        cached(user.login)?.let { return it }
        if (!GhPeople.isValidLogin(user.login)) return user
        val resolved = runCatching { GhPeople.parseUser(fetchUser(user.login)) }.getOrNull() ?: return user
        // Keep the login as the pull request spelled it; the answer is only trusted for the avatar.
        val result = user.copy(avatarUrl = resolved.avatarUrl)
        // A user without avatar is a definitive answer too, worth remembering.
        cache[user.login.lowercase()] = result
        return result
    }
}
