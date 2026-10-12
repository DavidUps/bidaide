package com.bidaide.gh

class GhAccount(val host: String, val login: String, val active: Boolean) {
    override fun toString() = "$login @ $host${if (active) "  (activa)" else ""}"
}

/** The accounts `gh auth login` knows about. Switching is global: it also changes what `gh` does in a terminal. */
object GhAccounts {
    fun status() = listOf("auth", "status", "--json", "hosts")

    fun switch(account: GhAccount) = listOf("auth", "switch", "--hostname", account.host, "--user", account.login)

    fun parse(json: String): List<GhAccount> {
        val hosts = parseObject(json).obj("hosts") ?: return emptyList()
        return hosts.keySet().flatMap { host ->
            hosts.array(host).filter { it.string("state") != "error" }.map { GhAccount(host, it.string("login").orEmpty(), it.bool("active")) }
        }
    }
}

class GhCodeMatch(val path: String, val repository: String, val url: String, val fragment: String)

class GhLinePullRequest(val number: Int, val title: String, val url: String)

object GhSearch {
    /** [repo] (`owner/name`) or [owner] narrow the search; with neither it covers all of GitHub. */
    fun code(query: String, repo: String?, owner: String?): List<String> = buildList {
        addAll(listOf("search", "code"))
        addAll(query.trim().split(Regex("\\s+")))
        if (repo != null) addAll(listOf("--repo", repo)) else if (owner != null) addAll(listOf("--owner", owner))
        addAll(listOf("--limit", "50", "--json", "path,repository,url,textMatches"))
    }

    fun parseCode(json: String): List<GhCodeMatch> =
        com.google.gson.JsonParser.parseString(json).asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }.map { m ->
            GhCodeMatch(
                path = m.string("path").orEmpty(),
                repository = m.obj("repository")?.string("nameWithOwner").orEmpty(),
                url = m.string("url").orEmpty(),
                fragment = m.array("textMatches").firstOrNull()?.string("fragment").orEmpty(),
            )
        }

    /** First line of `git blame --porcelain`: the commit, or null for lines not committed yet. */
    fun blamedCommit(porcelain: String): String? =
        porcelain.lineSequence().firstOrNull()?.substringBefore(' ')?.takeIf { it.length >= 40 && it.any { c -> c != '0' } }

    /** Answer of [GhCommands.commitPulls]: the pull request that brought the commit in, preferring a merged one. */
    fun parseCommitPulls(json: String): GhLinePullRequest? {
        val pulls = com.google.gson.JsonParser.parseString(json).asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }
        val pull = pulls.firstOrNull { it.string("merged_at") != null } ?: pulls.firstOrNull() ?: return null
        return GhLinePullRequest(pull.int("number") ?: return null, pull.string("title").orEmpty(), pull.string("html_url").orEmpty())
    }
}

object GhRepos {
    fun listMine() = listOf("repo", "list", "--limit", "200", "--json", "nameWithOwner", "--jq", ".[].nameWithOwner")

    fun clone(repo: String, target: String) = listOf("repo", "clone", repo, target)

    /** Creates the repository on GitHub from the local one in the working directory and wires [remote] to it. */
    fun publish(name: String, description: String, private: Boolean, remote: String, push: Boolean): List<String> = buildList {
        addAll(listOf("repo", "create", name, "--source", ".", "--remote", remote, if (private) "--private" else "--public"))
        if (description.isNotBlank()) addAll(listOf("--description", description.trim()))
        if (push) add("--push")
    }
}
