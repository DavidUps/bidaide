package com.bidaide.github.ui

import com.bidaide.gh.GhComment
import com.bidaide.gh.GhLabel
import com.bidaide.gh.GhTimelineEvent
import com.bidaide.gh.display
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.UIUtil

/** Small HTML builders for the detail panes. */
object Html {
    fun esc(text: String?): String = StringUtil.escapeXmlEntities(text.orEmpty())

    fun link(url: String?, label: String): String = if (url.isNullOrBlank()) esc(label) else "<a href=\"${esc(url)}\">${esc(label)}</a>"

    fun date(iso: String?): String = iso?.take(16)?.replace('T', ' ').orEmpty()

    fun labels(labels: List<GhLabel>?): String =
        labels.orEmpty().joinToString(" ") { "<code>${esc(it.name)}</code>" }

    /** A titled block; nothing at all when there is no content. */
    fun section(title: String, content: String): String = if (content.isBlank()) "" else heading(title) + content

    /** A section title on a band in the theme's own tint; `<hr>` comes out bright white on dark themes. */
    fun heading(title: String): String =
        "<table width=\"100%\" cellpadding=\"5\" cellspacing=\"0\" style=\"margin-top:14px;margin-bottom:6px\"><tr>" +
            "<td bgcolor=\"${tint(0.1)}\"><font color=\"gray\"><b>${esc(title.uppercase())}</b></font></td></tr></table>"

    /** Background for quoted text (comments, reviews): the pane's own colour nudged towards the text colour. */
    fun tint(amount: Double = 0.07): String {
        val background = UIUtil.getPanelBackground()
        val foreground = UIUtil.getLabelForeground()
        fun mix(a: Int, b: Int) = (a + (b - a) * amount).toInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(mix(background.red, foreground.red), mix(background.green, foreground.green), mix(background.blue, foreground.blue))
    }

    /** Markdown written by a person, set apart in a tinted box. */
    fun quote(markdown: String?): String {
        val html = Markdown.toHtml(markdown)
        return if (html.isEmpty()) "" else "<table width=\"100%\" cellpadding=\"8\" cellspacing=\"0\"><tr><td bgcolor=\"${tint()}\">$html</td></tr></table>"
    }

    private fun entry(head: String, iso: String?): String =
        "<table width=\"100%\" cellpadding=\"2\" cellspacing=\"0\"><tr><td>$head</td>" +
            "<td align=\"right\" width=\"110\"><font color=\"gray\"><small>${esc(TimeAgo.format(iso))}</small></font></td></tr></table>"

    fun comments(comments: List<GhComment>?): String =
        comments.orEmpty().joinToString("") { entry("<b>${esc(it.author.display())}</b> comentó", it.createdAt) + quote(it.body) }

    /** Activity feed: comments and reviews with their text in a box, everything else as a one-line note. */
    fun timeline(events: List<GhTimelineEvent>): String = events.joinToString("") { event ->
        val who = "<b>${esc(event.actor)}</b> ${esc(event.summary)}"
        if (event.body != null) entry(who, event.date) + quote(event.body) else entry("<font color=\"gray\">$who</font>", event.date)
    }

    fun page(content: String): String = "<html><body style=\"margin:8px\">$content</body></html>"
}
