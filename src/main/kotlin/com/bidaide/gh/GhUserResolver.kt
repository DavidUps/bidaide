package com.bidaide.gh

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves GitHub users with avatar URL data and keeps a per-login cache to avoid repeated `gh api users/<login>`
 * calls while the panel is alive.
 */
class GhUserResolver(
    private val userFetcher: (workDir: File, login: String) -> GhUser? =
        { workDir, login -> GhJson.user(GhCli.run(workDir, GhCommands.user(login))) },
) {
    private val cache = ConcurrentHashMap<String, GhUser>()

    fun resolvePullRequestUsers(workDir: File, pr: GhPullRequest): GhPullRequest =
        pr.copy(
            author = resolve(workDir, pr.author),
            reviewers = pr.reviewers.mapNotNull { resolve(workDir, it) },
        )

    private fun resolve(workDir: File, user: GhUser?): GhUser? {
        val login = user?.login?.trim().orEmpty()
        if (login.isBlank()) return user
        val key = login.lowercase()
        val fromCache = cache[key]
        if (fromCache != null) return merge(user, fromCache)

        val base = user.copy(login = login)
        if (!base.avatarUrl.isNullOrBlank()) {
            cache[key] = base
            return base
        }

        val fetched = runCatching { userFetcher(workDir, login) }.getOrNull()
        val resolved = merge(base, fetched ?: GhUser(login = login))
        cache[key] = resolved
        return resolved
    }

    private fun merge(primary: GhUser, secondary: GhUser): GhUser = GhUser(
        login = primary.login ?: secondary.login,
        avatarUrl = primary.avatarUrl ?: secondary.avatarUrl,
    )
}
