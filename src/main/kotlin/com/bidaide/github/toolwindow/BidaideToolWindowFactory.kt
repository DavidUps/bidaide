package com.bidaide.github.toolwindow

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhEvents
import com.bidaide.github.discussions.DiscussionsPanel
import com.bidaide.github.inbox.InboxPanel
import com.bidaide.github.issues.IssuesPanel
import com.bidaide.github.pr.PullRequestsPanel
import com.bidaide.github.projects.ProjectsPanel
import com.bidaide.github.releases.ReleasesPanel
import com.bidaide.github.runs.RunsPanel
import com.bidaide.github.ui.LazyTab
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import javax.swing.JComponent

class BidaideToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val panels: List<Pair<String, JComponent>> = listOf(
            "Pull Requests" to PullRequestsPanel(project), "Issues" to IssuesPanel(project), "Actions" to RunsPanel(project),
            "Releases" to ReleasesPanel(project), BidaideBundle.message("github.inbox.all") to InboxPanel(project), "Discussions" to DiscussionsPanel(project),
            "Projects" to ProjectsPanel(project),
        )
        // Each tab talks to gh on its own, so a tab only loads once it is looked at.
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) (event.content.component as? LazyTab)?.ensureLoaded()
            }
        })
        for ((title, panel) in panels) {
            // Only log tabs opened later can be closed.
            toolWindow.contentManager.addContent(
                factory.createContent(panel, title, false).apply {
                    isCloseable = false
                    // Tabs with spinners or timers stop them when the tool window goes away.
                    (panel as? Disposable)?.let(::setDisposer)
                },
            )
        }
        project.messageBus.connect(toolWindow.disposable).subscribe(GhEvents.TOPIC, GhEvents.Listener {
            panels.forEach { (_, panel) -> (panel as? LazyTab)?.takeIf { it.loaded }?.reload() }
        })
        (panels.first().second as LazyTab).ensureLoaded()
    }
}
