package com.bidaide.github.ui

import com.bidaide.BidaideBundle
import com.bidaide.settings.BidaideSettings
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.icons.AllIcons
import com.intellij.ui.InplaceButton
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/** Canned comments defined in Settings, offered wherever a review comment is written. */
object SavedReplies {
    class Reply(val name: String, val text: String)

    /** `Name = text` lines; a literal `\n` in the text becomes a line break. */
    fun parse(text: String): List<Reply> = BidaideSettings.namedLines(text).map { (name, body) -> Reply(name, body.replace("\\n", "\n")) }

    /** A link that inserts a saved reply at the caret of [target]; null when none are defined. */
    fun link(target: JTextComponent): ActionLink? {
        if (replies().isEmpty()) return null
        lateinit var link: ActionLink
        link = ActionLink(BidaideBundle.message("settings.savedReplies.group")) { choose(target, link) }
        return link
    }

    /** The same as [link], as a small icon for tight toolbars. */
    fun button(target: JTextComponent): JComponent? {
        if (replies().isEmpty()) return null
        lateinit var button: InplaceButton
        button = InplaceButton(BidaideBundle.message("settings.savedReplies.group"), AllIcons.Nodes.Template) { choose(target, button) }
        return button
    }

    private fun replies() = parse(BidaideSettings.getInstance().savedReplies)

    private fun choose(target: JTextComponent, anchor: JComponent) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(replies())
            .setRenderer(SimpleListCellRenderer.create("") { it.name })
            .setNamerForFiltering { it.name }
            .setItemChosenCallback { reply ->
                target.replaceSelection(reply.text)
                target.requestFocusInWindow()
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }
}
