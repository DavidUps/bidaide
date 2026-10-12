package com.bidaide.adb

import com.android.ddmlib.IDevice
import com.bidaide.Notifier
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import org.xml.sax.InputSource
import java.awt.Dimension
import java.io.StringReader
import javax.swing.JComponent
import javax.xml.parsers.DocumentBuilderFactory

/** Reads and rewrites the SharedPreferences XML files of a debuggable app. Blocking. */
object AdbPrefs {
    private const val REMOTE = "/data/local/tmp/bidaide-prefs.xml"
    private val SAFE_NAME = Regex("""[A-Za-z0-9._-]+""")

    fun list(device: IDevice, packageName: String): List<String> {
        val out = AdbShell.run(device, "run-as $packageName ls shared_prefs")
        AdbShell.checkRunAs(out, packageName)
        return parseList(out)
    }

    /** `ls` prints an error line instead of names when the app never wrote any preferences. */
    internal fun parseList(ls: String): List<String> =
        ls.lineSequence().map { it.trim() }.filter { it.endsWith(".xml") && SAFE_NAME.matches(it) }.sorted().toList()

    fun read(device: IDevice, packageName: String, name: String): String {
        val out = AdbShell.run(device, "run-as $packageName cat shared_prefs/${requireSafe(name)}")
        AdbShell.checkRunAs(out, packageName)
        return out
    }

    /**
     * Replaces the file and restarts the app. The app is stopped first: a running app keeps preferences in memory
     * and would write its own copy back over ours.
     */
    fun write(device: IDevice, packageName: String, name: String, xml: String) {
        val local = FileUtil.createTempFile("bidaide-prefs", ".xml", true)
        try {
            local.writeText(xml)
            AdbShell.forceStop(device, packageName)
            device.pushFile(local.path, REMOTE)
            // The app's user cannot read /data/local/tmp, so the shell user pipes the content in.
            val out = AdbShell.run(device, "cat $REMOTE | run-as $packageName sh -c 'cat > shared_prefs/${requireSafe(name)}'")
            AdbShell.checkRunAs(out, packageName)
            if (out.isNotEmpty()) throw AdbException(out)
        } finally {
            AdbShell.run(device, "rm -f $REMOTE")
            FileUtil.delete(local)
        }
        AdbShell.start(device, packageName)
    }

    private fun requireSafe(name: String): String =
        name.takeIf(SAFE_NAME::matches) ?: throw AdbException("Nombre de archivo no admitido: $name")

    /** Android refuses to load a malformed preferences file and silently starts from empty, so check before writing. */
    fun xmlProblem(xml: String): String? = try {
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().apply { setErrorHandler(null) }.parse(InputSource(StringReader(xml)))
        null
    } catch (e: Exception) {
        e.message ?: "XML no válido"
    }
}

/** Shows a SharedPreferences file of the app for editing and writes it back. */
class AdbPrefsAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AdbTargetResolver.resolve(project, needsPackage = true) { devices, pkg ->
            val packageName = pkg ?: return@resolve
            val device = devices.first()
            AdbTasks.fetch(project, "Preferencias de $packageName", work = { AdbPrefs.list(device, packageName) }) { files ->
                when (files.size) {
                    0 -> Notifier.info(project, "ADB", "$packageName no tiene SharedPreferences guardadas en ${AdbTargetResolver.deviceLabel(device)}.")
                    1 -> edit(project, device, packageName, files.single())
                    else -> JBPopupFactory.getInstance().createPopupChooserBuilder(files)
                        .setTitle("SharedPreferences de $packageName")
                        .setNamerForFiltering { it }
                        .setItemChosenCallback { edit(project, device, packageName, it) }
                        .createPopup()
                        .showCenteredInCurrentWindow(project)
                }
            }
        }
    }

    private fun edit(project: Project, device: IDevice, packageName: String, name: String) {
        AdbTasks.fetch(project, "Leyendo $name", work = { AdbPrefs.read(device, packageName, name) }) { xml ->
            val dialog = PrefsDialog(project, "$name — $packageName", xml)
            if (!dialog.showAndGet() || dialog.xml == xml) return@fetch
            AdbTasks.run(project, "Guardar $name", listOf(device), packageName) { ctx ->
                AdbPrefs.write(ctx.device, ctx.packageName, name, dialog.xml)
                "$name guardado; ${ctx.packageName} reiniciada"
            }
        }
    }
}

private class PrefsDialog(project: Project, dialogTitle: String, initial: String) : DialogWrapper(project) {
    private val editor = EditorTextField(
        EditorFactory.getInstance().createDocument(initial.replace("\r\n", "\n")), project,
        FileTypeManager.getInstance().getFileTypeByExtension("xml"), false, false,
    ).apply {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(460))
        addSettingsProvider {
            it.setVerticalScrollbarVisible(true)
            it.setHorizontalScrollbarVisible(true)
            it.settings.isLineNumbersShown = true
        }
    }

    val xml: String get() = editor.text.trim()

    init {
        title = dialogTitle
        setOKButtonText("Guardar y reiniciar app")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(editor).align(Align.FILL) }.resizableRow()
        row { comment("Al guardar se detiene la app, se sustituye el archivo y se vuelve a iniciar.") }
    }

    override fun getPreferredFocusedComponent() = editor

    override fun doValidate(): ValidationInfo? = AdbPrefs.xmlProblem(xml)?.let { ValidationInfo("XML no válido: $it", editor) }
}
