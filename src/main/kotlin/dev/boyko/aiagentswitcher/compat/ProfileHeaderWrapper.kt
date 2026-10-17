package dev.boyko.aiagentswitcher.compat

import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import dev.boyko.aiagentswitcher.MultiIcons
import java.awt.*
import java.awt.image.BufferedImage
import java.util.Collections
import java.util.IdentityHashMap
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole

/** Pinned native header presentation. Keep the native message view, scrolling and actions.
 * Its title reserves two lines, with one compact agent icon beside both lines.
 * Native accessibility bounds follow scrolling, wrapping and display scaling.
 */
internal class ProfileHeaderWrapper : Wrapper() {
    val headers = mutableMapOf<String, ResponseProfiles.Author>()
    private var backgroundSample: Pair<String, Color>? = null
    private var accessibilityPrepared = false
    private val bridgeContexts = Collections.newSetFromMap(IdentityHashMap<AccessibleContext, Boolean>())
    override fun isPaintingOrigin() = true

    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        if (!isShowing || headers.isEmpty()) return
        // Compose creates its accessibility bridge through the window's accessible
        // hierarchy. Keep only this view's bridge and its ancestor path alive.
        if (!accessibilityPrepared) {
            accessibilityPrepared = true
            val initialized = Collections.newSetFromMap(IdentityHashMap<AccessibleContext, Boolean>())
            val own = accessibleContext
            fun initialize(context: AccessibleContext?, depth: Int, inside: Boolean = false): Boolean {
                if (context == null || depth > 40 || initialized.size > 5000 || !initialized.add(context)) return false
                val subtree = inside || context === own
                if (subtree) bridgeContexts.add(context)
                var found = subtree
                for (index in 0 until context.accessibleChildrenCount) {
                    val matched = initialize(context.getAccessibleChild(index)?.accessibleContext, depth + 1, subtree)
                    if (matched) { found = true; bridgeContexts.add(context) }
                }
                return found
            }
            runCatching { initialize(javax.swing.SwingUtilities.getWindowAncestor(this)?.accessibleContext, 0) }
            javax.swing.SwingUtilities.invokeLater { repaint() }
        }
        val seen = Collections.newSetFromMap(IdentityHashMap<AccessibleContext, Boolean>())
        val layouts = mutableListOf<String>()
        fun visit(context: AccessibleContext?, depth: Int) {
            if (context == null || depth > 40 || !seen.add(context)) return
            val title = context.accessibleName
            val author = headers[title]
            if (author != null && context.accessibleRole == AccessibleRole.LABEL) {
                val bounds = runCatching {
                    val component = context.accessibleComponent ?: return@runCatching null
                    val origin = component.locationOnScreen
                    val parent = locationOnScreen
                    Rectangle(origin.x - parent.x, origin.y - parent.y, component.size.width, component.size.height)
                }.getOrNull()
                if (bounds != null && bounds.height >= 24 && bounds.intersects(Rectangle(0, 0, width, height))) {
                    val rowHeight = bounds.height / 2
                    val left = (bounds.x - JBUI.scale(27)).coerceAtLeast(0)
                    val row = Rectangle(left, bounds.y, bounds.width + bounds.x - left, bounds.height)
                    val theme = javax.swing.UIManager.getLookAndFeel().name + UIUtil.getLabelForeground().rgb
                    val background = backgroundSample?.takeIf { it.first == theme }?.second ?: run {
                        // Sample the native canvas margin, once per theme, without repainting other UI.
                        val sample = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
                        val paint = sample.createGraphics()
                        try { paint.translate(-left + 2, -bounds.y); super.paintChildren(paint) }
                        finally { paint.dispose() }
                        Color(sample.getRGB(0, 0)).also { backgroundSample = theme to it }
                    }
                    val paint = g.create() as Graphics2D
                    try {
                        paint.color = background
                        paint.clip(row)
                        paint.fillRect(row.x, row.y, row.width, row.height)
                        // Keep the established text column while avoiding a doubled icon/stroke.
                        val iconSlotWidth = JBUI.scale(32)
                        val agent = com.intellij.util.IconUtil.scale(MultiIcons.Agent, this, 1.25f)
                        agent.paintIcon(this, paint, left + (iconSlotWidth - agent.iconWidth) / 2,
                            bounds.y + (bounds.height - agent.iconHeight) / 2)
                        val textX = left + iconSlotWidth + JBUI.scale(10)
                        paint.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                        paint.font = UIUtil.getLabelFont()
                        paint.color = UIUtil.getLabelForeground()
                        val titleMetrics = paint.fontMetrics
                        paint.drawString(dev.boyko.aiagentswitcher.GenericAutoRegistration.AGENT, textX,
                            bounds.y + (rowHeight - titleMetrics.height) / 2 + titleMetrics.ascent)
                        val profileY = bounds.y + rowHeight
                        paint.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f)
                        val provider = InstalledAgents.icon(author.agentId) ?: MultiIcons.Agent
                        val providerX = textX
                        provider.paintIcon(this, paint, providerX, profileY + (rowHeight - provider.iconHeight) / 2)
                        paint.composite = AlphaComposite.SrcOver
                        paint.font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - JBUI.scale(1))
                        paint.color = UIUtil.getContextHelpForeground()
                        val metrics = paint.fontMetrics
                        paint.drawString(author.name, providerX + provider.iconWidth + JBUI.scale(6),
                            profileY + (rowHeight - metrics.height) / 2 + metrics.ascent)
                        layouts.add("${author.profileId}:compact-agent-icon,provider-icon,name")
                    } finally { paint.dispose() }
                }
            }
            for (index in 0 until context.accessibleChildrenCount)
                visit(context.getAccessibleChild(index)?.accessibleContext, depth + 1)
        }
        runCatching { visit(accessibleContext, 0) }
        putClientProperty("aiMulti.headerLayouts", layouts)
    }
}
