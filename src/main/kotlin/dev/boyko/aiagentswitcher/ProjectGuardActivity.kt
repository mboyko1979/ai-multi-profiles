package dev.boyko.aiagentswitcher

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ProjectGuardActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<AgentRecoveryDialogs>().start()
        service<ClientPreferences>().start()
        service<RegistrationLifecycle>().reconcileLegacy()
        val id = project.service<ProjectSelection>().selected
        service<ProjectRoutes>().set(project,id)
        GenericAutoRegistration.ensureAsync(project)
        if (id != "Unassigned" && service<AgentProfiles>().find(id) != null) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Agent Project Switcher")
            .createNotification("AI Multi: choose a default project profile", "Select AI Multi in AI Chat, or use Tools > AI Multi: Select Project Profile. Reopening a chat restores that chat's profile.", NotificationType.INFORMATION)
        notification.addAction(NotificationAction.createSimple("Open Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project,SwitcherConfigurable::class.java)
        })
        notification.notify(project)
    }
}
