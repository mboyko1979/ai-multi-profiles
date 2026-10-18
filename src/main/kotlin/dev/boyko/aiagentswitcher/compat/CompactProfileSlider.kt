package dev.boyko.aiagentswitcher.compat

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBSlider
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JSlider
import javax.swing.plaf.basic.BasicSliderUI

/** Dialog slider with a small round thumb. Does not subclass DarculaSliderUI: that class is final on IDEA 2026.3. */
internal class CompactProfileSlider(min: Int, max: Int, value: Int) : JBSlider(min, max, value) {
    override fun updateUI() {
        setUI(CompactSliderUI(this))
    }
}

private class CompactSliderUI(slider: JSlider) : BasicSliderUI(slider) {
    override fun installUI(c: JComponent) {
        super.installUI(c)
        slider.isOpaque = false
    }

    override fun getThumbSize(): Dimension = JBUI.size(22, 22)

    override fun paintTrack(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val y = trackRect.y + trackRect.height / 2
            g.stroke = BasicStroke(JBUI.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = UIUtil.getLabelDisabledForeground()
            g.drawLine(trackRect.x, y, trackRect.x + trackRect.width, y)
            val span = slider.maximum - slider.minimum
            if (slider.isEnabled && span > 0) {
                val filled = trackRect.width * (slider.value - slider.minimum) / span
                g.color = UIUtil.getLabelForeground()
                g.drawLine(trackRect.x, y, trackRect.x + filled, y)
            }
        } finally {
            g.dispose()
        }
    }

    override fun paintFocus(graphics: Graphics) = Unit

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
        } finally {
            g.dispose()
        }
    }
}
