package com.bidaide.github.pr

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCheck
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The checks of a pull request in its preview, each one a link to its page with its state as icon: the platform
 * spinner while it runs, as in the Actions tab. A spinner repaints only its own link, and only while it is showing.
 */
class PullRequestChecks(checks: List<GhCheck>) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(2))) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 0, 0)
        add(JBLabel(BidaideBundle.message("github.html.checks")).apply { foreground = UIUtil.getContextHelpForeground() })
        for (check in checks) {
            val url = check.url
            val entry = if (url.isNullOrBlank()) {
                JBLabel(check.displayName, iconOf(check), SwingConstants.LEFT)
            } else {
                ActionLink(check.displayName) { BrowserUtil.browse(url) }.apply { icon = iconOf(check) }
            }
            entry.toolTipText = check.result.lowercase()
            add(entry)
        }
    }

    companion object {
        fun iconOf(check: GhCheck): Icon = when {
            check.running -> AnimatedIcon.Default.INSTANCE
            check.failed -> AllIcons.RunConfigurations.TestFailed
            check.result == "SUCCESS" -> AllIcons.RunConfigurations.TestPassed
            else -> AllIcons.RunConfigurations.TestIgnored
        }
    }
}
