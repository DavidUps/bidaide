package com.bidaide

import com.bidaide.gh.GhPullRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File

/**
 * What the GitHub side uses from the Android side. Only bidaide-android.xml registers an implementation, and that
 * file is loaded in Android Studio alone, so in IntelliJ IDEA [instance] is null and those features are hidden.
 * Code outside com.bidaide.adb and com.bidaide.android must reach Android through here: a direct reference to
 * com.android.*, the Android plugin, Gradle or com.bidaide.adb would fail to load where they are missing.
 */
interface AndroidBridge {
    /** Must be called on the EDT: it may ask which device to use. */
    fun installApk(project: Project, apk: File)

    /** Builds [pr] in its own worktree and starts it on a device. Must be called on the EDT. */
    fun runPullRequestInWorktree(project: Project, pr: GhPullRequest)

    /** Shows the ADB favourites dialog and saves the choice; returns true when the user pressed OK. */
    fun editAdbFavorites(): Boolean

    fun adbFavoritesCount(): Int

    companion object {
        val instance: AndroidBridge?
            get() = ApplicationManager.getApplication().getService(AndroidBridge::class.java)

        val isAvailable: Boolean
            get() = instance != null
    }
}
