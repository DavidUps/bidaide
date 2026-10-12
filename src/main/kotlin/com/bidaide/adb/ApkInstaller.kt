package com.bidaide.adb

import com.bidaide.Notifier
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.io.File

/** Installs APK files that did not come out of this project's own build: downloads, CI artifacts, release assets. */
object ApkInstaller {
    private const val MAX_OFFERED = 3

    /** Must be called on the EDT: it may ask which device to use. */
    fun install(project: Project, apk: File) {
        AdbTargetResolver.resolve(project, needsPackage = false) { devices, _ ->
            AdbTasks.run(project, "Instalar ${apk.name}", devices, null) { ctx ->
                // -t accepts test-only builds, -d a lower versionCode than the installed one.
                ctx.device.installPackage(apk.path, true, "-t", "-d")
                "${apk.name} instalada"
            }
        }
    }

    fun findApks(dir: File): List<File> = dir.walkTopDown().filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }.sortedBy { it.path }.toList()

    /** Tells the user [what] was downloaded into [dir] and offers to install the APKs found there. */
    fun offer(project: Project, dir: File, what: String) {
        val apks = findApks(dir)
        val text = if (apks.isEmpty()) "$what descargado en ${dir.path}" else "$what descargado: ${apks.size} APK en ${dir.path}"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Bidaide")
            .createNotification("Bidaide", text, NotificationType.INFORMATION)
        for (apk in apks.take(MAX_OFFERED)) {
            notification.addAction(NotificationAction.createSimple("Instalar ${apk.name}") { install(project, apk) })
        }
        notification.addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openDirectory(dir) })
        notification.notify(project)
        if (apks.size > MAX_OFFERED) Notifier.info(project, "Bidaide", "Hay más APK; instala el resto con «ADB: Instalar APK...».")
    }
}
