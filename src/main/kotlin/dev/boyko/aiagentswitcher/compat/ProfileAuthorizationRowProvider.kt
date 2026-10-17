package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.core.settings.AgentAuthorizationRowProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.UIUtil
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.ProfileAuthorization
import dev.boyko.aiagentswitcher.ProfileCredentials
import kotlinx.coroutines.CoroutineScope

/** Native Agent Authorization rows, one per saved switcher profile. */
class ProfileAuthorizationRowProvider : AgentAuthorizationRowProvider {
    override fun createRows(panel: Panel, scope: CoroutineScope, project: Project) {
        for (profile in service<AgentProfiles>().all().filter { it.agentId.isNotBlank() }) {
            panel.row {
                label("${profile.name} (${profile.agentName})")
                label("Managed by profile").applyToComponent { foreground = UIUtil.getContextHelpForeground() }
                if (ProfileCredentials.hasCredentials(profile)) {
                    link("Revoke") { ProfileAuthorization.revoke(project, profile.id) }
                }
            }
        }
    }
}
