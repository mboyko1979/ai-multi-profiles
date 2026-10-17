package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.api.AcpAgentsCatalog
import com.intellij.openapi.application.ApplicationManager
import dev.boyko.aiagentswitcher.ChatProfiles

/** Matches native Revoke's IDE preference reset, scoped to AI Multi rather than the standard agent. */
internal object ProfileNativeAuthorization {
    fun clearRouterChoice() {
        val loader = AcpAgentsCatalog::class.java.classLoader
        val type = Class.forName("com.intellij.ml.llm.agents.acp.client.auth.AcpAuthenticationService", true, loader)
        val authType = Class.forName("com.intellij.ml.llm.agents.acp.client.auth.AcpAgentAuthentication", true, loader)
        val required = Class.forName("com.intellij.ml.llm.agents.acp.client.auth.AcpAgentAuthentication\$REQUIRED", true, loader)
            .getField("EXPLICIT_SELECTION").get(null)
        val service = ApplicationManager.getApplication().getService(type)
        val method = type.getMethod("setSelectedAgentAuth", AcpAgentId::class.java, authType)
        for (id in listOf(ChatProfiles.AGENT_ID, "acp.ai-auto-project-switcher")) {
            runCatching { method.invoke(service, AcpAgentId.parse(id), required) }
        }
    }
}
