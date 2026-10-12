package com.bidaide.gh

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class GhAsset(val name: String = "", val size: Long = 0, val downloadCount: Int = 0, val url: String? = null)

data class GhRelease(
    val name: String? = null,
    val tagName: String = "",
    val isDraft: Boolean = false,
    val isPrerelease: Boolean = false,
    val isLatest: Boolean = false,
    val publishedAt: String? = null,
    val createdAt: String? = null,
    val body: String? = null,
    val url: String? = null,
    val author: GhUser? = null,
    val targetCommitish: String? = null,
    val assets: List<GhAsset>? = null,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: tagName
}

object GhReleases {
    private val gson = Gson()
    private const val LIST_FIELDS = "name,tagName,isDraft,isPrerelease,isLatest,publishedAt,createdAt"

    fun list() = listOf("release", "list", "--limit", "50", "--json", LIST_FIELDS)

    fun view(tag: String) = listOf("release", "view", tag, "--json", "name,tagName,isDraft,isPrerelease,publishedAt,createdAt,body,url,author,targetCommitish,assets")

    /** [generateNotes] lets GitHub write the notes from the merged pull requests; [notes] then goes on top of them. */
    fun create(
        tag: String, title: String, notes: String, generateNotes: Boolean, draft: Boolean, prerelease: Boolean, target: String,
    ): List<String> = buildList {
        addAll(listOf("release", "create", tag))
        if (title.isNotBlank()) addAll(listOf("--title", title.trim()))
        // Without notes of any kind gh would open an editor, which cannot work from the IDE.
        if (notes.isNotBlank() || !generateNotes) addAll(listOf("--notes", notes))
        if (generateNotes) add("--generate-notes")
        if (draft) add("--draft")
        if (prerelease) add("--prerelease")
        if (target.isNotBlank()) addAll(listOf("--target", target.trim()))
    }

    fun download(tag: String, dir: File) = listOf("release", "download", tag, "--dir", dir.path, "--clobber")

    fun publish(tag: String) = listOf("release", "edit", tag, "--draft=false")

    fun delete(tag: String) = listOf("release", "delete", tag, "--yes")

    fun parseList(json: String): List<GhRelease> =
        gson.fromJson<List<GhRelease>>(json, object : TypeToken<List<GhRelease>>() {}.type).orEmpty()

    fun parse(json: String): GhRelease = gson.fromJson(json, GhRelease::class.java)
}
