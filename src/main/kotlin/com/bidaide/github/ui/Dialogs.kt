package com.bidaide.github.ui

import com.bidaide.BidaideBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

private fun bodyArea() = JBTextArea(10, 60).apply {
    lineWrap = true
    wrapStyleWord = true
}

/** Asks for a free-form Markdown text (comment, review body...). */
class TextPromptDialog(
    project: Project,
    title: String,
    okText: String,
    private val required: Boolean,
) : DialogWrapper(project) {
    private val area = bodyArea()

    val text: String get() = area.text.trim()

    init {
        this.title = title
        setOKButtonText(okText)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(JBScrollPane(area)).align(Align.FILL) }.resizableRow()
        SavedReplies.link(area)?.let { link -> row { cell(link) } }
    }

    override fun getPreferredFocusedComponent() = area

    override fun doValidate(): ValidationInfo? =
        if (required && text.isEmpty()) ValidationInfo(BidaideBundle.message("github.dialog.emptyText"), area) else null
}

/** Title + body form shared by "new pull request" and "new issue"; [extraRows] adds fields below the body. */
open class TitleBodyDialog(
    project: Project,
    dialogTitle: String,
    okText: String,
    initialTitle: String = "",
    initialBody: String = "",
    private val extraRows: Panel.() -> Unit = {},
) : DialogWrapper(project) {
    private val titleField = JBTextField(initialTitle)
    private val area = bodyArea().apply {
        text = initialBody
        caretPosition = 0
    }

    val itemTitle: String get() = titleField.text.trim()
    val body: String get() = area.text.trim()

    init {
        title = dialogTitle
        setOKButtonText(okText)
    }

    /** Subclasses call this once their own fields are initialised. */
    fun build() = init()

    override fun createCenterPanel(): JComponent = panel {
        row(BidaideBundle.message("github.dialog.title")) { cell(titleField).align(AlignX.FILL) }
        row { cell(JBScrollPane(area)).align(Align.FILL) }.resizableRow()
        extraRows()
    }

    override fun getPreferredFocusedComponent() = titleField

    override fun doValidate(): ValidationInfo? =
        if (itemTitle.isEmpty()) ValidationInfo(BidaideBundle.message("github.dialog.titleRequired"), titleField) else null
}

/** Labels, assignees and milestone of an issue or pull request; [reviewersField] is only shown for pull requests. */
class EditMetadataDialog(
    project: Project,
    title: String,
    allLabels: List<String>,
    private val currentLabels: Set<String>,
    private val currentAssignees: Set<String>,
    milestones: List<String>,
    private val currentMilestone: String?,
    private val withReviewers: Boolean,
) : DialogWrapper(project) {
    private val labelsList = CheckBoxList<String>().apply {
        (allLabels + currentLabels).distinct().sorted().forEach { addItem(it, it, it in currentLabels) }
    }
    private val assigneesField = JBTextField(currentAssignees.joinToString(", "))
    private val reviewersField = JBTextField()
    private val milestoneCombo = ComboBox((listOf(NO_MILESTONE) + milestones + listOfNotNull(currentMilestone)).distinct().toTypedArray()).apply {
        selectedItem = currentMilestone ?: NO_MILESTONE
    }

    init {
        this.title = title
        setOKButtonText(BidaideBundle.message("github.dialog.save"))
        init()
    }

    private val selectedLabels: Set<String>
        get() = (0 until labelsList.itemsCount).mapNotNull { labelsList.getItemAt(it)?.takeIf { _ -> labelsList.isItemSelected(it) } }.toSet()

    private val assignees: Set<String> get() = logins(assigneesField.text)

    val addLabels: Set<String> get() = selectedLabels - currentLabels
    val removeLabels: Set<String> get() = currentLabels - selectedLabels
    val addAssignees: Set<String> get() = assignees - currentAssignees
    val removeAssignees: Set<String> get() = currentAssignees - assignees
    val addReviewers: Set<String> get() = logins(reviewersField.text)

    /** Null when unchanged, blank to remove. */
    val milestone: String?
        get() {
            val chosen = (milestoneCombo.item ?: NO_MILESTONE).takeIf { it != NO_MILESTONE }
            return if (chosen == currentMilestone) null else chosen.orEmpty()
        }

    private fun logins(text: String): Set<String> = text.split(',').map { it.trim().removePrefix("@").let { l -> if (l == "me") "@me" else l } }
        .filter { it.isNotEmpty() }.toSet()

    override fun createCenterPanel(): JComponent = panel {
        row(BidaideBundle.message("github.metadata.labels")) { cell(JBScrollPane(labelsList)).align(Align.FILL) }.resizableRow()
        row(BidaideBundle.message("github.metadata.assignees")) { cell(assigneesField).align(AlignX.FILL).comment(BidaideBundle.message("github.metadata.assignees.comment")) }
        if (withReviewers) row(BidaideBundle.message("github.metadata.reviewers")) { cell(reviewersField).align(AlignX.FILL) }
        row(BidaideBundle.message("github.metadata.milestone")) { cell(milestoneCombo) }
    }

    private companion object {
        val NO_MILESTONE: String get() = BidaideBundle.message("github.metadata.noMilestone")
    }
}
