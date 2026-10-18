package com.bidaide.adb

import com.intellij.openapi.project.Project
import java.io.File

/** Installs APK files that did not come out of this project's own build: downloads, CI artifacts, release assets. */
object ApkInstaller {
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
}
