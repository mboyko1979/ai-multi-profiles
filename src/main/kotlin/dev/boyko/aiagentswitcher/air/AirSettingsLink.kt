package dev.boyko.aiagentswitcher.air

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.boyko.aiagentswitcher.SwitcherConfigurable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent

/** Puts an Open Settings link in the Air composer when the project has no profile. */
internal object AirSettingsLink {
    private const val NAME = "ai-multi-open-settings"

    fun show(origin: Component?, project: Project?, visible: Boolean) {
        if (origin == null || project == null) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val editor = findPrompt(origin) ?: return@invokeLater
            val parent = editor.parent as? JComponent ?: return@invokeLater
            val existing = parent.components.filterIsInstance<JComponent>().firstOrNull { it.name == NAME }
            if (!visible) {
                if (existing != null) parent.remove(existing)
                if (editor.parent != parent) {
                    if (parent.layout is BorderLayout) parent.add(editor, BorderLayout.CENTER) else parent.add(editor)
                }
                editor.isVisible = true
                parent.revalidate()
                parent.repaint()
                return@invokeLater
            }
            if (existing != null) return@invokeLater
            val notice = Box(BoxLayout.Y_AXIS).apply {
                name = NAME
                border = JBUI.Borders.empty(8, 10)
                add(JBLabel("<html>This project has no AI Multi profile.<br>This chat does not send requests to any account.<br>Choose a profile, then start a new chat.</html>"))
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(ActionLink("Open Settings") {
                    if (!project.isDisposed) ShowSettingsUtil.getInstance().showSettingsDialog(project, SwitcherConfigurable::class.java)
                })
            }
            editor.isVisible = false
            if (parent.layout is BorderLayout) parent.add(notice, BorderLayout.CENTER) else parent.add(notice)
            parent.revalidate()
            parent.repaint()
        }
    }

    private fun findPrompt(origin: Component): EditorTextField? {
        var node: Component? = origin
        while (node != null) {
            findEditor(node, origin)?.let { return it }
            node = node.parent
        }
        return null
    }

    private fun findEditor(root: Component, skip: Component): EditorTextField? {
        if (root is EditorTextField && root !== skip) return root
        if (root !is Container) return null
        for (child in root.components) findEditor(child, skip)?.let { return it }
        return null
    }
}
