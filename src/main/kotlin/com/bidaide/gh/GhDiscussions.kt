package com.bidaide.gh

import com.bidaide.gh.GhReviewCommands.Var
import com.google.gson.JsonObject

data class GhDiscussionComment(
    val author: String, val body: String, val createdAt: String?, val isAnswer: Boolean, val replies: List<GhDiscussionComment> = emptyList(),
)

data class GhDiscussion(
    val id: String,
    val number: Int,
    val title: String,
    val url: String,
    val author: String,
    val category: String,
    val updatedAt: String?,
    val answered: Boolean,
    val commentCount: Int,
    val body: String? = null,
    val comments: List<GhDiscussionComment> = emptyList(),
)

class GhDiscussionCategory(val id: String, val name: String) {
    override fun toString() = name
}

/** What is needed to open a new discussion. */
class GhDiscussionSetup(val repositoryId: String, val enabled: Boolean, val categories: List<GhDiscussionCategory>)

/** GitHub Discussions; GraphQL only. */
object GhDiscussions {
    private const val HEADER = "query(\$owner:String!,\$name:String!"
    private const val SUMMARY = "id number title url updatedAt author{login} category{name} answer{id}"

    private val LIST_QUERY = "$HEADER){repository(owner:\$owner,name:\$name){" +
        "discussions(first:50,orderBy:{field:UPDATED_AT,direction:DESC}){nodes{$SUMMARY comments{totalCount}}}}}"

    private val VIEW_QUERY = "$HEADER,\$number:Int!){repository(owner:\$owner,name:\$name){discussion(number:\$number){$SUMMARY body " +
        "comments(first:50){totalCount nodes{body createdAt isAnswer author{login} " +
        "replies(first:30){nodes{body createdAt isAnswer author{login}}}}}}}}"

    private val SETUP_QUERY = "$HEADER){repository(owner:\$owner,name:\$name){id hasDiscussionsEnabled discussionCategories(first:30){nodes{id name}}}}"

    private fun query(text: String, vararg extra: String) =
        listOf("api", "graphql", "-F", "owner={owner}", "-F", "name={repo}") + extra + listOf("-f", "query=$text")

    fun list() = query(LIST_QUERY)

    fun view(number: Int) = query(VIEW_QUERY, "-F", "number=$number")

    fun setup() = query(SETUP_QUERY)

    fun comment(discussionId: String, body: String) = GhReviewCommands.mutation(
        "addDiscussionComment", "comment{id}", Var("discussionId", "ID!", discussionId), Var("body", "String!", body),
    )

    fun create(repositoryId: String, categoryId: String, title: String, body: String) = GhReviewCommands.mutation(
        "createDiscussion", "discussion{url}",
        Var("repositoryId", "ID!", repositoryId), Var("categoryId", "ID!", categoryId), Var("title", "String!", title), Var("body", "String!", body),
    )

    private fun repository(json: String): JsonObject? = parseObject(json).obj("data")?.obj("repository")

    fun parseList(json: String): List<GhDiscussion> = repository(json)?.nodes("discussions").orEmpty().map(::parseDiscussion)

    fun parse(json: String): GhDiscussion =
        repository(json)?.obj("discussion")?.let(::parseDiscussion) ?: throw GhException("La discussion ya no existe.")

    fun parseSetup(json: String): GhDiscussionSetup {
        val repo = repository(json) ?: throw GhException("No se pudo leer el repositorio.")
        val categories = repo.nodes("discussionCategories").map { GhDiscussionCategory(it.string("id").orEmpty(), it.string("name").orEmpty()) }
        return GhDiscussionSetup(repo.string("id").orEmpty(), repo.bool("hasDiscussionsEnabled"), categories)
    }

    fun parseCreatedUrl(json: String): String =
        parseObject(json).obj("data")?.obj("createDiscussion")?.obj("discussion")?.string("url").orEmpty()

    private fun parseDiscussion(d: JsonObject) = GhDiscussion(
        id = d.string("id").orEmpty(),
        number = d.int("number") ?: 0,
        title = d.string("title").orEmpty(),
        url = d.string("url").orEmpty(),
        author = login(d),
        category = d.obj("category")?.string("name").orEmpty(),
        updatedAt = d.string("updatedAt"),
        answered = d.obj("answer") != null,
        commentCount = d.obj("comments")?.int("totalCount") ?: 0,
        body = d.string("body"),
        comments = d.nodes("comments").map(::parseComment),
    )

    private fun parseComment(c: JsonObject): GhDiscussionComment = GhDiscussionComment(
        login(c), c.string("body").orEmpty(), c.string("createdAt"), c.bool("isAnswer"), c.nodes("replies").map(::parseComment),
    )

    private fun login(node: JsonObject) = node.obj("author")?.string("login") ?: "ghost"
}
