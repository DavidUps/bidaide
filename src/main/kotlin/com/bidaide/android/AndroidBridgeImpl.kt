package com.bidaide.android

import com.bidaide.AndroidBridge
import com.bidaide.adb.AdbFavoritesDialog
import com.bidaide.adb.AdbFavoritesStore
import com.bidaide.adb.ApkInstaller
import com.bidaide.gh.GhPullRequest
import com.intellij.openapi.project.Project
import java.io.File

/** Registered by bidaide-android.xml, so it only exists (and its Android classes only load) in Android Studio. */
class AndroidBridgeImpl : AndroidBridge {
    override fun installApk(project: Project, apk: File) = ApkInstaller.install(project, apk)

    override fun runPullRequestInWorktree(project: Project, pr: GhPullRequest) = PrWorktreeRun.start(project, pr)

    override fun editAdbFavorites(): Boolean = AdbFavoritesDialog.edit(null)

    override fun adbFavoritesCount(): Int = AdbFavoritesStore.ids().size
}
