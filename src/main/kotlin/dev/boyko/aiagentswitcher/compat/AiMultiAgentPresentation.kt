package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.registry.AcpChatAgentExtension
import dev.boyko.aiagentswitcher.MultiIcons
import javax.swing.Icon

/** Native agent-menu metadata, scoped to the existing registration ID. */
class AiMultiAgentPresentation : AcpChatAgentExtension {
    override fun getIcon(agentId: AcpAgentId): Icon? =
        if (agentId.fullId == "acp.ai-multi") MultiIcons.Agent else null

    override fun getDescription(agentId: AcpAgentId): String? =
        if (agentId.fullId == "acp.ai-multi")
            "Use multiple AI profiles and switch between accounts or agents in the same chat window, carrying selected conversation history forward. Each project keeps its own default profile."
        else null
}
