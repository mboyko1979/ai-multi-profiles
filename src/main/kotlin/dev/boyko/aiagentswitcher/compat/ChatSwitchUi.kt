package dev.boyko.aiagentswitcher.compat

import com.intellij.ide.IdeEventQueue
import com.intellij.ml.llm.core.chat.ui.chat.AIAssistantChatPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LoadingDecorator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.AWTEvent
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.InputMethodEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Native loading decoration and event blocking, scoped to the project's chat surfaces. */
internal object ChatSwitchUi {
    private val panels = mutableMapOf<Project, MutableSet<AIAssistantChatPanel>>()
    private val operations = ConcurrentHashMap<Project, Operation>()

    fun isBusy(project: Project) = operations.containsKey(project)

    fun register(project: Project, panel: AIAssistantChatPanel) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        panels.getOrPut(project) { linkedSetOf() }.add(panel)
        Disposer.register(panel, Disposable {
            panels[project]?.let { it.remove(panel); if (it.isEmpty()) panels.remove(project) }
        })
        operations[project]?.cover(surface(project, panel))
    }

    private fun surface(project: Project, panel: AIAssistantChatPanel): JComponent {
        val tool = ToolWindowManager.getInstance(project).getToolWindow("AIAssistant")?.component
        return if (tool != null && SwingUtilities.isDescendingFrom(panel, tool)) tool else panel
    }

    fun begin(project: Project): Operation? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (project.isDisposed || isBusy(project)) return null
        val operation = Operation(project)
        operations[project] = operation
        try {
            Disposer.register(project, operation)
            panels[project]?.forEach { operation.cover(surface(project, it)) }
            ToolWindowManager.getInstance(project).getToolWindow("AIAssistant")?.component?.let(operation::cover)
            return operation
        } catch (e: Throwable) {
            Disposer.dispose(operation)
            throw e
        }
    }

    class Operation(private val project: Project) : Disposable {
        private val overlays = linkedMapOf<JComponent, Overlay>()
        private var closed = false

        fun cover(surface: JComponent) {
            if (!closed && !overlays.containsKey(surface)) overlays[surface] = Overlay(surface, this)
        }

        fun close() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (!closed) Disposer.dispose(this)
        }

        override fun dispose() {
            if (closed) return
            closed = true
            overlays.values.forEach { it.remove() }
            overlays.clear()
            operations.remove(project, this)
        }
    }

    private class Overlay(private val surface: JComponent, owner: Disposable) {
        private val lifetime = Disposer.newDisposable("AI Multi profile switch overlay")
        private val decorator = LoadingDecorator(JPanel().apply { isOpaque = false }, lifetime, 0).apply {
            loadingText = "Switching AI profile…"
            val background = UIUtil.getPanelBackground()
            overlayBackground = Color(background.red, background.green, background.blue, 230)
        }
        private val component = decorator.component
        private var layer: JLayeredPane? = null
        private val resize = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateBounds()
            override fun componentMoved(e: ComponentEvent) = updateBounds()
            override fun componentShown(e: ComponentEvent) = updateBounds()
            override fun componentHidden(e: ComponentEvent) = updateBounds()
        }
        private val ancestors = object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent) = updateBounds()
            override fun ancestorResized(e: HierarchyEvent) = updateBounds()
        }
        private val hierarchy = HierarchyListener { updateBounds() }

        init {
            Disposer.register(owner, lifetime)
            surface.addComponentListener(resize)
            surface.addHierarchyBoundsListener(ancestors)
            surface.addHierarchyListener(hierarchy)
            IdeEventQueue.getInstance().addDispatcher(object : IdeEventQueue.NonLockedEventDispatcher {
                override fun dispatch(e: AWTEvent): Boolean = when (e) {
                    is KeyEvent, is InputMethodEvent -> surface.isShowing &&
                        (inside(e.source as? Component, surface) || inside(e.source as? Component, component))
                    is MouseEvent -> surface.isShowing &&
                        SwingUtilities.getRootPane(e.component) === SwingUtilities.getRootPane(surface) &&
                        surface.contains(SwingUtilities.convertPoint(e.component, e.point, surface))
                    else -> false
                }
            }, lifetime)
            updateBounds()
            decorator.startLoading(false)
        }

        private fun inside(source: Component?, target: Component) = source != null &&
            (source === target || SwingUtilities.isDescendingFrom(source, target))

        private fun updateBounds() {
            val next = SwingUtilities.getRootPane(surface)?.layeredPane
            if (layer !== next) {
                layer?.remove(component)
                layer = next
                next?.add(component, JLayeredPane.DRAG_LAYER)
            }
            component.isVisible = surface.isShowing
            if (surface.isShowing && next != null) {
                component.bounds = SwingUtilities.convertRectangle(surface.parent, surface.bounds, next)
                component.revalidate()
                component.repaint()
            }
        }

        fun remove() {
            surface.removeComponentListener(resize)
            surface.removeHierarchyBoundsListener(ancestors)
            surface.removeHierarchyListener(hierarchy)
            decorator.stopLoading()
            layer?.let { it.remove(component); it.revalidate(); it.repaint() }
            layer = null
        }
    }
}
