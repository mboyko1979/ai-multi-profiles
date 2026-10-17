package dev.boyko.aiagentswitcher

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.boyko.aiagentswitcher.compat.ProfileNativeAuthorization

internal object ProfileAuthorization {
    fun revoke(project: Project, id: String, completed: (Boolean) -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val profile = service<AgentProfiles>().find(id) ?: error("Profile no longer exists")
                require(service<AgentProfiles>().all().none {
                    it.id != id && normalizedPath(it.profileHome) == normalizedPath(profile.profileHome)
                }) { "Another profile shares this authorization directory" }
                ProfileCredentials.revoke(profile)
                ProfileNativeAuthorization.clearRouterChoice()
                profile
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    val profile = result.getOrNull()
                    NotificationGroupManager.getInstance().getNotificationGroup("AI Agent Project Switcher")
                        .createNotification(if (profile != null) "Authorization revoked" else "Could not revoke authorization",
                            if (profile != null) "${profile.name}: authorization removed. Sign in again to continue existing AI Multi chats."
                            else result.exceptionOrNull()?.message ?: "Revoke failed",
                            if (profile != null) NotificationType.INFORMATION else NotificationType.ERROR).notify(project)
                    completed(result.isSuccess)
                }
            }
        }
    }
}
