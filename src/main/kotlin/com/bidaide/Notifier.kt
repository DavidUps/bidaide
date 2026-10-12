package com.bidaide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifier {
    private const val GROUP = "Bidaide"

    fun info(project: Project?, title: String, content: String = "") = notify(project, title, content, NotificationType.INFORMATION)

    fun warn(project: Project?, title: String, content: String = "") = notify(project, title, content, NotificationType.WARNING)

    fun error(project: Project?, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.ERROR, *actions)

    private fun notify(project: Project?, title: String, content: String, type: NotificationType, vararg actions: NotificationAction) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, content.trim(), type)
        actions.forEach(notification::addAction)
        notification.notify(project)
    }
}
