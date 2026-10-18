package com.bidaide.github.ui

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCli
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLoadingPanel
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import javax.swing.JComponent

/**
 * The platform spinner over [content] while `gh` fetches what it shows. Nothing is dimmed or hidden: on a reload
 * what was there before stays readable underneath, and the spinner fades out when the new data arrives.
 */
class GhLoadingPanel(content: JComponent, parent: Disposable) {
    val component = JBLoadingPanel(BorderLayout(), parent).apply {
        setLoadingText(BidaideBundle.message("github.list.loading"))
        add(content, BorderLayout.CENTER)
    }

    /** Requests still running; the spinner stays while any of them does. */
    private var pending = 0

    init {
        // The spinner only runs while it can be seen: switching to another tab or hiding the tool window pauses it.
        component.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) update()
        }
    }

    /** [GhCli.async] with the spinner shown for as long as [work] runs. */
    fun <T> async(project: Project, title: String, onFailure: () -> Unit = {}, work: () -> T, onSuccess: (T) -> Unit) {
        pending++
        update()
        GhCli.async(
            project, title,
            onFailure = {
                finished()
                onFailure()
            },
            work = work,
        ) {
            finished()
            onSuccess(it)
        }
    }

    private fun finished() {
        pending--
        update()
    }

    private fun update() {
        if (pending > 0 && component.isShowing) component.startLoading() else component.stopLoading()
    }
}
