package com.bidaide.github.ui

import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JEditorPane

object Markdown {
    private val LOG = logger<Markdown>()

    /** GitHub-flavoured Markdown to the HTML subset Swing can show; falls back to escaped text if conversion fails. */
    fun toHtml(markdown: String?): String {
        val text = markdown?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return try {
            convertMarkdownToHtml(text)
        } catch (e: Exception) {
            LOG.info("Markdown conversion failed", e)
            Html.esc(text).replace("\n", "<br>")
        }
    }

    /** Read-only rendered Markdown laid out for a fixed [width], so it can sit inside editor inlays. */
    fun pane(markdown: String?, width: Int): JEditorPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        isOpaque = false
        font = UIUtil.getLabelFont()
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
        text = "<html><body>${toHtml(markdown)}</body></html>"
        setSize(width, Short.MAX_VALUE.toInt())
        val height = preferredSize.height
        preferredSize = Dimension(width, height)
        maximumSize = Dimension(width, height)
    }
}
