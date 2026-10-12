package com.bidaide.adb

import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.debug.AndroidDebugger
import com.android.tools.idea.execution.common.debug.utils.AndroidConnectDebugger
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File

class AdbContext(val project: Project, val device: IDevice, private val pkg: String?) {
    val packageName: String get() = pkg ?: error("Operation declared needsPackage = false")

    /** Set by operations that produce a local file, so the notification can offer to reveal it. */
    var resultFile: File? = null
}

private fun onOff(on: Boolean) = if (on) "activado" else "desactivado"

/**
 * Every quick ADB operation offered by the plugin. [run] executes on a background thread and returns the
 * message shown to the user on success; failures are reported by throwing.
 */
enum class AdbOperation(val label: String, val needsPackage: Boolean = true, val run: (AdbContext) -> String) {
    UNINSTALL("Desinstalar app", run = { ctx ->
        AdbShell.uninstall(ctx.device, ctx.packageName)
        "${ctx.packageName} desinstalada"
    }),
    KILL("Matar app", run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        "${ctx.packageName} detenida"
    }),
    START("Iniciar app", run = { ctx ->
        AdbShell.start(ctx.device, ctx.packageName)
        "${ctx.packageName} iniciada"
    }),
    RESTART("Reiniciar app", run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName)
        "${ctx.packageName} reiniciada"
    }),
    CLEAR_DATA("Limpiar datos", run = { ctx ->
        AdbShell.clearData(ctx.device, ctx.packageName)
        "Datos de ${ctx.packageName} borrados"
    }),
    CLEAR_DATA_RESTART("Limpiar datos y reiniciar", run = { ctx ->
        AdbShell.clearData(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName)
        "Datos de ${ctx.packageName} borrados y app reiniciada"
    }),
    CLEAR_CACHE("Limpiar caché", run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.clearCache(ctx.device, ctx.packageName)
        "Caché de ${ctx.packageName} borrada"
    }),
    REINSTALL("Reinstalar app", run = { ctx ->
        val settings = RunManager.getInstance(ctx.project).selectedConfiguration
            ?: throw AdbException("No hay ninguna run configuration seleccionada")
        if (AdbShell.isInstalled(ctx.device, ctx.packageName)) AdbShell.uninstall(ctx.device, ctx.packageName)
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
        "${ctx.packageName} desinstalada; lanzando «${settings.name}»"
    }),
    START_DEBUG("Iniciar app con depurador", run = { ctx ->
        AdbShell.start(ctx.device, ctx.packageName, waitForDebugger = true)
        attachDebugger(ctx)
        "${ctx.packageName} iniciada con depurador"
    }),
    RESTART_DEBUG("Reiniciar app con depurador", run = { ctx ->
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName, waitForDebugger = true)
        attachDebugger(ctx)
        "${ctx.packageName} reiniciada con depurador"
    }),
    CLEAR_DATA_RESTART_DEBUG("Limpiar datos y reiniciar con depurador", run = { ctx ->
        AdbShell.clearData(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName, waitForDebugger = true)
        attachDebugger(ctx)
        "Datos borrados; ${ctx.packageName} reiniciada con depurador"
    }),
    GRANT_PERMISSIONS("Conceder permisos", run = { ctx ->
        "${AdbShell.setPermissions(ctx.device, ctx.packageName, grant = true)} permisos concedidos a ${ctx.packageName}"
    }),
    REVOKE_PERMISSIONS("Revocar permisos", run = { ctx ->
        "${AdbShell.setPermissions(ctx.device, ctx.packageName, grant = false)} permisos revocados a ${ctx.packageName}"
    }),
    REVOKE_PERMISSIONS_RESTART("Revocar permisos y reiniciar", run = { ctx ->
        val count = AdbShell.setPermissions(ctx.device, ctx.packageName, grant = false)
        AdbShell.forceStop(ctx.device, ctx.packageName)
        AdbShell.start(ctx.device, ctx.packageName)
        "$count permisos revocados; ${ctx.packageName} reiniciada"
    }),
    WIFI_ON("Activar Wi-Fi", needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "wifi", true)
        "Wi-Fi activado"
    }),
    WIFI_OFF("Desactivar Wi-Fi", needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "wifi", false)
        "Wi-Fi desactivado"
    }),
    MOBILE_DATA_ON("Activar datos móviles", needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "data", true)
        "Datos móviles activados"
    }),
    MOBILE_DATA_OFF("Desactivar datos móviles", needsPackage = false, run = { ctx ->
        AdbShell.setService(ctx.device, "data", false)
        "Datos móviles desactivados"
    }),

    // --- Captures ---
    SCREENSHOT("Captura de pantalla", needsPackage = false, run = { ctx ->
        val file = AdbFiles.screenshot(ctx.device)
        ctx.resultFile = file
        "Captura guardada en ${file.name} y copiada al portapapeles"
    }),
    RECORD_TOGGLE("Iniciar / detener grabación de pantalla", needsPackage = false, run = { ctx ->
        if (AdbFiles.isRecording(ctx.device)) {
            val file = AdbFiles.stopRecording(ctx.device)
            ctx.resultFile = file
            "Grabación guardada en ${file.name}"
        } else {
            AdbFiles.startRecording(ctx.device)
            "Grabando pantalla (máx. 3 min). Repite la operación para detener."
        }
    }),

    // --- Device settings ---
    DARK_MODE("Alternar modo oscuro", needsPackage = false, run = { ctx -> "Modo oscuro ${onOff(AdbShell.toggleNightMode(ctx.device))}" }),
    FONT_SCALE("Alternar fuente grande", needsPackage = false, run = { ctx -> "Escala de fuente: ${AdbShell.toggleFontScale(ctx.device)}" }),
    ANIMATIONS("Alternar animaciones", needsPackage = false, run = { ctx ->
        "Animaciones ${if (AdbShell.toggleAnimations(ctx.device)) "activadas" else "desactivadas"}"
    }),
    DONT_KEEP_ACTIVITIES("Alternar «No mantener actividades»", needsPackage = false, run = { ctx ->
        "«No mantener actividades» ${onOff(AdbShell.toggleSetting(ctx.device, "global", "always_finish_activities"))}"
    }),
    SHOW_TOUCHES("Alternar mostrar toques", needsPackage = false, run = { ctx ->
        "Mostrar toques ${onOff(AdbShell.toggleSetting(ctx.device, "system", "show_touches"))}"
    }),

    ROTATE("Girar pantalla (vertical / horizontal)", needsPackage = false, run = { ctx ->
        "Pantalla en ${if (AdbShell.toggleRotation(ctx.device)) "horizontal" else "vertical"} (rotación automática desactivada)"
    }),
    AUTO_ROTATE("Activar rotación automática", needsPackage = false, run = { ctx ->
        AdbShell.setRotation(ctx.device, null)
        "Rotación automática activada"
    }),

    // --- Simulated conditions ---
    AIRPLANE_MODE("Alternar modo avión", needsPackage = false, run = { ctx -> "Modo avión ${onOff(AdbShell.toggleAirplaneMode(ctx.device))}" }),
    BATTERY_LOW("Simular batería baja (5 %)", needsPackage = false, run = { ctx ->
        AdbShell.run(ctx.device, "dumpsys battery unplug")
        AdbShell.run(ctx.device, "dumpsys battery set level 5")
        "Batería simulada al 5 % y desenchufada"
    }),
    DOZE_ON("Forzar modo Doze", needsPackage = false, run = { ctx ->
        AdbShell.run(ctx.device, "dumpsys battery unplug")
        AdbShell.run(ctx.device, "dumpsys deviceidle force-idle")
        "Dispositivo en Doze"
    }),
    POWER_RESET("Restaurar batería y Doze", needsPackage = false, run = { ctx ->
        AdbShell.run(ctx.device, "dumpsys deviceidle unforce")
        AdbShell.run(ctx.device, "dumpsys battery reset")
        "Batería y Doze restaurados"
    }),
    SLOW_NETWORK("Simular red lenta (emulador)", needsPackage = false, run = { ctx ->
        emulatorConsole(ctx, "network", "speed", "edge")
        emulatorConsole(ctx, "network", "delay", "edge")
        "Red del emulador limitada a EDGE (lenta y con latencia)"
    }),
    NORMAL_NETWORK("Restaurar velocidad de red (emulador)", needsPackage = false, run = { ctx ->
        emulatorConsole(ctx, "network", "speed", "full")
        emulatorConsole(ctx, "network", "delay", "none")
        "Red del emulador sin límites"
    }),
    KILL_IN_BACKGROUND("Matar proceso en segundo plano", run = { ctx ->
        AdbShell.killInBackground(ctx.device, ctx.packageName)
        "Proceso de ${ctx.packageName} eliminado en segundo plano. Ábrela desde recientes para probar la restauración de estado."
    }),

    // --- App data ---
    PULL_DATA("Extraer bases de datos y preferencias", run = { ctx ->
        val file = AdbFiles.pullAppData(ctx.device, ctx.packageName)
        ctx.resultFile = file
        "Datos de ${ctx.packageName} copiados a ${file.name}"
    }),
    BACKUP("Guardar estado de la app", run = { ctx ->
        val file = AdbFiles.backup(ctx.device, ctx.packageName)
        ctx.resultFile = file
        "Estado de ${ctx.packageName} guardado en ${file.name}"
    }),
}

/** Sends a command to the emulator console (`adb emu ...`); physical devices have no such thing. */
internal fun emulatorConsole(ctx: AdbContext, vararg command: String): String {
    if (!ctx.device.isEmulator) throw AdbException("Solo disponible en emuladores")
    return AdbFiles.adb(ctx.project, "-s", ctx.device.serialNumber, "emu", *command)
}

private const val CLIENT_WAIT_MILLIS = 15_000L

private fun attachDebugger(ctx: AdbContext) {
    val client = waitForClient(ctx.device, ctx.packageName)
        ?: throw AdbException("El proceso de ${ctx.packageName} no apareció a tiempo para adjuntar el depurador")
    val debugger = AndroidDebugger.EP_NAME.extensionList.firstOrNull { it.supportsProject(ctx.project) && it.shouldBeDefault() }
        ?: AndroidDebugger.EP_NAME.extensionList.firstOrNull { it.supportsProject(ctx.project) }
        ?: throw AdbException("No hay depurador Android disponible para este proyecto")
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
