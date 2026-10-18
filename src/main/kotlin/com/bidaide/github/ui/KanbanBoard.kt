package com.bidaide.github.ui

import com.bidaide.BidaideBundle
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.TransferHandler

/**
 * Columns of cards, as on a GitHub project board. Cards are dragged between columns; what a move means (another
 * status, another label...) is up to [onMove]. The board only shows data: after a move the owner reloads and calls
 * [show] again.
 */
class KanbanBoard(
    /** A card was selected with a click or the keyboard. */
    private val onSelect: (Card) -> Unit,
    /** A card was double-clicked. */
    private val onOpen: (Card) -> Unit,
    /** A card was dropped on another column: (card, key of the column it came from, key of the target column). */
    private val onMove: (Card, String, String) -> Unit,
) : JBScrollPane() {

    class Card(val id: String, val title: String, val subtitle: String, val payload: Any? = null)

    class Column(val key: String, val title: String, val cards: List<Card>)

    /**
     * The columns share the width of the board when they fit, and only scroll sideways when there are too many to
     * show at a readable width.
     */
    private val row = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(COLUMN_WIDTH)
        override fun getScrollableTracksViewportWidth() = (parent?.width ?: 0) >= minimumSize.width
        override fun getScrollableTracksViewportHeight() = true
    }.apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(6)
    }
    private val lists = ArrayList<JBList<Card>>()

    /** Set while the board itself changes selections, so that is not reported as the user selecting a card. */
    private var adjusting = false

    init {
        setViewportView(row)
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
    }

    fun show(columns: List<Column>, emptyText: String = BidaideBundle.message("github.board.empty")) {
        val selected = lists.firstNotNullOfOrNull { it.selectedValue }?.id
        row.removeAll()
        lists.clear()
        if (columns.isEmpty()) row.add(JBLabel(emptyText).apply { foreground = UIUtil.getContextHelpForeground() })
        adjusting = true
        try {
            for (column in columns) {
                row.add(column(column, selected))
                row.add(Box.createHorizontalStrut(JBUI.scale(8)))
            }
        } finally {
            adjusting = false
        }
        row.revalidate()
        row.repaint()
    }

    private fun column(column: Column, selectedId: String?): JComponent {
        val model = DefaultListModel<Card>().apply { column.cards.forEach(::addElement) }
        val list = JBList(model)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = CardRenderer()
        list.emptyText.text = BidaideBundle.message("github.board.dropHere")
        list.dragEnabled = true
        list.dropMode = DropMode.ON
        list.transferHandler = Mover(column.key, list)
        // Cards wrap to the column's width, so their heights have to be measured again when it changes.
        list.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                list.fixedCellHeight = 1
                list.fixedCellHeight = -1
            }
        })
        list.addListSelectionListener { event ->
            if (event.valueIsAdjusting || adjusting) return@addListSelectionListener
            val card = list.selectedValue ?: return@addListSelectionListener
            // One selection for the whole board, not one per column.
            adjusting = true
            try {
                lists.filter { it !== list }.forEach { it.clearSelection() }
            } finally {
                adjusting = false
            }
            onSelect(card)
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let(onOpen)
                return true
            }
        }.installOn(list)
        column.cards.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 && lists.none { other -> other.selectedValue != null } }
            ?.let { list.selectedIndex = it }
        lists += list

        val header = JBLabel("<html><b>${Html.esc(column.title)}</b> <font color=\"gray\">${column.cards.size}</font></html>").apply {
            border = JBUI.Borders.empty(6, 8)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(COLUMN_WIDTH), JBUI.scale(100))
            minimumSize = Dimension(JBUI.scale(MIN_COLUMN_WIDTH), JBUI.scale(80))
            // No limit: with room to spare the columns grow, all by the same amount.
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    /** Title on up to two lines, details below in grey. */
    private class CardRenderer : ListCellRenderer<Card> {
        private val title = JBLabel()
        private val subtitle = JBLabel().apply { font = JBUI.Fonts.smallFont() }
        private val panel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            add(title, BorderLayout.CENTER)
            add(subtitle, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(list: JList<out Card>, value: Card, index: Int, selected: Boolean, focused: Boolean): Component {
            // Wrap to the column as it is now; columns are no longer of one fixed width.
            val textWidth = ((if (list.width > 0) list.width else JBUI.scale(COLUMN_WIDTH)) - JBUI.scale(28)).coerceAtLeast(JBUI.scale(120))
            title.text = "<html><div style=\"width:${(textWidth / 1.34).toInt()}px\">${Html.esc(value.title.take(110))}</div></html>"
            subtitle.text = value.subtitle
            subtitle.isVisible = value.subtitle.isNotBlank()
            val background = if (selected) UIUtil.getListSelectionBackground(list.hasFocus()) else UIUtil.getListBackground()
            val foreground = if (selected) UIUtil.getListSelectionForeground(list.hasFocus()) else UIUtil.getListForeground()
            panel.background = background
            title.foreground = foreground
            subtitle.foreground = if (selected) foreground else UIUtil.getContextHelpForeground()
            panel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(6, 8),
            )
            return panel
        }
    }

    /** Drag and drop between the column lists. The payload names the source column and the card, nothing else. */
    private inner class Mover(private val columnKey: String, private val list: JBList<Card>) : TransferHandler() {
        override fun getSourceActions(component: JComponent) = MOVE

        override fun createTransferable(component: JComponent): Transferable? =
            list.selectedValue?.let { StringSelection("$MARKER$columnKey$SEPARATOR${it.id}") }

        override fun canImport(support: TransferSupport): Boolean = support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val data = runCatching { support.transferable.getTransferData(DataFlavor.stringFlavor) as String }.getOrNull() ?: return false
            // Text dragged in from elsewhere is not a card.
            if (!data.startsWith(MARKER)) return false
            val fromKey = data.removePrefix(MARKER).substringBefore(SEPARATOR)
            val cardId = data.substringAfter(SEPARATOR)
            if (fromKey == columnKey) return false
            val card = lists.asSequence().flatMap { other -> (0 until other.model.size).asSequence().map(other.model::getElementAt) }
                .firstOrNull { it.id == cardId } ?: return false
            onMove(card, fromKey, columnKey)
            return true
        }
    }

    private companion object {
        const val COLUMN_WIDTH = 270
        const val MIN_COLUMN_WIDTH = 210
        const val MARKER = "bidaide-card:"
        const val SEPARATOR = "\n"
    }
}
