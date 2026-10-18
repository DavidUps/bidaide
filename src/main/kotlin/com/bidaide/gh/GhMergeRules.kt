package com.bidaide.gh

import com.bidaide.BidaideBundle
import com.google.gson.JsonParser

/** One line of the "can this be merged?" checklist. */
data class MergeRequirement(val label: String, val state: State, val detail: String) {
    enum class State(val symbol: String, val color: String) {
        OK("✓", "green"), FAIL("✗", "red"), PENDING("…", "#b08800"), INFO("•", "gray")
    }
}

class GhMergeStatus(val verdict: String, val requirements: List<MergeRequirement>) {
    val failing: List<MergeRequirement> get() = requirements.filter { it.state == MergeRequirement.State.FAIL }
    val pending: List<MergeRequirement> get() = requirements.filter { it.state == MergeRequirement.State.PENDING }
    val ready: Boolean get() = failing.isEmpty() && pending.isEmpty()
}

/**
 * Whether a pull request satisfies what its base branch demands: approvals, required checks, resolved conversations,
 * being up to date. Combines the PR's own state with rulesets and classic branch protection, either of which may be
 * unreadable (rulesets are not exposed for private repositories on free plans; protection needs enough permissions).
 */
object GhMergeRules {
    private const val HEADER = "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){pullRequest(number:\$number){"

    private const val STATE_QUERY = HEADER + "isDraft mergeable mergeStateStatus reviewDecision baseRefName " +
        "latestOpinionatedReviews(first:50){nodes{state author{login}}} reviewThreads(first:100){nodes{isResolved}} " +
        "commits(last:1){nodes{commit{statusCheckRollup{contexts(first:100){nodes{__typename " +
        "... on CheckRun{name status conclusion isRequired(pullRequestNumber:\$number)} " +
        "... on StatusContext{context state isRequired(pullRequestNumber:\$number)}}}}}}}}}}"

    private const val PROTECTION_QUERY = HEADER + "baseRef{branchProtectionRule{requiresApprovingReviews requiredApprovingReviewCount " +
        "requiresCodeOwnerReviews requiresConversationResolution requiresStrictStatusChecks requiredStatusCheckContexts " +
        "requiresLinearHistory requiresCommitSignatures}}}}}"

    private fun graphql(query: String, number: Int) =
        listOf("api", "graphql", "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$query")

    fun state(number: Int) = graphql(STATE_QUERY, number)

    fun protection(number: Int) = graphql(PROTECTION_QUERY, number)

    /** Rulesets that apply to [branch], from every level (repository and organisation). */
    fun rules(branch: String) = listOf("api", "repos/{owner}/{repo}/rules/branches/${GhCommands.encodePath(branch)}")

    fun baseBranch(stateJson: String): String = pullRequest(stateJson)?.string("baseRefName").orEmpty()

    private fun pullRequest(json: String) = parseObject(json).obj("data")?.obj("repository")?.obj("pullRequest")

    private class Check(val name: String, val required: Boolean, val result: String)

    private val PASSED = setOf("SUCCESS", "NEUTRAL", "SKIPPED")
    private val FAILED = setOf("FAILURE", "ERROR", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE")

    /**
     * [rulesJson] and [protectionJson] are null when they could not be read; the verdict then relies on what GitHub
     * itself reports for the pull request (review decision, required flag of each check, merge state).
     */
    fun evaluate(stateJson: String, rulesJson: String?, protectionJson: String?): GhMergeStatus {
        val pr = pullRequest(stateJson) ?: throw GhException(BidaideBundle.message("gh.merge.noState"))
        val rules = rulesJson?.let { json -> JsonParser.parseString(json).asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } }.orEmpty()
        fun rule(type: String) = rules.firstOrNull { it.string("type") == type }
        fun parameters(type: String) = rule(type)?.obj("parameters")
        val protection = protectionJson?.let(::pullRequest)?.obj("baseRef")?.obj("branchProtectionRule")
        val out = ArrayList<MergeRequirement>()
        fun add(label: String, state: MergeRequirement.State, detail: String) = out.add(MergeRequirement(label, state, detail))

        if (pr.bool("isDraft")) add(BidaideBundle.message("gh.merge.draft"), MergeRequirement.State.FAIL, BidaideBundle.message("gh.merge.draft.detail"))

        // --- Reviews ---
        val reviews = pr.nodes("latestOpinionatedReviews")
        val approvals = reviews.count { it.string("state") == "APPROVED" }
        val objectors = reviews.filter { it.string("state") == "CHANGES_REQUESTED" }.map { it.obj("author")?.string("login") ?: "ghost" }
        val needed = maxOf(
            parameters("pull_request")?.int("required_approving_review_count") ?: 0,
            protection?.takeIf { it.bool("requiresApprovingReviews") }?.int("requiredApprovingReviewCount") ?: 0,
        )
        val decision = pr.string("reviewDecision")
        val approvalCount = if (needed > 0) BidaideBundle.message("gh.merge.approvalsOf", approvals, needed) else BidaideBundle.message("gh.merge.approvals", approvals)
        when {
            objectors.isNotEmpty() -> add(BidaideBundle.message("gh.merge.reviews"), MergeRequirement.State.FAIL, BidaideBundle.message("gh.merge.reviews.changesRequested", objectors.joinToString(), approvalCount))
            approvals < needed -> add(BidaideBundle.message("gh.merge.reviews"), MergeRequirement.State.PENDING, approvalCount)
            decision == "REVIEW_REQUIRED" -> add(BidaideBundle.message("gh.merge.reviews"), MergeRequirement.State.PENDING, BidaideBundle.message("gh.merge.reviews.missingRequired", approvals))
            needed > 0 || decision == "APPROVED" -> add(BidaideBundle.message("gh.merge.reviews"), MergeRequirement.State.OK, approvalCount)
            else -> add(BidaideBundle.message("gh.merge.reviews"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.reviews.notRequired", approvals))
        }

        // --- Checks ---
        val checks = pr.nodes("commits").firstOrNull()?.obj("commit")?.obj("statusCheckRollup")?.nodes("contexts").orEmpty().map { c ->
            val result = (c.string("conclusion")?.takeIf { it.isNotBlank() } ?: c.string("state") ?: c.string("status") ?: "PENDING").uppercase()
            Check(c.string("name") ?: c.string("context").orEmpty(), c.bool("isRequired"), result)
        }
        // Required checks that have not reported yet are not in the rollup; only the rules know about them.
        val declared = parameters("required_status_checks")?.array("required_status_checks").orEmpty().mapNotNull { it.string("context") } +
            protection?.value("requiredStatusCheckContexts")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
        val missing = declared.distinct().filter { name -> checks.none { it.name == name } }
        val required = checks.filter { it.required || it.name in declared }
        val requiredFailed = required.filter { it.result in FAILED }
        val requiredPending = required.filter { it.result !in FAILED && it.result !in PASSED }
        when {
            requiredFailed.isNotEmpty() -> add(BidaideBundle.message("gh.merge.requiredChecks"), MergeRequirement.State.FAIL, BidaideBundle.message("gh.merge.checks.failing", requiredFailed.joinToString { it.name }))
            requiredPending.isNotEmpty() || missing.isNotEmpty() -> add(
                BidaideBundle.message("gh.merge.requiredChecks"), MergeRequirement.State.PENDING, BidaideBundle.message("gh.merge.checks.pending", (requiredPending.map { it.name } + missing).joinToString()),
            )
            required.isNotEmpty() -> add(BidaideBundle.message("gh.merge.requiredChecks"), MergeRequirement.State.OK, BidaideBundle.message("gh.merge.checks.passing", required.size, required.size))
            else -> add(BidaideBundle.message("gh.merge.requiredChecks"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.none"))
        }
        val optionalFailed = checks.filter { it !in required && it.result in FAILED }
        if (optionalFailed.isNotEmpty()) add(BidaideBundle.message("gh.merge.optionalChecks"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.optionalChecks.failing", optionalFailed.joinToString { it.name }))

        // --- Conversations ---
        val unresolved = pr.nodes("reviewThreads").count { !it.bool("isResolved") }
        val mustResolve = parameters("pull_request")?.bool("required_review_thread_resolution") == true || protection?.bool("requiresConversationResolution") == true
        when {
            mustResolve && unresolved > 0 -> add(BidaideBundle.message("gh.merge.conversations"), MergeRequirement.State.FAIL, BidaideBundle.message("gh.merge.conversations.mustResolve", unresolved))
            mustResolve -> add(BidaideBundle.message("gh.merge.conversations"), MergeRequirement.State.OK, BidaideBundle.message("gh.merge.conversations.resolved"))
            unresolved > 0 -> add(BidaideBundle.message("gh.merge.conversations"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.conversations.unresolved", unresolved))
        }

        // --- Branch ---
        val mergeState = pr.string("mergeStateStatus").orEmpty()
        val strict = parameters("required_status_checks")?.bool("strict_required_status_checks_policy") == true || protection?.bool("requiresStrictStatusChecks") == true
        val base = pr.string("baseRefName").orEmpty()
        when {
            mergeState == "BEHIND" && strict -> add(BidaideBundle.message("gh.merge.upToDate"), MergeRequirement.State.FAIL, BidaideBundle.message("gh.merge.upToDate.behindStrict", base))
            mergeState == "BEHIND" -> add(BidaideBundle.message("gh.merge.upToDate"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.upToDate.behind", base))
            strict -> add(BidaideBundle.message("gh.merge.upToDate"), MergeRequirement.State.OK, BidaideBundle.message("gh.merge.upToDate.ok", base))
        }
        when (pr.string("mergeable")) {
            "CONFLICTING" -> add(BidaideBundle.message("gh.merge.conflicts"), MergeRequirement.State.FAIL, BidaideBundle.message("gh.merge.conflicts.with", base))
            "MERGEABLE" -> add(BidaideBundle.message("gh.merge.conflicts"), MergeRequirement.State.OK, BidaideBundle.message("gh.merge.none"))
            else -> add(BidaideBundle.message("gh.merge.conflicts"), MergeRequirement.State.PENDING, BidaideBundle.message("gh.merge.conflicts.unknown"))
        }

        // --- Rules that cannot be checked from here, but explain a refused merge ---
        if (rule("required_linear_history") != null || protection?.bool("requiresLinearHistory") == true) {
            add(BidaideBundle.message("gh.merge.linearHistory"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.linearHistory.detail"))
        }
        if (rule("required_signatures") != null || protection?.bool("requiresCommitSignatures") == true) {
            add(BidaideBundle.message("gh.merge.signedCommits"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.signedCommits.detail"))
        }
        if (rule("merge_queue") != null) add(BidaideBundle.message("gh.merge.queue"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.queue.detail"))
        if (rule("required_deployments") != null) add(BidaideBundle.message("gh.merge.deployments"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.deployments.detail"))
        if (rulesJson == null && protectionJson == null) {
            add(BidaideBundle.message("gh.merge.branchRules"), MergeRequirement.State.INFO, BidaideBundle.message("gh.merge.branchRules.unreadable"))
        }

        val verdict = when (mergeState) {
            "CLEAN", "HAS_HOOKS" -> BidaideBundle.message("gh.merge.verdict.clean")
            "UNSTABLE" -> BidaideBundle.message("gh.merge.verdict.unstable")
            "BLOCKED" -> BidaideBundle.message("gh.merge.verdict.blocked", base)
            "BEHIND" -> BidaideBundle.message("gh.merge.verdict.behind", base)
            "DIRTY" -> BidaideBundle.message("gh.merge.verdict.dirty")
            "DRAFT" -> BidaideBundle.message("gh.merge.draft")
            else -> BidaideBundle.message("gh.merge.verdict.unknown")
        }
        return GhMergeStatus(verdict, out)
    }

    /** Blocking: gathers everything and never fails because rules or protection are unreadable. */
    fun load(workDir: java.io.File, number: Int): GhMergeStatus {
        val state = GhCli.run(workDir, state(number))
        val rules = runCatching { GhCli.run(workDir, rules(baseBranch(state))) }.getOrNull()
        val protection = runCatching { GhCli.run(workDir, protection(number)) }.getOrNull()
        return evaluate(state, rules, protection)
    }
}
