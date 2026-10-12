package com.bidaide.gh

import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Review threads live behind GraphQL only: REST has no notion of resolved threads, pending reviews or reactions.

data class GhReaction(val content: String, val count: Int, val viewerHasReacted: Boolean)

data class GhThreadComment(
    val id: String,
    val databaseId: Long,
    val body: String,
    val author: String,
    val createdAt: String?,
    /** Part of the viewer's not-yet-submitted review; invisible to everybody else. */
    val pending: Boolean,
    val canUpdate: Boolean,
    val canDelete: Boolean,
    val reactions: List<GhReaction>,
)

data class GhReviewThread(
    val id: String,
    val path: String,
    /** Line in the current diff, null once the code under the thread changed ([isOutdated]). */
    val line: Int?,
    val startLine: Int?,
    val originalLine: Int?,
    /** LEFT (base) or RIGHT (head). */
    val side: String,
    val isResolved: Boolean,
    val isOutdated: Boolean,
    val canResolve: Boolean,
    val canUnresolve: Boolean,
    val canReply: Boolean,
    val comments: List<GhThreadComment>,
) {
    val root: GhThreadComment? get() = comments.firstOrNull()
    val hasPending: Boolean get() = comments.any { it.pending }
}

class GhReviewState(
    val pullRequestId: String,
    val headRefName: String,
    /** `owner/name` of the repository the PR branch lives in (a fork for external contributions). */
    val headRepository: String?,
    /** Node id of the viewer's pending review, if one is open. */
    val pendingReviewId: String?,
    val threads: List<GhReviewThread>,
)

enum class ReviewEvent(val label: String) {
    COMMENT("Comentar"), APPROVE("Aprobar"), REQUEST_CHANGES("Pedir cambios")
}

enum class ReactionContent(val emoji: String) {
    THUMBS_UP("👍"), THUMBS_DOWN("👎"), LAUGH("😄"), HOORAY("🎉"), CONFUSED("😕"), HEART("❤️"), ROCKET("🚀"), EYES("👀");

    companion object {
        fun emojiOf(content: String): String = entries.firstOrNull { it.name == content }?.emoji ?: content
    }
}

object GhReviewCommands {
    private const val STATE_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){" +
        "repository(owner:\$owner,name:\$name){pullRequest(number:\$number){id headRefName headRepository{nameWithOwner} " +
        "reviews(states:PENDING,first:5){nodes{id}} " +
        "reviewThreads(first:50,after:\$endCursor){nodes{id isResolved isOutdated path line startLine originalLine diffSide " +
        "viewerCanResolve viewerCanUnresolve viewerCanReply " +
        "comments(first:100){nodes{id databaseId body createdAt state author{login} viewerCanUpdate viewerCanDelete " +
        "reactionGroups{content viewerHasReacted reactors{totalCount}}}}} " +
        "pageInfo{hasNextPage endCursor}}}}}"

    fun state(number: Int) = listOf(
        "api", "graphql", "--paginate", "--slurp",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$STATE_QUERY",
    )

    /** Adds a comment to the viewer's pending review, opening one if needed. Nobody sees it until the review is submitted. */
    fun addPendingThread(pullRequestId: String, path: String, body: String, side: String, line: Int, startLine: Int?) = mutation(
        "addPullRequestReviewThread", "thread{id}",
        Var("pullRequestId", "ID!", pullRequestId), Var("path", "String!", path), Var("body", "String!", body),
        Var("line", "Int!", line), Var("side", "DiffSide", side),
        Var("startLine", "Int", startLine?.takeIf { it != line }), Var("startSide", "DiffSide", side.takeIf { startLine != null && startLine != line }),
    )

    /** Published right away, unless the viewer has a pending review, in which case GitHub attaches it to that review. */
    fun reply(threadId: String, body: String) = mutation(
        "addPullRequestReviewThreadReply", "comment{id}",
        Var("pullRequestReviewThreadId", "ID!", threadId), Var("body", "String!", body),
    )

    fun submitPending(pullRequestId: String, event: ReviewEvent, body: String) = mutation(
        "submitPullRequestReview", "pullRequestReview{id}",
        Var("pullRequestId", "ID!", pullRequestId), Var("event", "PullRequestReviewEvent!", event.name),
        Var("body", "String", body.takeIf { it.isNotBlank() }),
    )

    fun discardPending(reviewId: String) =
        mutation("deletePullRequestReview", "pullRequestReview{id}", Var("pullRequestReviewId", "ID!", reviewId))

    fun setResolved(threadId: String, resolved: Boolean) =
        mutation(if (resolved) "resolveReviewThread" else "unresolveReviewThread", "thread{id}", Var("threadId", "ID!", threadId))

    fun updateComment(commentId: String, body: String) = mutation(
        "updatePullRequestReviewComment", "pullRequestReviewComment{id}",
        Var("pullRequestReviewCommentId", "ID!", commentId), Var("body", "String!", body),
    )

    fun deleteComment(commentId: String) =
        mutation("deletePullRequestReviewComment", "clientMutationId", Var("id", "ID!", commentId))

    fun setReaction(subjectId: String, content: ReactionContent, on: Boolean) = mutation(
        if (on) "addReaction" else "removeReaction", "clientMutationId",
        Var("subjectId", "ID!", subjectId), Var("content", "ReactionContent!", content.name),
    )

    internal class Var(val name: String, val type: String, val value: Any?)

    /** `mutation($a:T){name(input:{a:$a}){selection}}`; variables with a null value are left out entirely. */
    internal fun mutation(name: String, selection: String, vararg vars: Var): List<String> = buildList {
        val used = vars.filter { it.value != null }
        addAll(listOf("api", "graphql"))
        for (v in used) {
            // -F converts numbers; strings always go through -f so their content is never interpreted.
            addAll(listOf(if (v.value is Int) "-F" else "-f", "${v.name}=${v.value}"))
        }
        val declarations = used.joinToString(",") { "\$${it.name}:${it.type}" }
        val input = used.joinToString(",") { "${it.name}:\$${it.name}" }
        addAll(listOf("-f", "query=mutation($declarations){$name(input:{$input}){$selection}}"))
    }

    fun parseState(slurped: String): GhReviewState {
        var id = ""
        var headRefName = ""
        var headRepository: String? = null
        var pendingReviewId: String? = null
        val threads = ArrayList<GhReviewThread>()
        for (page in JsonParser.parseString(slurped).asJsonArray) {
            val pr = page.asJsonObject.getAsJsonObject("data").getAsJsonObject("repository").getAsJsonObject("pullRequest")
            id = pr.string("id").orEmpty()
            headRefName = pr.string("headRefName").orEmpty()
            headRepository = pr.obj("headRepository")?.string("nameWithOwner")
            pendingReviewId = pr.nodes("reviews").firstOrNull()?.string("id")
            pr.nodes("reviewThreads").mapTo(threads, ::parseThread)
        }
        return GhReviewState(id, headRefName, headRepository, pendingReviewId, threads)
    }

    private fun parseThread(t: JsonObject) = GhReviewThread(
        id = t.string("id").orEmpty(),
        path = t.string("path").orEmpty(),
        line = t.int("line"),
        startLine = t.int("startLine"),
        originalLine = t.int("originalLine"),
        side = t.string("diffSide") ?: "RIGHT",
        isResolved = t.bool("isResolved"),
        isOutdated = t.bool("isOutdated"),
        canResolve = t.bool("viewerCanResolve"),
        canUnresolve = t.bool("viewerCanUnresolve"),
        canReply = t.bool("viewerCanReply"),
        comments = t.nodes("comments").map { c ->
            GhThreadComment(
                id = c.string("id").orEmpty(),
                databaseId = c.get("databaseId")?.takeUnless { it.isJsonNull }?.asLong ?: 0,
                body = c.string("body").orEmpty(),
                author = c.obj("author")?.string("login") ?: "ghost",
                createdAt = c.string("createdAt"),
                pending = c.string("state") == "PENDING",
                canUpdate = c.bool("viewerCanUpdate"),
                canDelete = c.bool("viewerCanDelete"),
                reactions = c.getAsJsonArray("reactionGroups")?.map { it.asJsonObject }.orEmpty().mapNotNull { g ->
                    val count = g.obj("reactors")?.int("totalCount") ?: 0
                    if (count == 0) null else GhReaction(g.string("content").orEmpty(), count, g.bool("viewerHasReacted"))
                },
            )
        },
    )

}
