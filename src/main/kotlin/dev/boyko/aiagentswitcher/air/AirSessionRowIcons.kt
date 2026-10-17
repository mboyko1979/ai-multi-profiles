@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.air

import com.intellij.air.frontend.session.toolwindow.tree.SessionTreeNode
import com.intellij.air.frontend.session.toolwindow.ui.extractSessionTreeId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.boyko.aiagentswitcher.NativeRecoveryContinuation
import java.awt.Component
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

/** Pinned Air 262 presentation adapter. Session routing and native catalogs stay untouched. */
internal object AirSessionRowIcons {
    private const val MARKER = "ai-multi.session-row-icons"
    private val ownedRow = ThreadLocal<Boolean>()
    private val reportedUnsupportedApi = java.util.concurrent.atomic.AtomicBoolean()
    val renderingOwnedSession: Boolean get() = ownedRow.get() == true

    fun install(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ToolWindowManager.getInstance(project).getToolWindow("air.threads")?.component?.let(::observe)
            }
        }
    }

    private fun observe(component: Component) {
        if (component !is JComponent || component.getClientProperty(MARKER) == true) return
        component.putClientProperty(MARKER, true)
        if (component is JTree) {
            decorate(component)
            component.addPropertyChangeListener("cellRenderer") { decorate(component) }
        }
        // The native tool window creates its contents lazily. Observe only this
        // tool window's subtree, without polling or an application-wide UI hook.
        component.addContainerListener(object : ContainerAdapter() {
            override fun componentAdded(e: ContainerEvent) = observe(e.child)
        })
        component.components.forEach(::observe)
    }

    private fun decorate(tree: JTree) {
        val delegate = tree.cellRenderer ?: return
        if (delegate.javaClass.name != "com.intellij.air.frontend.session.toolwindow.ui.ThreadTreeCellRendererWithSeparators") return
        @Suppress("UNCHECKED_CAST")
        val resolve = try {
            NativeRecoveryContinuation.field(delegate, "nodeResolver") as (Any) -> Any?
        } catch (error: Exception) {
            if (reportedUnsupportedApi.compareAndSet(false, true))
                com.intellij.openapi.diagnostic.Logger.getInstance(AirSessionRowIcons::class.java)
                    .warn("Air session-row icon binding is unavailable; retaining native rendering", error)
            return
        }
        tree.cellRenderer = TreeCellRenderer { nativeTree, value, selected, expanded, leaf, row, focused ->
            val id = extractSessionTreeId(value)
            val node = id?.let(resolve)
            val owned = (node as? SessionTreeNode.Session)?.session?.agentSessionId?.startsWith("aiaps-chat-") == true
            val previous = ownedRow.get()
            ownedRow.set(owned)
            try {
                // Native rendering retains progress/status badges, separators,
                // tooltips, accessibility and row actions, including old chats.
                delegate.getTreeCellRendererComponent(nativeTree, value, selected, expanded, leaf, row, focused)
            } finally {
                if (previous == null) ownedRow.remove() else ownedRow.set(previous)
            }
        }
    }
}

class AirSessionRowIconActivity : ProjectActivity {
    override suspend fun execute(project: Project) = AirSessionRowIcons.install(project)
}

class AirSessionRowIconListener(private val project: Project) : ToolWindowManagerListener {
    override fun stateChanged(toolWindowManager: ToolWindowManager) = AirSessionRowIcons.install(project)
}
