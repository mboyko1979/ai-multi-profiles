package dev.boyko.aiagentswitcher.air

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.boyko.aiagentswitcher.MultiIcons
import javax.swing.Icon
import com.intellij.openapi.util.Key
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.actionSystem.DataContext
import dev.boyko.aiagentswitcher.NativeRecoveryContinuation

internal object AirNewSessionTabIcon {
    private val owned = Key.create<Boolean>("ai-multi.new-session-tab")

    fun isOwned(file: VirtualFile) = file.getUserData(owned) == true

    fun update(context: DataContext, project: Project, isOwned: Boolean) {
        val native = NativeRecoveryContinuation
        val type = native.type("com.intellij.air.frontend.session.view.api.AgentSessionViewTabContextKt", "com.intellij.air") ?: return
        @Suppress("UNCHECKED_CAST")
        val key = type.getField("AGENT_SESSION_VIEW_TAB_CONTEXT").get(null) as com.intellij.openapi.actionSystem.DataKey<Any>
        val tab = key.getData(context) ?: return
        if (!(tab.javaClass.getField("selectedThreadId").get(tab) as? String).isNullOrBlank()) return
        val tabKey = tab.javaClass.getField("tabKey").get(tab) as? String ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val manager = FileEditorManager.getInstance(project)
            val file = manager.openFiles.firstOrNull {
                it.javaClass.name == "com.intellij.air.thread.view.AgentThreadViewVirtualFile" &&
                    runCatching { it.javaClass.getMethod("getTabKey").invoke(it) == tabKey }.getOrDefault(false)
            } ?: return@invokeLater
            if (isOwned(file) != isOwned) {
                file.putUserData(owned, isOwned)
                manager.updateFilePresentation(file)
            }
        }
    }
}

/** Air 263 file tabs: keep native activity and access badges for our virtual sessions. */
class AirSessionTabIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.javaClass.name != "com.intellij.air.thread.view.AgentThreadViewVirtualFile") return null
        return try {
            val type = file.javaClass
            if (type.getMethod("getSubAgentId").invoke(file) != null) return null
            val sessionId = type.getMethod("getSessionId").invoke(file) as? String
            if (sessionId?.startsWith("aiaps-chat-") != true && !AirNewSessionTabIcon.isOwned(file)) return null
            val loader = type.classLoader
            val provider = loader.loadClass("com.intellij.air.thread.view.AgentThreadViewFileIconProviderKt")
            val icons = loader.loadClass("com.intellij.air.thread.view.AgentThreadViewIconsKt")
            val activity = provider.getMethod("resolveAgentThreadViewTabIconActivity", type).invoke(null, file)
            var icon = icons.methods.single { it.name == "agentThreadViewStatusIcon" && it.parameterCount == 2 }
                .invoke(null, MultiIcons.Agent, activity) as Icon
            val profile = type.getMethod("getLaunchProfile").invoke(file)
            val launchMode = profile?.javaClass?.getField("launchMode")?.get(profile)?.toString()
            if (launchMode == "FULL_ACCESS" || (profile == null && type.getMethod("getPendingLaunchMode").invoke(file) == "FULL_ACCESS")) {
                icon = icons.getMethod("withAgentThreadViewFullAccessModeBadge", Icon::class.java).invoke(null, icon) as Icon
            }
            icon
        } catch (_: ReflectiveOperationException) {
            null // Unsupported Air versions retain their native presentation.
        } catch (_: LinkageError) {
            null
        }
    }
}
