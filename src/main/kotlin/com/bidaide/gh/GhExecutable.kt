package com.bidaide.gh

import com.bidaide.settings.BidaideSettings
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.io.File

object GhExecutable {
    // IDEs launched from the Dock do not inherit the shell PATH, so the usual install locations are probed too.
    private val FALLBACK_LOCATIONS = listOf(
        "/opt/homebrew/bin/gh",
        "/usr/local/bin/gh",
        "/usr/bin/gh",
        "/home/linuxbrew/.linuxbrew/bin/gh",
    )

    fun find(): String? {
        val configured = BidaideSettings.getInstance().ghPath
        if (configured.isNotEmpty()) return configured.takeIf { File(it).canExecute() }
        PathEnvironmentVariableUtil.findInPath("gh")?.let { return it.path }
        return FALLBACK_LOCATIONS.firstOrNull { File(it).canExecute() }
    }
}
