package com.bidaide.github.review

import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.GhReviewThread
import com.bidaide.gh.GhThreadComment
import com.bidaide.gh.ReactionContent
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.Markdown
import com.bidaide.github.ui.SavedReplies
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/** Which threads are expanded / being replied to / edited. Kept outside the panels because those are rebuilt on every change. */
class ThreadUiState {
    val expandedResolved = HashSet<String>()
    var replyingThreadId: String? = null
    var editingCommentId: String? = null
}

internal fun <T : JComponent> T.leftAligned(): T = apply { alignmentX = Component.LEFT_ALIGNMENT }

internal fun card(): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    border = JBUI.Borders.compound(
        JBUI.Borders.empty(4, 8),
        JBUI.Borders.customLine(JBColor.border(), 1),
        JBUI.Borders.empty(6, 8),
    )
    background = UIUtil.getPanelBackground()
}

private fun row(width: Int, vararg components: JComponent): JPanel =
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        isOpaque = false
        components.forEach(::add)
        maximumSize = Dimension(width, preferredSize.height)
    }.leftAligned()

private fun tag(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

object ThreadPanels {
    /**
     * Builds the card for one review thread. Server changes go through [session] (which re-renders via its listeners);
     * [rerender] is for changes that only touch [ui].
     */
    fun build(session: PrReviewSession, thread: GhReviewThread, ui: ThreadUiState, width: Int, rerender: () -> Unit): JComponent {
        val card = card()
        if (thread.isResolved && thread.id !in ui.expandedResolved) {
            val preview = thread.root?.let { "${it.author}: ${it.body.lineSequence().firstOrNull().orEmpty().take(70)}" }.orEmpty()
            card.add(row(width, tag("✓ Resuelto"), JBLabel(preview), ActionLink("Mostrar") {
                ui.expandedResolved += thread.id
                rerender()
            }))
            return wrap(card)
        }

        val tags = listOfNotNull(
            "✓ Resuelto".takeIf { thread.isResolved },
            "Obsoleto (línea original ${thread.originalLine ?: "?"})".takeIf { thread.isOutdated },
        )
        if (tags.isNotEmpty()) card.add(row(width, *tags.map(::tag).toTypedArray()))

        for (comment in thread.comments) addComment(card, session, thread, comment, ui, width, rerender)

        if (ui.replyingThreadId == thread.id) {
            card.add(InputPanel(
                "Respuesta", width, embedded = true,
                onCancel = {
                    ui.replyingThreadId = null
                    rerender()
                },
                submits = arrayOf(InputPanel.Submit(if (session.hasPendingReview) "Añadir a la review" else "Responder") { body, done ->
                    session.mutate("Respondiendo en PR #${session.number}", GhReviewCommands.reply(thread.id, body)) { ok ->
                        if (ok) ui.replyingThreadId = null
                        done(ok)
                    }
                }),
            ).also { it.focusLater() }.leftAligned())
        } else {
            val footer = ArrayList<JComponent>()
            if (thread.canReply) footer += ActionLink("Responder") {
                ui.replyingThreadId = thread.id
                rerender()
            }
            if (!thread.isResolved && thread.canResolve) footer += ActionLink("Resolver hilo") {
                ui.expandedResolved -= thread.id
                session.mutate("Resolviendo hilo", GhReviewCommands.setResolved(thread.id, true))
            }
            if (thread.isResolved && thread.canUnresolve) footer += ActionLink("Reabrir hilo") {
                session.mutate("Reabriendo hilo", GhReviewCommands.setResolved(thread.id, false))
            }
            if (thread.isResolved) footer += ActionLink("Ocultar") {
                ui.expandedResolved -= thread.id
                rerender()
            }
            if (footer.isNotEmpty()) card.add(row(width, *footer.toTypedArray()))
        }
        return wrap(card)
    }

    private fun addComment(
        card: JPanel, session: PrReviewSession, thread: GhReviewThread, comment: GhThreadComment,
        ui: ThreadUiState, width: Int, rerender: () -> Unit,
    ) {
        val header = ArrayList<JComponent>()
        header += JBLabel("<html><b>${Html.esc(comment.author)}</b> <font color=\"gray\">${Html.date(comment.createdAt)}</font></html>")
        if (comment.pending) header += tag("Pendiente")
        if (comment.canUpdate) header += ActionLink("Editar") {
            ui.editingCommentId = comment.id
            rerender()
        }
        if (comment.canDelete) header += ActionLink("Borrar") {
            val answer = Messages.showYesNoDialog(session.project, "¿Borrar este comentario?", "Borrar comentario", Messages.getQuestionIcon())
            if (answer == Messages.YES) session.mutate("Borrando comentario", GhReviewCommands.deleteComment(comment.id))
        }
        card.add(row(width, *header.toTypedArray()))

        if (ui.editingCommentId == comment.id) {
            card.add(InputPanel(
                "Editar comentario", width, embedded = true, initialText = comment.body,
                onCancel = {
                    ui.editingCommentId = null
                    rerender()
                },
                submits = arrayOf(InputPanel.Submit("Guardar") { body, done ->
                    session.mutate("Guardando comentario", GhReviewCommands.updateComment(comment.id, body)) { ok ->
                        if (ok) ui.editingCommentId = null
                        done(ok)
                    }
                }),
            ).also { it.focusLater() }.leftAligned())
            return
        }

        card.add(Markdown.pane(comment.body, width).leftAligned())

        val suggestion = Suggestions.extract(comment.body)
        val extras = ArrayList<JComponent>()
        if (suggestion != null && !thread.isOutdated && !thread.isResolved && thread.line != null && thread.side == "RIGHT") {
            extras += ActionLink("Aplicar sugerencia") { Suggestions.apply(session, thread, suggestion) }
        }
        for (reaction in comment.reactions) {
            val content = ReactionContent.entries.firstOrNull { it.name == reaction.content } ?: continue
            extras += ActionLink("${content.emoji} ${reaction.count}") {
                session.mutate("Reaccionando", GhReviewCommands.setReaction(comment.id, content, !reaction.viewerHasReacted))
            }.apply { toolTipText = if (reaction.viewerHasReacted) "Quitar tu reacción" else "Sumarte" }
        }
        if (!comment.pending) {
            lateinit var add: ActionLink
            add = ActionLink("☺+") {
                JBPopupFactory.getInstance().createPopupChooserBuilder(ReactionContent.entries)
                    .setRenderer(SimpleListCellRenderer.create("") { it.emoji })
                    .setItemChosenCallback { chosen ->
                        val mine = comment.reactions.any { it.content == chosen.name && it.viewerHasReacted }
                        session.mutate("Reaccionando", GhReviewCommands.setReaction(comment.id, chosen, !mine))
                    }
                    .createPopup().showUnderneathOf(add)
            }
            add.toolTipText = "Añadir reacción"
            extras += add
        }
        if (extras.isNotEmpty()) card.add(row(width, *extras.toTypedArray()))
    }

    /** Keeps the card at its natural width instead of stretching across the editor. */
    private fun wrap(card: JPanel): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(card, BorderLayout.WEST)
    }
}

/** Text box with one or more submit buttons and cancel. A submit handler gets the text and reports back whether it worked. */
class InputPanel(
    title: String,
    width: Int,
    embedded: Boolean = false,
    initialText: String = "",
    /** When set, a button inserts a GitHub suggestion block seeded with this text. */
    suggestionSeed: String? = null,
    private val onCancel: () -> Unit,
    vararg submits: Submit,
) : JPanel(BorderLayout()) {

    class Submit(val label: String, val handler: (body: String, done: (Boolean) -> Unit) -> Unit)

    private val area = JBTextArea(initialText, 5, 0).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val buttons = ArrayList<JButton>()

    init {
        isOpaque = false
        val content = if (embedded) {
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        } else {
            card()
        }
        content.add(JBLabel(title).leftAligned())
        content.add(JBScrollPane(area).apply {
            preferredSize = Dimension(width, JBUI.scale(100))
            maximumSize = preferredSize
        }.leftAligned())

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply { isOpaque = false }
        for (submit in submits) {
            bar.add(JButton(submit.label).also { button ->
                buttons += button
                button.addActionListener { send(submit) }
            })
        }
        bar.add(JButton("Cancelar").also { button ->
            buttons += button
            button.addActionListener { onCancel() }
        })
        if (suggestionSeed != null) {
            bar.add(ActionLink("Insertar sugerencia") {
                area.insert("```suggestion\n$suggestionSeed\n```\n", area.caretPosition)
                area.requestFocusInWindow()
            })
        }
        SavedReplies.link(area)?.let(bar::add)
        bar.maximumSize = Dimension(width, bar.preferredSize.height)
        content.add(bar.leftAligned())
        add(content, BorderLayout.WEST)

        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        submits.firstOrNull()?.let { first ->
            area.registerKeyboardAction({ send(first) }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask), WHEN_FOCUSED)
        }
        area.registerKeyboardAction({ onCancel() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_FOCUSED)
    }

    /** The panel is usually not showing yet when it is built. */
    fun focusLater() {
        SwingUtilities.invokeLater { area.requestFocusInWindow() }
    }

    private fun send(submit: Submit) {
        val body = area.text.trim()
        if (body.isEmpty() || !area.isEnabled) return
        setBusy(true)
        submit.handler(body) { ok -> if (!ok) setBusy(false) }
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
        area.isEnabled = !busy
    }
}
