package dev.boyko.aiagentswitcher.air

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.boyko.aiagentswitcher.MultiIcons
import javax.swing.Icon

/** Air 263 file tabs: keep native activity and access badges for our virtual sessions. */
class AirSessionTabIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.javaClass.name != "com.intellij.air.thread.view.AgentThreadViewVirtualFile") return null
        return try {
            val type = file.javaClass
            if (type.getMethod("getSubAgentId").invoke(file) != null) return null
            val sessionId = type.getMethod("getSessionId").invoke(file) as? String ?: return null
            if (!sessionId.startsWith("aiaps-chat-")) return null
            val loader = type.classLoader
            val provider = loader.loadClass("com.intellij.air.thread.view.AgentThreadViewFileIconProviderKt")
            val icons = loader.loadClass("com.intellij.air.thread.view.AgentThreadViewIconsKt")
            val activity = provider.getMethod("resolveAgentThreadViewTabIconActivity", type).invoke(null, file)
            var icon = icons.methods.single { it.name == "agentThreadViewStatusIcon" && it.parameterCount == 2 }
                .invoke(null, MultiIcons.Agent, activity) as Icon
            if (type.getMethod("getPendingLaunchMode").invoke(file) == "FULL_ACCESS") {
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
