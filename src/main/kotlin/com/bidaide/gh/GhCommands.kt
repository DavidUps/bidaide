package com.bidaide.gh

/** Builds `gh` argument lists. Pure, so the exact commands can be unit tested. */
object GhCommands {
    const val PR_LIST_FIELDS = "number,title,state,isDraft,author,headRefName,baseRefName,createdAt,updatedAt,url,reviewDecision," +
        "labels,assignees,statusCheckRollup"
    const val PR_VIEW_FIELDS = "$PR_LIST_FIELDS,body,additions,deletions,changedFiles,files,commits,reviews,comments," +
        "mergeable,baseRefOid,headRefOid,autoMergeRequest,milestone,reviewRequests,latestReviews"
    const val ISSUE_LIST_FIELDS = "number,title,state,author,labels,assignees,updatedAt,url,milestone"
    const val ISSUE_VIEW_FIELDS = "$ISSUE_LIST_FIELDS,body,comments"
    private const val LIST_LIMIT = "100"

    enum class StateFilter(val cli: String, val label: String) {
        OPEN("open", "Abiertos"), CLOSED("closed", "Cerrados"), MERGED("merged", "Fusionados"), ALL("all", "Todos")
    }

    enum class MergeMethod(val flag: String, val label: String) {
        MERGE("--merge", "Merge commit"), SQUASH("--squash", "Squash and merge"), REBASE("--rebase", "Rebase and merge")
    }

    enum class ReviewKind(val flag: String, val label: String) {
        APPROVE("--approve", "Aprobar"), REQUEST_CHANGES("--request-changes", "Pedir cambios"), COMMENT("--comment", "Comentar")
    }

    fun prList(state: StateFilter, search: String): List<String> =
        listOf("pr", "list", "--state", state.cli, "--limit", LIST_LIMIT, "--json", PR_LIST_FIELDS) + searchArgs(search)

    fun prView(number: Int) = listOf("pr", "view", number.toString(), "--json", PR_VIEW_FIELDS)

    fun prCheckout(number: Int) = listOf("pr", "checkout", number.toString())

    /** [reviewers], [assignees] and [labels] are comma-separated lists; `@me` works as an assignee. */
    fun prCreate(
        title: String, body: String, base: String?, draft: Boolean,
        reviewers: String = "", assignees: String = "", labels: String = "",
    ): List<String> = buildList {
        addAll(listOf("pr", "create", "--title", title, "--body", body))
        if (!base.isNullOrBlank()) addAll(listOf("--base", base))
        if (draft) add("--draft")
        csv(reviewers)?.let { addAll(listOf("--reviewer", it)) }
        csv(assignees)?.let { addAll(listOf("--assignee", it)) }
        csv(labels)?.let { addAll(listOf("--label", it)) }
    }

    private fun csv(text: String): String? =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",").takeIf { it.isNotEmpty() }

    fun prReview(number: Int, kind: ReviewKind, body: String): List<String> = buildList {
        addAll(listOf("pr", "review", number.toString(), kind.flag))
        if (body.isNotBlank()) addAll(listOf("--body", body))
    }

    /** With [auto] GitHub merges by itself once required checks and reviews pass. */
    fun prMerge(number: Int, method: MergeMethod, deleteBranch: Boolean, auto: Boolean = false): List<String> = buildList {
        addAll(listOf("pr", "merge", number.toString(), method.flag))
        if (deleteBranch) add("--delete-branch")
        if (auto) add("--auto")
    }

    fun prDisableAutoMerge(number: Int) = listOf("pr", "merge", number.toString(), "--disable-auto")

    /** Brings the PR branch up to date with its base, by merge or by rebase. */
    fun prUpdateBranch(number: Int, rebase: Boolean): List<String> = buildList {
        addAll(listOf("pr", "update-branch", number.toString()))
        if (rebase) add("--rebase")
    }

    /** The PR of the checked-out branch; gh fails with "no pull requests found" when there is none. */
    fun prForCurrentBranch() = listOf("pr", "view", "--json", PR_VIEW_FIELDS)

    fun prComment(number: Int, body: String) = listOf("pr", "comment", number.toString(), "--body", body)

    fun prSetOpen(number: Int, open: Boolean) = listOf("pr", if (open) "reopen" else "close", number.toString())

    fun prReady(number: Int) = listOf("pr", "ready", number.toString())

    fun issueList(state: StateFilter, search: String): List<String> {
        // Issues have no "merged" state.
        val cliState = if (state == StateFilter.MERGED) StateFilter.CLOSED.cli else state.cli
        return listOf("issue", "list", "--state", cliState, "--limit", LIST_LIMIT, "--json", ISSUE_LIST_FIELDS) + searchArgs(search)
    }

    fun issueView(number: Int) = listOf("issue", "view", number.toString(), "--json", ISSUE_VIEW_FIELDS)

    fun issueCreate(title: String, body: String) = listOf("issue", "create", "--title", title, "--body", body)

    fun issueComment(number: Int, body: String) = listOf("issue", "comment", number.toString(), "--body", body)

    /** Creates a branch linked to the issue and checks it out. */
    fun issueDevelop(number: Int) = listOf("issue", "develop", number.toString(), "--checkout")

    fun labelList() = listOf("label", "list", "--limit", "200", "--json", "name", "--jq", ".[].name")

    fun milestoneList() = listOf("api", "$REPO/milestones?state=open&per_page=100", "--jq", ".[].title")

    /**
     * `gh issue edit` / `gh pr edit`. [kind] is "issue" or "pr". [milestone]: null leaves it alone, blank removes it.
     * [addReviewers] only applies to pull requests.
     */
    fun editMetadata(
        kind: String, number: Int,
        addLabels: Collection<String>, removeLabels: Collection<String>,
        addAssignees: Collection<String>, removeAssignees: Collection<String>,
        milestone: String?, addReviewers: Collection<String> = emptyList(),
    ): List<String> = buildList {
        addAll(listOf(kind, "edit", number.toString()))
        fun flag(name: String, values: Collection<String>) {
            if (values.isNotEmpty()) addAll(listOf(name, values.joinToString(",")))
        }
        flag("--add-label", addLabels)
        flag("--remove-label", removeLabels)
        flag("--add-assignee", addAssignees)
        flag("--remove-assignee", removeAssignees)
        flag("--add-reviewer", addReviewers)
        when {
            milestone == null -> {}
            milestone.isBlank() -> add("--remove-milestone")
            else -> addAll(listOf("--milestone", milestone))
        }
    }

    fun issueSetOpen(number: Int, open: Boolean) = listOf("issue", if (open) "reopen" else "close", number.toString())

    fun repoSetDefault(repo: String) = listOf("repo", "set-default", repo)

    fun defaultBranch() = listOf("repo", "view", "--json", "defaultBranchRef", "--jq", ".defaultBranchRef.name")

    /**
     * [path] is relative to the repository root. With [commit] the URL is a permalink; otherwise it points at [branch]
     * (or the default branch when both are null).
     */
    fun browse(path: String?, lines: IntRange?, branch: String?, commit: String?, printOnly: Boolean): List<String> = buildList {
        add("browse")
        if (path != null) {
            val suffix = when {
                lines == null -> ""
                lines.first == lines.last -> ":${lines.first}"
                else -> ":${lines.first}-${lines.last}"
            }
            add(path + suffix)
        }
        when {
            commit != null -> add("--commit=$commit")
            branch != null -> addAll(listOf("--branch", branch))
        }
        if (printOnly) add("--no-browser")
    }

    fun browseNumber(number: Int) = listOf("browse", number.toString())

    fun gistCreate(filePath: String, description: String, public: Boolean): List<String> = buildList {
        addAll(listOf("gist", "create", filePath))
        if (description.isNotBlank()) addAll(listOf("--desc", description))
        if (public) add("--public")
    }

    // --- REST calls through `gh api`; {owner}/{repo} is filled in by gh from the repository it runs in. ---

    private const val REPO = "repos/{owner}/{repo}"

    /** A PR diff is "three-dot": head against the merge base, not against the tip of the base branch. */
    fun mergeBase(base: String, head: String) =
        listOf("api", "$REPO/compare/$base...$head", "--jq", ".merge_base_commit.sha")

    fun prFiles(number: Int) = listOf("api", "$REPO/pulls/$number/files?per_page=100", "--paginate", "--slurp")

    /** A single commit with the files it changed (and their patches). */
    fun commit(sha: String) = listOf("api", "$REPO/commits/$sha")

    /** Pull requests a commit belongs to; the way from a blamed line back to its review. */
    fun commitPulls(sha: String) = listOf("api", "$REPO/commits/$sha/pulls")

    fun repoName() = listOf("repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner")

    fun fileContent(path: String, ref: String) =
        listOf("api", "-H", "Accept: application/vnd.github.raw+json", "$REPO/contents/${encodePath(path)}?ref=$ref")

    /**
     * Publishes a single comment immediately. [side] is LEFT (base) or RIGHT (head); [startLine] makes it multi-line.
     * GitHub rejects this while the user has a pending review: use [GhReviewCommands.addPendingThread] then.
     */
    fun prCreateReviewComment(
        number: Int, body: String, commit: String, path: String, side: String, line: Int, startLine: Int?,
    ): List<String> = buildList {
        addAll(listOf("api", "--method", "POST", "$REPO/pulls/$number/comments"))
        addAll(listOf("-f", "body=$body", "-f", "commit_id=$commit", "-f", "path=$path", "-f", "side=$side", "-F", "line=$line"))
        if (startLine != null && startLine != line) addAll(listOf("-F", "start_line=$startLine", "-f", "start_side=$side"))
    }

    private const val VIEWED_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){" +
        "repository(owner:\$owner,name:\$name){pullRequest(number:\$number){id " +
        "files(first:100,after:\$endCursor){nodes{path viewerViewedState} pageInfo{hasNextPage endCursor}}}}}"

    /** Per-file "viewed" marks of the current user, plus the PR node id needed to change them. GraphQL only. */
    fun prViewedStates(number: Int) = listOf(
        "api", "graphql", "--paginate", "--slurp",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$VIEWED_QUERY",
    )

    fun prSetFileViewed(pullRequestId: String, path: String, viewed: Boolean): List<String> {
        val mutation = if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"
        return listOf(
            "api", "graphql", "-f", "id=$pullRequestId", "-f", "path=$path",
            "-f", "query=mutation(\$id:ID!,\$path:String!){$mutation(input:{pullRequestId:\$id,path:\$path}){clientMutationId}}",
        )
    }

    internal fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

    // --- GitHub Actions ---

    private const val RUN_LIST_FIELDS = "databaseId,displayTitle,workflowName,status,conclusion,headBranch,headSha,event,createdAt,url"

    fun runList(branch: String?, workflow: String): List<String> = buildList {
        addAll(listOf("run", "list", "--limit", "50", "--json", RUN_LIST_FIELDS))
        if (!branch.isNullOrBlank()) addAll(listOf("--branch", branch))
        if (workflow.isNotBlank()) addAll(listOf("--workflow", workflow.trim()))
    }

    fun runView(id: Long) = listOf("run", "view", id.toString(), "--json", "$RUN_LIST_FIELDS,jobs")

    fun runRerun(id: Long, failedOnly: Boolean): List<String> = buildList {
        addAll(listOf("run", "rerun", id.toString()))
        if (failedOnly) add("--failed")
    }

    fun runCancel(id: Long) = listOf("run", "cancel", id.toString())

    fun runLog(id: Long, failedOnly: Boolean) = listOf("run", "view", id.toString(), if (failedOnly) "--log-failed" else "--log")

    fun workflowList() = listOf("workflow", "list", "--json", "id,name,path,state")

    fun workflowYaml(id: Long) = listOf("workflow", "view", id.toString(), "--yaml")

    fun workflowRun(id: Long, ref: String, inputs: Map<String, String>): List<String> = buildList {
        addAll(listOf("workflow", "run", id.toString()))
        if (ref.isNotBlank()) addAll(listOf("--ref", ref.trim()))
        inputs.forEach { (key, value) -> addAll(listOf("-f", "$key=$value")) }
    }

    /** Quick filters, expressed as GitHub search qualifiers so they combine with free-text search. */
    val PR_SCOPES = listOf(
        "Todas" to "", "Mías" to "author:@me", "Pendientes de mi review" to "review-requested:@me",
        "Asignadas a mí" to "assignee:@me", "Me mencionan" to "mentions:@me",
    )
    val ISSUE_SCOPES = listOf("Todas" to "", "Mías" to "author:@me", "Asignadas a mí" to "assignee:@me", "Me mencionan" to "mentions:@me")

    fun combineSearch(scope: String, search: String): String = listOf(scope.trim(), search.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private fun searchArgs(search: String) = if (search.isBlank()) emptyList() else listOf("--search", search.trim())
}
