package com.bidaide.github.ui

import com.bidaide.gh.GhComment
import com.bidaide.gh.GhLabel
import com.bidaide.gh.GhTimelineEvent
import com.bidaide.gh.display
import com.intellij.openapi.util.text.StringUtil

/** Small HTML builders for the detail panes. */
object Html {
    fun esc(text: String?): String = StringUtil.escapeXmlEntities(text.orEmpty())

    fun link(url: String?, label: String): String = if (url.isNullOrBlank()) esc(label) else "<a href=\"${esc(url)}\">${esc(label)}</a>"

    fun date(iso: String?): String = iso?.take(16)?.replace('T', ' ').orEmpty()

    fun labels(labels: List<GhLabel>?): String =
        labels.orEmpty().joinToString(" ") { "<code>${esc(it.name)}</code>" }

    fun section(title: String, content: String): String = if (content.isBlank()) "" else "<h3>${esc(title)}</h3>$content"

    fun comments(comments: List<GhComment>?): String =
        comments.orEmpty().joinToString("") {
            "<p><b>${esc(it.author.display())}</b> <small>${date(it.createdAt)}</small></p>${Markdown.toHtml(it.body)}"
        }

    /** Activity feed: comments and reviews with their text, everything else as a one-line note. */
    fun timeline(events: List<GhTimelineEvent>): String = events.joinToString("") { event ->
        val head = "<b>${esc(event.actor)}</b> ${esc(event.summary)} <small><font color=\"gray\">${date(event.date)}</font></small>"
        if (event.body != null) "<p>$head</p>${Markdown.toHtml(event.body)}" else "<div><font color=\"gray\">•</font> $head</div>"
    }

    fun page(content: String): String = "<html><body style=\"margin:8px\">$content</body></html>"
}
