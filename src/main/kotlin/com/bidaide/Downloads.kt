package com.bidaide

import com.bidaide.settings.BidaideSettings
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.io.File

/** Files that land on this machine: captures, CI artifacts and release assets, and the APKs among them. */
object Downloads {
    private const val MAX_OFFERED = 3

    /** Where captures and downloads go: the configured folder, else the Desktop, else the home folder. */
    fun captureDir(): File {
        val configured = BidaideSettings.getInstance().captureDir
        val home = File(System.getProperty("user.home"))
        val dir = if (configured.isNotEmpty()) File(configured) else File(home, "Desktop").takeIf { it.isDirectory } ?: home
        dir.mkdirs()
        return dir
    }

    fun findApks(dir: File): List<File> = dir.walkTopDown().filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }.sortedBy { it.path }.toList()

    /** Tells the user [what] was downloaded into [dir] and, where there is ADB, offers to install the APKs found there. */
    fun offer(project: Project, dir: File, what: String) {
        val android = AndroidBridge.instance
        val apks = if (android == null) emptyList() else findApks(dir)
        val text = if (apks.isEmpty()) BidaideBundle.message("adb.apk.downloaded", what, dir.path) else BidaideBundle.message("adb.apk.downloadedApks", what, apks.size, dir.path)
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Bidaide")
            .createNotification("Bidaide", text, NotificationType.INFORMATION)
        if (android != null) {
            for (apk in apks.take(MAX_OFFERED)) {
                notification.addAction(NotificationAction.createSimple(BidaideBundle.message("adb.apk.install", apk.name)) { android.installApk(project, apk) })
            }
        }
        notification.addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openDirectory(dir) })
        notification.notify(project)
        if (apks.size > MAX_OFFERED) Notifier.info(project, "Bidaide", BidaideBundle.message("adb.apk.more"))
    }
}
