package com.bidaide.gh

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** Told when what `gh` talks to changed under our feet: another default repository, another account, a new remote. */
object GhEvents {
    fun interface Listener {
        fun gitHubContextChanged()
    }

    val TOPIC: Topic<Listener> = Topic.create("Bidaide GitHub context", Listener::class.java)

    /** Must be called on the EDT. */
    fun fire(project: Project) = project.messageBus.syncPublisher(TOPIC).gitHubContextChanged()
}
