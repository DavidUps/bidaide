package com.bidaide.adb

import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.debug.AndroidDebugger
import com.android.tools.idea.execution.common.debug.utils.AndroidConnectDebugger
import com.bidaide.BidaideBundle
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File
import java.util.function.Supplier

class AdbContext(val project: Project, val device: IDevice, private val pkg: String?) {
    val packageName: String get() = pkg ?: error("Operation declared needsPackage = false")

    /** Set by operations that produce a local file, so the notification can offer to reveal it. */
    var resultFile: File? = null
}

/**
 * Every quick ADB operation offered by the plugin. [run] executes on a background thread and returns the
 * message shown to the user on success; failures are reported by throwing.
 */
enum class AdbOperation(private val text: Supplier<String>, val needsPackage: Boolean = true, val run: (AdbContext) -> String) {
    UNINSTALL(BidaideBundle.lazyMessage("adb.op.uninstall"), run = { ctx ->
        AdbShell.uninstall(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.uninstall.done", ctx.packageName)
    }),
    KILL(BidaideBundle.lazyMessage("adb.op.kill"), run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.kill.done", ctx.packageName)
    }),
    START(BidaideBundle.lazyMessage("adb.op.start"), run = { ctx ->
        AdbShell.start(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.start.done", ctx.packageName)
    }),
    RESTART(BidaideBundle.lazyMessage("adb.op.restart"), run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.restart.done", ctx.packageName)
    }),
    CLEAR_DATA(BidaideBundle.lazyMessage("adb.op.clearData"), run = { ctx ->
        AdbShell.clearData(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.clearData.done", ctx.packageName)
    }),
    CLEAR_DATA_RESTART(BidaideBundle.lazyMessage("adb.op.clearDataRestart"), run = { ctx ->
        AdbShell.clearData(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.clearDataRestart.done", ctx.packageName)
    }),
    CLEAR_CACHE(BidaideBundle.lazyMessage("adb.op.clearCache"), run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.clearCache(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.clearCache.done", ctx.packageName)
    }),
    REINSTALL(BidaideBundle.lazyMessage("adb.op.reinstall"), run = { ctx ->
        val settings = RunManager.getInstance(ctx.project).selectedConfiguration
            ?: throw AdbException(BidaideBundle.message("adb.op.reinstall.noConfiguration"))
        if (AdbShell.isInstalled(ctx.device, ctx.packageName)) AdbShell.uninstall(ctx.device, ctx.packageName)
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
        BidaideBundle.message("adb.op.reinstall.done", ctx.packageName, settings.name)
    }),
    START_DEBUG(BidaideBundle.lazyMessage("adb.op.startDebug"), run = { ctx ->
        AdbShell.start(ctx.device, ctx.packageName, waitForDebugger = true)
        attachDebugger(ctx)
        BidaideBundle.message("adb.op.startDebug.done", ctx.packageName)
    }),
    RESTART_DEBUG(BidaideBundle.lazyMessage("adb.op.restartDebug"), run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName, waitForDebugger = true)
        attachDebugger(ctx)
        BidaideBundle.message("adb.op.restartDebug.done", ctx.packageName)
    }),
    CLEAR_DATA_RESTART_DEBUG(BidaideBundle.lazyMessage("adb.op.clearDataRestartDebug"), run = { ctx ->
        AdbShell.clearData(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName, waitForDebugger = true)
        attachDebugger(ctx)
        BidaideBundle.message("adb.op.clearDataRestartDebug.done", ctx.packageName)
    }),
    GRANT_PERMISSIONS(BidaideBundle.lazyMessage("adb.op.grantPermissions"), run = { ctx ->
        BidaideBundle.message("adb.op.grantPermissions.done", AdbShell.setPermissions(ctx.device, ctx.packageName, grant = true), ctx.packageName)
    }),
    REVOKE_PERMISSIONS(BidaideBundle.lazyMessage("adb.op.revokePermissions"), run = { ctx ->
        BidaideBundle.message("adb.op.revokePermissions.done", AdbShell.setPermissions(ctx.device, ctx.packageName, grant = false), ctx.packageName)
    }),
    REVOKE_PERMISSIONS_RESTART(BidaideBundle.lazyMessage("adb.op.revokePermissionsRestart"), run = { ctx ->
        val count = AdbShell.setPermissions(ctx.device, ctx.packageName, grant = false)
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.revokePermissionsRestart.done", count, ctx.packageName)
    }),
    WIFI_ON(BidaideBundle.lazyMessage("adb.op.wifiOn"), needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "wifi", true)
        BidaideBundle.message("adb.op.wifiOn.done")
    }),
    WIFI_OFF(BidaideBundle.lazyMessage("adb.op.wifiOff"), needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "wifi", false)
        BidaideBundle.message("adb.op.wifiOff.done")
    }),
    MOBILE_DATA_ON(BidaideBundle.lazyMessage("adb.op.mobileDataOn"), needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "data", true)
        BidaideBundle.message("adb.op.mobileDataOn.done")
    }),
    MOBILE_DATA_OFF(BidaideBundle.lazyMessage("adb.op.mobileDataOff"), needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "data", false)
        BidaideBundle.message("adb.op.mobileDataOff.done")
    }),

    // --- Captures ---
    SCREENSHOT(BidaideBundle.lazyMessage("adb.op.screenshot"), needsPackage = false, run = { ctx ->
        val file = AdbFiles.screenshot(ctx.device)
        ctx.resultFile = file
        BidaideBundle.message("adb.op.screenshot.done", file.name)
    }),
    RECORD_TOGGLE(BidaideBundle.lazyMessage("adb.op.record"), needsPackage = false, run = { ctx ->
        if (AdbFiles.isRecording(ctx.device)) {
            val file = AdbFiles.stopRecording(ctx.device)
            ctx.resultFile = file
            BidaideBundle.message("adb.op.record.saved", file.name)
        } else {
            AdbFiles.startRecording(ctx.device)
            BidaideBundle.message("adb.op.record.started")
        }
    }),

    // --- Device settings ---
    DARK_MODE(BidaideBundle.lazyMessage("adb.op.darkMode"), needsPackage = false, run = { ctx -> if (AdbShell.toggleNightMode(ctx.device)) BidaideBundle.message("adb.op.darkMode.on") else BidaideBundle.message("adb.op.darkMode.off") }),
    FONT_SCALE(BidaideBundle.lazyMessage("adb.op.fontScale"), needsPackage = false, run = { ctx -> BidaideBundle.message("adb.op.fontScale.done", AdbShell.toggleFontScale(ctx.device)) }),
    ANIMATIONS(BidaideBundle.lazyMessage("adb.op.animations"), needsPackage = false, run = { ctx ->
        if (AdbShell.toggleAnimations(ctx.device)) BidaideBundle.message("adb.op.animations.on") else BidaideBundle.message("adb.op.animations.off")
    }),
    DONT_KEEP_ACTIVITIES(BidaideBundle.lazyMessage("adb.op.dontKeepActivities"), needsPackage = false, run = { ctx ->
        if (AdbShell.toggleSetting(ctx.device, "global", "always_finish_activities")) BidaideBundle.message("adb.op.dontKeepActivities.on") else BidaideBundle.message("adb.op.dontKeepActivities.off")
    }),
    SHOW_TOUCHES(BidaideBundle.lazyMessage("adb.op.showTouches"), needsPackage = false, run = { ctx ->
        if (AdbShell.toggleSetting(ctx.device, "system", "show_touches")) BidaideBundle.message("adb.op.showTouches.on") else BidaideBundle.message("adb.op.showTouches.off")
    }),

    ROTATE(BidaideBundle.lazyMessage("adb.op.rotate"), needsPackage = false, run = { ctx ->
        if (AdbShell.toggleRotation(ctx.device)) BidaideBundle.message("adb.op.rotate.on") else BidaideBundle.message("adb.op.rotate.off")
    }),
    AUTO_ROTATE(BidaideBundle.lazyMessage("adb.op.autoRotate"), needsPackage = false, run = { ctx ->
        AdbShell.setRotation(ctx.device, null)
        BidaideBundle.message("adb.op.autoRotate.done")
    }),

    // --- Simulated conditions ---
    AIRPLANE_MODE(BidaideBundle.lazyMessage("adb.op.airplaneMode"), needsPackage = false, run = { ctx -> if (AdbShell.toggleAirplaneMode(ctx.device)) BidaideBundle.message("adb.op.airplaneMode.on") else BidaideBundle.message("adb.op.airplaneMode.off") }),
    BATTERY_LOW(BidaideBundle.lazyMessage("adb.op.batteryLow"), needsPackage = false, run = { ctx ->
        AdbShell.run(ctx.device, "dumpsys battery unplug")
        AdbShell.run(ctx.device, "dumpsys battery set level 5")
        BidaideBundle.message("adb.op.batteryLow.done")
    }),
    DOZE_ON(BidaideBundle.lazyMessage("adb.op.doze"), needsPackage = false, run = { ctx ->
        AdbShell.run(ctx.device, "dumpsys battery unplug")
        AdbShell.run(ctx.device, "dumpsys deviceidle force-idle")
        BidaideBundle.message("adb.op.doze.done")
    }),
    POWER_RESET(BidaideBundle.lazyMessage("adb.op.powerReset"), needsPackage = false, run = { ctx ->
        AdbShell.run(ctx.device, "dumpsys deviceidle unforce")
        AdbShell.run(ctx.device, "dumpsys battery reset")
        BidaideBundle.message("adb.op.powerReset.done")
    }),
    SLOW_NETWORK(BidaideBundle.lazyMessage("adb.op.slowNetwork"), needsPackage = false, run = { ctx ->
        emulatorConsole(ctx, "network", "speed", "edge")
        emulatorConsole(ctx, "network", "delay", "edge")
        BidaideBundle.message("adb.op.slowNetwork.done")
    }),
    NORMAL_NETWORK(BidaideBundle.lazyMessage("adb.op.normalNetwork"), needsPackage = false, run = { ctx ->
        emulatorConsole(ctx, "network", "speed", "full")
        emulatorConsole(ctx, "network", "delay", "none")
        BidaideBundle.message("adb.op.normalNetwork.done")
    }),
    KILL_IN_BACKGROUND(BidaideBundle.lazyMessage("adb.op.killInBackground"), run = { ctx ->
        AdbShell.killInBackground(ctx.device, ctx.packageName)
        BidaideBundle.message("adb.op.killInBackground.done", ctx.packageName)
    }),

    // --- App data ---
    PULL_DATA(BidaideBundle.lazyMessage("adb.op.pullData"), run = { ctx ->
        val file = AdbFiles.pullAppData(ctx.device, ctx.packageName)
        ctx.resultFile = file
        BidaideBundle.message("adb.op.pullData.done", ctx.packageName, file.name)
    }),
    BACKUP(BidaideBundle.lazyMessage("adb.op.backup"), run = { ctx ->
        val file = AdbFiles.backup(ctx.device, ctx.packageName)
        ctx.resultFile = file
        BidaideBundle.message("adb.op.backup.done", ctx.packageName, file.name)
    }),
    ;

    val label: String get() = text.get()
}

/** Sends a command to the emulator console (`adb emu ...`); physical devices have no such thing. */
internal fun emulatorConsole(ctx: AdbContext, vararg command: String): String {
    if (!ctx.device.isEmulator) throw AdbException(BidaideBundle.message("adb.emulatorOnly"))
    return AdbFiles.adb(ctx.project, "-s", ctx.device.serialNumber, "emu", *command)
}

private const val CLIENT_WAIT_MILLIS = 15_000L

private fun attachDebugger(ctx: AdbContext) {
    val client = waitForClient(ctx.device, ctx.packageName)
        ?: throw AdbException(BidaideBundle.message("adb.debugger.timeout", ctx.packageName))
    val debugger = AndroidDebugger.EP_NAME.extensionList.firstOrNull { it.supportsProject(ctx.project) && it.shouldBeDefault() }
        ?: AndroidDebugger.EP_NAME.extensionList.firstOrNull { it.supportsProject(ctx.project) }
        ?: throw AdbException(BidaideBundle.message("adb.debugger.none"))
    ApplicationManager.getApplication().invokeLater {
        AndroidConnectDebugger.closeOldSessionAndRun(ctx.project, debugger, client, null)
    }
}

private fun waitForClient(device: IDevice, packageName: String): Client? {
    val deadline = System.currentTimeMillis() + CLIENT_WAIT_MILLIS
    while (System.currentTimeMillis() < deadline) {
        device.getClient(packageName)?.let { return it }
        Thread.sleep(200)
    }
    return null
}
