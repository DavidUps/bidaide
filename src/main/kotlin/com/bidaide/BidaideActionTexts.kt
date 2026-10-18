package com.bidaide

import com.bidaide.settings.BidaideLanguage
import com.bidaide.settings.BidaideSettings
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager

/**
 * The platform resolves the texts of the actions declared in plugin.xml in the IDE's language. When another
 * language is chosen in Bidaide's settings, this rewrites their template presentations, which menus, popups,
 * Find Action and the keymap are built from. Menus the IDE has already shown may keep the old texts until it
 * restarts.
 */
object BidaideActionTexts {
    private const val ID_PREFIX = "Bidaide."

    fun apply() {
        val manager = ActionManager.getInstance()
        for (id in manager.getActionIdList(ID_PREFIX)) {
            val action = manager.getAction(id) ?: continue
            val prefix = if (action is ActionGroup) "group.$id" else "action.$id"
            val presentation = action.templatePresentation
            text("$prefix.text")?.let { presentation.text = it }
            text("$prefix.description")?.let { presentation.description = it }
        }
    }

    private fun text(key: String): String? = if (BidaideBundle.containsKey(key)) BidaideBundle.message(key) else null

    /** Applies the chosen language before the first frame, and so before any menu is built. */
    class Startup : AppLifecycleListener {
        override fun appFrameCreated(commandLineArgs: List<String>) {
            if (BidaideSettings.getInstance().language != BidaideLanguage.AUTO) apply()
        }
    }
}
