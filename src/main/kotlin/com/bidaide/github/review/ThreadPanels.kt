package com.bidaide.github.review

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.GhReviewThread
import com.bidaide.gh.GhThreadComment
import com.bidaide.gh.GitHubUser
import com.bidaide.gh.ReactionContent
import com.bidaide.github.ui.AvatarImages
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Markdown
import com.bidaide.github.ui.SavedReplies
import com.bidaide.github.ui.UserChip
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.time.Instant
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Which threads are expanded / collapsed / being replied to / edited. Kept outside the panels because those are rebuilt on every change. */
class ThreadUiState {
    val expandedResolved = HashSet<String>()

    /** Threads hidden from the diff with their gutter icon; they stay listed in the conversations. */
    val collapsed = HashSet<String>()
    var replyingThreadId: String? = null
    var editingCommentId: String? = null
}

internal fun <T : JComponent> T.leftAligned(): T = apply { alignmentX = Component.LEFT_ALIGNMENT }

private val AVATAR_SIZE get() = JBUI.scale(24)
private val AVATAR_GAP get() = JBUI.scale(8)

/** A rounded frame like the one around comments on github.com; transparent, so it takes the editor's background. */
internal fun card(): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    border = JBUI.Borders.compound(
        JBUI.Borders.empty(4, 8),
        RoundedLineBorder(JBColor.border(), JBUI.scale(12), 1),
        JBUI.Borders.empty(8, 10),
    )
    isOpaque = false
}

private fun row(width: Int, vararg components: JComponent): JPanel =
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        isOpaque = false
        components.forEach(::add)
        maximumSize = Dimension(width, preferredSize.height)
    }.leftAligned()

private fun column(): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
}

private fun tag(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

/** A small outlined label, like GitHub's “Pending”. */
private fun badge(text: String) = JBLabel(text.uppercase()).apply {
    font = JBUI.Fonts.smallFont()
    foreground = UIUtil.getContextHelpForeground()
    border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(6), 1), JBUI.Borders.empty(0, 4))
}

/** The round picture of [login], top-aligned so it sits beside the first line of what follows. */
internal fun avatar(login: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    add(UserChip(GitHubUser(login, AvatarImages.profilePicture(login)), size = AVATAR_SIZE, showLogin = false), BorderLayout.NORTH)
}

/** “Moments ago”, “Yesterday 10:31”…, as the IDE writes dates; the raw date if it cannot be read. */
internal fun prettyTime(iso: String?): String =
    iso?.let { runCatching { DateFormatUtil.formatPrettyDateTime(Instant.parse(it).toEpochMilli()) }.getOrNull() } ?: Html.date(iso)

object ThreadPanels {
    /**
     * Builds the card for one review thread. Server changes go through [session] (which re-renders via its listeners);
     * [rerender] is for changes that only touch [ui].
     */
    fun build(session: PrReviewSession, thread: GhReviewThread, ui: ThreadUiState, width: Int, rerender: () -> Unit): JComponent {
        val card = card()
        if (thread.isResolved && thread.id !in ui.expandedResolved) {
            val preview = thread.root?.let { "${it.author}: ${it.body.lineSequence().firstOrNull().orEmpty().take(70)}" }.orEmpty()
            card.add(row(width, tag(BidaideBundle.message("github.thread.resolved")), JBLabel(preview), ActionLink(BidaideBundle.message("github.thread.show")) {
                ui.expandedResolved += thread.id
                rerender()
            }))
            return wrap(card)
        }

        val tags = listOfNotNull(
            BidaideBundle.message("github.thread.resolved").takeIf { thread.isResolved },
            BidaideBundle.message("github.thread.outdated", thread.originalLine ?: "?").takeIf { thread.isOutdated },
        )
        if (tags.isNotEmpty()) card.add(row(width, *tags.map(::tag).toTypedArray()))

        thread.comments.forEachIndexed { index, comment ->
            if (index > 0) card.add(spacer())
            addComment(card, session, thread, comment, ui, width, rerender)
        }

        // Replies and actions line up with the text of the comments, past the avatars.
        val indent = AVATAR_SIZE + AVATAR_GAP
        if (ui.replyingThreadId == thread.id) {
            card.add(spacer())
            card.add(InputPanel(
                width, session.viewerLogin, BidaideBundle.message("github.thread.reply.title"), embedded = true,
                onCancel = {
                    ui.replyingThreadId = null
                    rerender()
                },
                submits = arrayOf(InputPanel.Submit(if (session.hasPendingReview) BidaideBundle.message("github.review.addToReview") else BidaideBundle.message("github.thread.reply")) { body, done ->
                    session.mutate(BidaideBundle.message("github.thread.replying", session.number), GhReviewCommands.reply(thread.id, body)) { ok ->
                        if (ok) ui.replyingThreadId = null
                        done(ok)
                    }
                }),
            ).also { it.focusLater() }.leftAligned())
        } else {
            val footer = ArrayList<JComponent>()
            if (thread.canReply) footer += ActionLink(BidaideBundle.message("github.thread.reply")) {
                ui.replyingThreadId = thread.id
                rerender()
            }
            if (!thread.isResolved && thread.canResolve) footer += ActionLink(BidaideBundle.message("github.thread.resolve")) {
                ui.expandedResolved -= thread.id
                session.mutate(BidaideBundle.message("github.thread.resolving"), GhReviewCommands.setResolved(thread.id, true))
            }
            if (thread.isResolved && thread.canUnresolve) footer += ActionLink(BidaideBundle.message("github.thread.unresolve")) {
                session.mutate(BidaideBundle.message("github.thread.unresolving"), GhReviewCommands.setResolved(thread.id, false))
            }
            if (thread.isResolved) footer += ActionLink(BidaideBundle.message("github.thread.hide")) {
                ui.expandedResolved -= thread.id
                rerender()
            }
            if (footer.isNotEmpty()) {
                card.add(spacer())
                card.add(row(width, *footer.toTypedArray()).apply { border = JBUI.Borders.emptyLeft(indent - JBUI.scale(6)) })
            }
        }
        return wrap(card)
    }

    private fun spacer(): JComponent = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(1, JBUI.scale(8))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(8))
    }.leftAligned()

    /** `[avatar] author · time [PENDING]   ✎ 🗑`, then the body, then suggestion and reactions. */
    private fun addComment(
        card: JPanel, session: PrReviewSession, thread: GhReviewThread, comment: GhThreadComment,
        ui: ThreadUiState, width: Int, rerender: () -> Unit,
    ) {
        val textWidth = width - AVATAR_SIZE - AVATAR_GAP
        val content = column()

        val header = ArrayList<JComponent>()
        header += JBLabel("<html><b>${Html.esc(comment.author)}</b>&nbsp; <font color=\"gray\">${Html.esc(prettyTime(comment.createdAt))}</font></html>")
        if (comment.pending) header += badge(BidaideBundle.message("github.thread.pending"))
        if (comment.canUpdate) header += InplaceButton(BidaideBundle.message("github.thread.edit"), AllIcons.Actions.Edit) {
            ui.editingCommentId = comment.id
            rerender()
        }
        if (comment.canDelete) header += InplaceButton(BidaideBundle.message("github.thread.delete"), AllIcons.General.Delete) {
            val answer = Messages.showYesNoDialog(
                session.project, BidaideBundle.message("github.thread.delete.confirm"), BidaideBundle.message("github.thread.delete.title"),
                Messages.getQuestionIcon(),
            )
            if (answer == Messages.YES) session.mutate(BidaideBundle.message("github.thread.deleting"), GhReviewCommands.deleteComment(comment.id))
        }
        content.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            header.forEach(::add)
            maximumSize = Dimension(textWidth, preferredSize.height)
        }.leftAligned())

        if (ui.editingCommentId == comment.id) {
            content.add(InputPanel(
                textWidth, null, BidaideBundle.message("github.thread.edit.title"), embedded = true, initialText = comment.body,
                onCancel = {
                    ui.editingCommentId = null
                    rerender()
                },
                submits = arrayOf(InputPanel.Submit(BidaideBundle.message("github.dialog.save")) { body, done ->
                    session.mutate(BidaideBundle.message("github.thread.saving"), GhReviewCommands.updateComment(comment.id, body)) { ok ->
                        if (ok) ui.editingCommentId = null
                        done(ok)
                    }
                }),
            ).also { it.focusLater() }.leftAligned())
        } else {
            content.add(Markdown.pane(comment.body, textWidth).leftAligned())
            extras(session, thread, comment)?.let { content.add(row(textWidth, *it)) }
        }

        card.add(JPanel(BorderLayout(AVATAR_GAP, 0)).apply {
            isOpaque = false
            add(avatar(comment.author), BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
            maximumSize = Dimension(width, Int.MAX_VALUE)
        }.leftAligned())
    }

    private fun extras(session: PrReviewSession, thread: GhReviewThread, comment: GhThreadComment): Array<JComponent>? {
        val suggestion = Suggestions.extract(comment.body)
        val extras = ArrayList<JComponent>()
        if (suggestion != null && !thread.isOutdated && !thread.isResolved && thread.line != null && thread.side == "RIGHT") {
            extras += ActionLink(BidaideBundle.message("github.suggestion.apply")) { Suggestions.apply(session, thread, suggestion) }
        }
        for (reaction in comment.reactions) {
            val content = ReactionContent.entries.firstOrNull { it.name == reaction.content } ?: continue
            extras += ActionLink("${content.emoji} ${reaction.count}") {
                session.mutate(BidaideBundle.message("github.thread.reacting"), GhReviewCommands.setReaction(comment.id, content, !reaction.viewerHasReacted))
            }.apply { toolTipText = if (reaction.viewerHasReacted) BidaideBundle.message("github.thread.removeReaction") else BidaideBundle.message("github.thread.joinReaction") }
        }
        if (!comment.pending) {
            lateinit var add: ActionLink
            add = ActionLink("☺+") {
                JBPopupFactory.getInstance().createPopupChooserBuilder(ReactionContent.entries)
                    .setRenderer(SimpleListCellRenderer.create("") { it.emoji })
                    .setItemChosenCallback { chosen ->
                        val mine = comment.reactions.any { it.content == chosen.name && it.viewerHasReacted }
                        session.mutate(BidaideBundle.message("github.thread.reacting"), GhReviewCommands.setReaction(comment.id, chosen, !mine))
                    }
                    .createPopup().showUnderneathOf(add)
            }
            add.toolTipText = BidaideBundle.message("github.thread.addReaction")
            extras += add
        }
        return extras.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /** Keeps the card at its natural width instead of stretching across the editor. */
    private fun wrap(card: JPanel): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(card, BorderLayout.WEST)
    }
}

/**
 * The comment box of GitHub's own IDE integration: the author's avatar, a text field that grows with its content,
 * a shortcut hint and one primary button whose arrow holds the other ways of sending. A submit handler gets the
 * text and reports back whether it worked.
 */
class InputPanel(
    width: Int,
    /** Whose avatar goes beside the field; none when unknown or when the card already shows it. */
    author: String?,
    placeholder: String,
    embedded: Boolean = false,
    initialText: String = "",
    /** When set, a button inserts a GitHub suggestion block seeded with this text. */
    suggestionSeed: String? = null,
    private val onCancel: () -> Unit,
    vararg submits: Submit,
) : JPanel(BorderLayout()) {

    class Submit(val label: String, val handler: (body: String, done: (Boolean) -> Unit) -> Unit)

    private val area = JBTextArea(initialText, 1, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        // Inside the editor the field would inherit its monospaced font.
        font = JBUI.Fonts.label()
        emptyText.text = placeholder
        border = JBUI.Borders.empty(4, 6)
    }
    // Room left for the field: minus the avatar and, outside a thread, the close button and the card's own frame.
    private val fieldWidth = width - (if (author != null) AVATAR_SIZE + AVATAR_GAP else 0) - (if (embedded) 0 else JBUI.scale(28 + 38))
    private val scroll = JBScrollPane(area).apply {
        border = RoundedLineBorder(JBColor.border(), JBUI.scale(8), 1)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val actions = submits.map { submit ->
        object : AbstractAction(submit.label) {
            override fun actionPerformed(e: ActionEvent?) = send(submit)
        }
    }
    private var busy = false

    init {
        isOpaque = false
        val content = column()
        content.add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(scroll, BorderLayout.CENTER)
            if (!embedded) add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(InplaceButton(BidaideBundle.message("common.cancel"), AllIcons.Actions.Close) { onCancel() }, BorderLayout.NORTH)
            }, BorderLayout.EAST)
        }.leftAligned())

        // Under the field: tool icons, the shortcut hint (cut short when there is no room) and the buttons.
        val tools = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            if (suggestionSeed != null) {
                add(InplaceButton(BidaideBundle.message("github.suggestion.insert"), AllIcons.Actions.Diff) {
                    area.insert("```suggestion\n$suggestionSeed\n```\n", area.caretPosition)
                    area.requestFocusInWindow()
                })
            }
            SavedReplies.button(area)?.let(::add)
        }
        val hint = JBLabel(BidaideBundle.message("github.review.hint", if (SystemInfo.isMac) "⌘↩" else "Ctrl+Enter")).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(if (tools.componentCount > 0) JBUI.scale(6) else 0)
            minimumSize = Dimension(0, 0)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            if (embedded) add(ActionLink(BidaideBundle.message("common.cancel")) { onCancel() })
            primaryButton()?.let(::add)
        }
        content.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(tools, BorderLayout.WEST)
            add(hint, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
            // Exactly as wide as the field, so the parts squeeze the hint instead of overlapping each other.
            val size = Dimension(fieldWidth, preferredSize.height)
            preferredSize = size
            maximumSize = size
        }.leftAligned())

        val body = JPanel(BorderLayout(AVATAR_GAP, 0)).apply {
            isOpaque = false
            if (author != null) add(avatar(author), BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }
        if (embedded) {
            add(body, BorderLayout.WEST)
        } else {
            add(card().apply { add(body.leftAligned()) }, BorderLayout.WEST)
        }

        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = textChanged()
            override fun removeUpdate(e: DocumentEvent) = textChanged()
            override fun changedUpdate(e: DocumentEvent) = textChanged()
        })
        textChanged()

        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        submits.firstOrNull()?.let { first ->
            area.registerKeyboardAction({ send(first) }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask), WHEN_FOCUSED)
        }
        area.registerKeyboardAction({ onCancel() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_FOCUSED)
    }

    /** The first submit is the button; the others hang from its arrow, like “Add Single Comment” under “Start Review”. */
    private fun primaryButton(): JButton? {
        val first = actions.firstOrNull() ?: return null
        val button = if (actions.size > 1) JBOptionButton(first, actions.drop(1).toTypedArray<Action>()) else JButton(first)
        button.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        return button
    }

    /** The field grows with its text, from one line up to about ten, then scrolls. */
    private fun textChanged() {
        val empty = area.text.isBlank()
        actions.forEach { it.isEnabled = !empty && !busy }
        val inner = fieldWidth - scroll.insets.left - scroll.insets.right
        area.setSize(inner, Short.MAX_VALUE.toInt())
        val line = area.getFontMetrics(area.font).height
        val wanted = area.preferredSize.height.coerceIn(line * 2 + area.insets.top + area.insets.bottom, line * 10)
        val size = Dimension(fieldWidth, wanted + scroll.insets.top + scroll.insets.bottom)
        if (scroll.preferredSize != size) {
            scroll.preferredSize = size
            scroll.maximumSize = size
            revalidate()
        }
    }

    /** The panel is usually not showing yet when it is built. */
    fun focusLater() {
        SwingUtilities.invokeLater { area.requestFocusInWindow() }
    }

    private fun send(submit: Submit) {
        val body = area.text.trim()
        if (body.isEmpty() || busy) return
        setBusy(true)
        submit.handler(body) { ok -> if (!ok) setBusy(false) }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        area.isEnabled = !value
        textChanged()
    }
}
