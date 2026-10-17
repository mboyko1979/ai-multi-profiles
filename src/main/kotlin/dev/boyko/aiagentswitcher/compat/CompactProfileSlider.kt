package dev.boyko.aiagentswitcher.compat

import com.intellij.ide.ui.laf.darcula.ui.DarculaSliderUI
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBSlider
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/** Native slider interaction/track, with a small round thumb local to this dialog. */
internal class CompactProfileSlider(min: Int, max: Int, value: Int) : JBSlider(min, max, value) {
    override fun updateUI() {
        super.updateUI()
        setUI(object : DarculaSliderUI(this@CompactProfileSlider) {
            // Keep a larger hit rectangle than the visible 12-pixel thumb.
            override fun getThumbSize(): Dimension = JBUI.size(22, 22)
            override fun paintThumb(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val size = JBUI.scale(12)
                    val x = thumbRect.x + (thumbRect.width - size) / 2
                    val y = thumbRect.y + (thumbRect.height - size) / 2
                    if (slider.hasFocus() && slider.isEnabled) {
                        g.color = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
                        g.stroke = BasicStroke(JBUI.scale(1.5f))
                        val pad = JBUI.scale(3)
                        g.drawOval(x - pad, y - pad, size + 2 * pad, size + 2 * pad)
                    }
                    g.color = if (slider.isEnabled) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
                    g.fillOval(x, y, size, size)
                } finally { g.dispose() }
            }
        })
    }
}
