package dev.boyko.aiagentswitcher.compat;

import com.intellij.ml.llm.agents.acp.api.AcpAgentId;
import com.intellij.ml.llm.agents.acp.registry.AcpChatAgentExtension;
import dev.boyko.aiagentswitcher.MultiIcons;

import javax.swing.Icon;

/**
 * Native agent-menu metadata, scoped to the existing registration ID.
 * Implemented in Java so default interface methods stay on the interface.
 * A Kotlin implementation copies them into this class, and IDEA 2026.3
 * removed {@code contributeProcessEnv}, which then fails verification.
 */
public final class AiMultiAgentPresentation implements AcpChatAgentExtension {
    @Override
    public Icon getIcon(AcpAgentId agentId) {
        return "acp.ai-multi".equals(agentId.getFullId()) ? MultiIcons.Agent : null;
    }

    @Override
    public String getDescription(AcpAgentId agentId) {
        return "acp.ai-multi".equals(agentId.getFullId())
                ? "Use multiple AI profiles and switch between accounts or agents in the same chat window, carrying selected conversation history forward. Each project keeps its own default profile."
                : null;
    }
}
