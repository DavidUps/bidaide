package com.bidaide.github.issues

import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhIssue
import com.bidaide.gh.display
import com.bidaide.github.ui.KanbanBoard

/** Turns a list of issues into board columns, and a card move into the `gh` command that makes it true. */
object IssueBoard {
    enum class GroupBy(val label: String, val none: String) {
        LABEL("Label", "Sin label"), ASSIGNEE("Asignado", "Sin asignar"), MILESTONE("Milestone", "Sin milestone"), STATE("Estado", "");

        override fun toString() = label
    }

    /** Key of the column for issues that have no value in the grouped dimension. */
    const val NONE = ""
    private const val OPEN = "OPEN"
    private const val CLOSED = "CLOSED"

    private fun values(issue: GhIssue, groupBy: GroupBy): List<String> = when (groupBy) {
        GroupBy.LABEL -> issue.labels.orEmpty().map { it.name }
        GroupBy.ASSIGNEE -> issue.assignees.orEmpty().mapNotNull { it.login }
        GroupBy.MILESTONE -> listOfNotNull(issue.milestone?.title)
        GroupBy.STATE -> listOf(if (issue.isOpen) OPEN else CLOSED)
    }

    /**
     * An issue with several labels (or assignees) shows up in each of their columns. [known] adds columns for values
     * no listed issue has yet, so there is somewhere to drop a card.
     */
    fun columns(issues: List<GhIssue>, groupBy: GroupBy, known: List<String> = emptyList()): List<KanbanBoard.Column> {
        if (groupBy == GroupBy.STATE) {
            return listOf(OPEN to "Abiertas", CLOSED to "Cerradas").map { (key, title) ->
                KanbanBoard.Column(key, title, issues.filter { values(it, groupBy).single() == key }.map { card(it, groupBy) })
            }
        }
        val keys = (issues.flatMap { values(it, groupBy) } + known).distinct().sortedBy { it.lowercase() }
        val none = KanbanBoard.Column(NONE, groupBy.none, issues.filter { values(it, groupBy).isEmpty() }.map { card(it, groupBy) })
        return listOf(none) + keys.map { key ->
            KanbanBoard.Column(key, key, issues.filter { key in values(it, groupBy) }.map { card(it, groupBy) })
        }
    }

    private fun card(issue: GhIssue, groupBy: GroupBy): KanbanBoard.Card {
        // The grouped dimension is already the column title; the card shows the others.
        val details = listOfNotNull(
            issue.author.display(),
            issue.labels?.takeIf { groupBy != GroupBy.LABEL && it.isNotEmpty() }?.joinToString(", ") { it.name },
            issue.assignees?.takeIf { groupBy != GroupBy.ASSIGNEE && it.isNotEmpty() }?.joinToString(", ") { "@${it.display()}" },
            issue.milestone?.title?.takeIf { groupBy != GroupBy.MILESTONE },
        )
        return KanbanBoard.Card(issue.number.toString(), "#${issue.number} ${issue.title}", details.joinToString(" · "), issue)
    }

    /** Dragging from one column to another swaps that one value; the issue keeps its other labels or assignees. */
    fun moveCommand(number: Int, groupBy: GroupBy, from: String, to: String): List<String> {
        val add = listOf(to).filter { it != NONE }
        val remove = listOf(from).filter { it != NONE }
        return when (groupBy) {
            GroupBy.LABEL -> GhCommands.editMetadata("issue", number, add, remove, emptyList(), emptyList(), null)
            GroupBy.ASSIGNEE -> GhCommands.editMetadata("issue", number, emptyList(), emptyList(), add, remove, null)
            GroupBy.MILESTONE -> GhCommands.editMetadata("issue", number, emptyList(), emptyList(), emptyList(), emptyList(), to)
            GroupBy.STATE -> GhCommands.issueSetOpen(number, to == OPEN)
        }
    }

    fun moveDescription(number: Int, groupBy: GroupBy, to: String): String = when (groupBy) {
        GroupBy.STATE -> "Issue #$number ${if (to == OPEN) "reabierta" else "cerrada"}"
        else -> "Issue #$number → ${to.ifEmpty { groupBy.none.lowercase() }}"
    }
}
