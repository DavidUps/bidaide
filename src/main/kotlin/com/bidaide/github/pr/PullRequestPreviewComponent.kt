package com.bidaide.github.pr

import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhUser
import com.bidaide.gh.display
import com.bidaide.github.ui.Html
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.net.URI
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JEditorPane
import javax.swing.JPanel

class PullRequestPreviewComponent(pr: GhPullRequest) : JBScrollPane() {
    init {
        border = JBUI.Borders.empty()
        viewport.view = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            val identities = identitiesPanel(pr)
            if (identities.componentCount > 0) add(identities, BorderLayout.NORTH)
            add(JEditorPane().apply {
                editorKit = HTMLEditorKitBuilder.simple()
                isEditable = false
                addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
                text = Html.page(PullRequestHtml.render(pr))
                caretPosition = 0
            }, BorderLayout.CENTER)
        }
    }

    private fun identitiesPanel(pr: GhPullRequest): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyBottom(8)
        pr.author?.let {
            add(JBLabel("Author").apply { border = JBUI.Borders.emptyBottom(2) })
            add(identityRow(it))
            add(JPanel().apply { border = JBUI.Borders.emptyBottom(6) })
        }
        if (pr.reviewers.isNotEmpty()) {
            add(JBLabel("Reviewers").apply { border = JBUI.Borders.emptyBottom(2) })
            pr.reviewers.forEach { add(identityRow(it)) }
        }
    }

    private fun identityRow(user: GhUser): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        alignmentX = LEFT_ALIGNMENT
        val avatar = JBLabel(GitHubAvatarLoader.defaultIcon())
        add(avatar)
        add(JBLabel(user.display()))
        GitHubAvatarLoader.load(user.avatarUrl) { icon ->
            if (avatar.isDisplayable) avatar.icon = icon
        }
    }
}

private object GitHubAvatarLoader {
    private val cache = ConcurrentHashMap<String, Icon>()
    private val lock = Any()
    private val pending = HashMap<String, MutableList<(Icon) -> Unit>>()
    private val size = JBUI.scale(20)
    private val executor = AppExecutorUtil.getAppExecutorService()

    fun defaultIcon(): Icon = circleIcon(AllIcons.General.User)

    fun load(url: String?, onLoaded: (Icon) -> Unit) {
        if (url.isNullOrBlank()) {
            onLoaded(defaultIcon())
            return
        }
        cache[url]?.let {
            onLoaded(it)
            return
        }
        val shouldFetch = synchronized(lock) {
            val waiters = pending.getOrPut(url) { mutableListOf() }
            waiters += onLoaded
            waiters.size == 1
        }
        if (!shouldFetch) return

        executor.execute {
            val icon = cache[url] ?: runCatching { fetch(url) }.getOrElse { defaultIcon() }
            cache[url] = icon
            val callbacks = synchronized(lock) { pending.remove(url).orEmpty() }
            ApplicationManager.getApplication().invokeLater({
                callbacks.forEach { it(icon) }
            }, ModalityState.any())
        }
    }

    private fun fetch(url: String): Icon {
        val connection = URI(url).toURL().openConnection() as URLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.getInputStream().use { input ->
            val image = ImageIO.read(input) ?: return defaultIcon()
            return circleIcon(ImageIcon(image))
        }
    }

    private fun circleIcon(icon: Icon): Icon {
        val source = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val sourceGraphics = source.createGraphics()
        icon.paintIcon(null, sourceGraphics, 0, 0)
        sourceGraphics.dispose()

        val scaled = source.getScaledInstance(size, size, Image.SCALE_SMOOTH)
        val target = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.clip = Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat())
        g.drawImage(scaled, 0, 0, size, size, null)
        g.dispose()
        return ImageIcon(target)
    }
}
