package com.bidaide.gh

class GhProjectItem(val id: String, val title: String, val status: String, val type: String, val number: Int?, val url: String?, val repository: String?) {
    override fun toString() = (number?.let { "#$it " }.orEmpty()) + title
}

data class GhProject(
    val number: Int,
    val id: String,
    val title: String,
    val url: String,
    val closed: Boolean,
    val description: String,
    val itemCount: Int,
    val owner: String,
    val items: List<GhProjectItem>? = null,
    /** The single-select field the board is grouped by; null when unknown or the project has none. */
    val statusField: GhProjectField? = null,
)

class GhProjectOption(val id: String, val name: String) {
    override fun toString() = name
}

/** A single-select field of a project, such as the Status column of a board. */
class GhProjectField(val id: String, val name: String, val options: List<GhProjectOption>)

/** GitHub Projects (v2) through `gh project`, which needs the `project` token scope. */
object GhProjects {
    const val SCOPE_HINT = "Falta el permiso de Projects en tu sesión de gh. Ejecuta en una terminal: gh auth refresh -s project"

    const val NO_STATUS = "Sin estado"

    fun owner() = listOf("repo", "view", "--json", "owner", "--jq", ".owner.login")

    fun list(owner: String) = listOf("project", "list", "--owner", owner, "--format", "json", "--limit", "50")

    fun items(number: Int, owner: String) = listOf("project", "item-list", number.toString(), "--owner", owner, "--format", "json", "--limit", "300")

    fun fields(number: Int, owner: String) = listOf("project", "field-list", number.toString(), "--owner", owner, "--format", "json")

    fun addItem(number: Int, owner: String, url: String) = listOf("project", "item-add", number.toString(), "--owner", owner, "--url", url)

    fun moveItem(projectId: String, itemId: String, fieldId: String, optionId: String) = listOf(
        "project", "item-edit", "--id", itemId, "--project-id", projectId, "--field-id", fieldId, "--single-select-option-id", optionId,
    )

    /** Works for pull requests too: gh resolves the number either way. */
    fun itemUrl(number: Int) = listOf("issue", "view", number.toString(), "--json", "url", "--jq", ".url")

    fun isScopeError(message: String?): Boolean = message.orEmpty().let { "required scopes" in it || "read:project" in it }

    fun parseList(json: String, owner: String): List<GhProject> = parseObject(json).array("projects").map { p ->
        GhProject(
            number = p.int("number") ?: 0,
            id = p.string("id").orEmpty(),
            title = p.string("title").orEmpty(),
            url = p.string("url").orEmpty(),
            closed = p.bool("closed"),
            description = p.string("shortDescription").orEmpty(),
            itemCount = p.obj("items")?.int("totalCount") ?: 0,
            owner = owner,
        )
    }

    fun parseItems(json: String): List<GhProjectItem> = parseObject(json).array("items").map { item ->
        val content = item.obj("content")
        GhProjectItem(
            id = item.string("id").orEmpty(),
            title = item.string("title") ?: content?.string("title").orEmpty(),
            status = item.string("status")?.takeIf { it.isNotBlank() } ?: NO_STATUS,
            type = content?.string("type").orEmpty(),
            number = content?.int("number"),
            url = content?.string("url"),
            repository = content?.string("repository"),
        )
    }

    /** The field boards are grouped by: the one called Status, else the first single-select field. */
    fun parseStatusField(json: String): GhProjectField? {
        val fields = parseObject(json).array("fields").filter { it.array("options").isNotEmpty() }
        val field = fields.firstOrNull { it.string("name").equals("Status", ignoreCase = true) } ?: fields.firstOrNull() ?: return null
        val options = field.array("options").map { GhProjectOption(it.string("id").orEmpty(), it.string("name").orEmpty()) }
        return GhProjectField(field.string("id").orEmpty(), field.string("name").orEmpty(), options)
    }
}
