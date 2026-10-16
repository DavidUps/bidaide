package com.bidaide.settings

import com.bidaide.adb.AdbFavoritesDialog
import com.bidaide.adb.AdbFavoritesStore
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhExecutable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.panel
import javax.swing.JLabel

class BidaideConfigurable : BoundConfigurable("Bidaide") {

    override fun createPanel(): DialogPanel {
        val settings = BidaideSettings.getInstance()
        val status = JLabel(" ")
        val favorites = JLabel(describeFavorites())
        return panel {
            group("GitHub CLI") {
                row("Ruta de gh:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Ejecutable gh"))
                        .bindText(settings::ghPath)
                        .align(AlignX.FILL)
                        .comment("Vacío = detectar automáticamente (PATH, /opt/homebrew/bin, /usr/local/bin).")
                }
                row {
                    button("Comprobar") {
                        status.text = "Comprobando..."
                        val modality = ModalityState.stateForComponent(status)
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val text = describeGhStatus()
                            ApplicationManager.getApplication().invokeLater({ status.text = text }, modality)
                        }
                    }
                    cell(status)
                }
            }
            group("Avisos de GitHub") {
                row { checkBox("Avisar de peticiones de review y de cambios en mis pull requests").bindSelected(settings::notificationsEnabled) }
                row("Consultar cada (minutos):") { intTextField(1..120).bindIntText(settings::pollMinutes) }
            }
            group("Respuestas guardadas") {
                row {
                    textArea().bindText(settings::savedReplies).rows(4).align(AlignX.FILL)
                        .comment("Una por línea: <code>Nombre = texto</code>. <code>\\n</code> es un salto de línea. Aparecen al comentar una PR.")
                }
            }
            group("ADB") {
                row("Favoritos:") {
                    button("Gestionar favoritos...") {
                        if (AdbFavoritesDialog.edit(null)) favorites.text = describeFavorites()
                    }.comment("Aparecen primero en el popup de ADB. Se guardan al aceptar el diálogo.")
                    cell(favorites)
                }
                row("Carpeta de capturas:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Carpeta de capturas"))
                        .bindText(settings::captureDir)
                        .align(AlignX.FILL)
                        .comment("Capturas, grabaciones y datos extraídos. Vacío = Escritorio.")
                }
                row("Comandos propios:") {
                    textArea().bindText(settings::customAdbCommands).rows(6).align(AlignX.FILL)
                        .comment("Uno por línea: <code>Nombre = comando shell</code>. <code>{package}</code> es el id de la app. Aparecen en el popup de ADB.")
                }
                row("Perfiles de dispositivo:") {
                    textArea().bindText(settings::deviceProfiles).rows(4).align(AlignX.FILL)
                        .comment(
                            "Uno por línea: <code>Nombre = clave=valor, clave=valor</code>. Claves: dark, animations, touches, " +
                                "dontkeep, wifi, data (on/off), font (p. ej. 1.3), rotation (portrait/landscape/auto), " +
                                "locale (p. ej. ar, o system). Ejemplo: <code>Accesibilidad = dark=on, font=1.3, animations=off</code>"
                        )
                }
            }
        }
    }

    private fun describeFavorites(): String = when (val count = AdbFavoritesStore.ids().size) {
        0 -> "Ninguno"
        1 -> "1 operación"
        else -> "$count operaciones"
    }

    private fun describeGhStatus(): String {
        val path = GhExecutable.find() ?: return "gh no encontrado. Instálalo con: brew install gh"
        return try {
            val user = GhCli.run(null, listOf("api", "user", "--jq", ".login")).trim()
            "OK — $path, sesión de $user"
        } catch (e: Exception) {
            "$path encontrado, pero sin sesión. Ejecuta: gh auth login"
        }
    }
}
