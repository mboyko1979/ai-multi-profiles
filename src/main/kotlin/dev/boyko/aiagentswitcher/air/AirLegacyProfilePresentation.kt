@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.air

import com.intellij.air.acp.AcpAgentCatalogContributor
import com.intellij.air.backend.session.api.AgentCatalogVisibilityOnlyDescriptor
import com.intellij.air.backend.session.api.AgentDescriptor
import com.intellij.air.backend.session.launch.AgentCatalogContributionSnapshot
import com.intellij.air.backend.session.launch.AgentCatalogContributor
import com.intellij.air.shared.core.thread.AgentId

/** Presentation lookup for chats created by dev.79–82 under the ACP registration ID.
 * Air's native composer uses a separate source ID. Keep old records unchanged;
 * contribute a non-launchable alias that delegates the actual native descriptor.
 */
class AirLegacyProfilePresentation : AgentCatalogContributor {
    override fun snapshot(): AgentCatalogContributionSnapshot {
        val original = AcpAgentCatalogContributor().snapshot().agentDescriptors.singleOrNull {
            it.agentId.value == "acp.dynamic.6163702e61692d6d756c7469"
        } ?: return AgentCatalogContributionSnapshot.EMPTY
        return AgentCatalogContributionSnapshot(fallbackAgentDescriptors = listOf(LegacyDescriptor(original)))
    }

    private class LegacyDescriptor(original: AgentDescriptor) : AgentCatalogVisibilityOnlyDescriptor, AgentDescriptor by original {
        override val agentId = AgentId.from("acp.ai-multi")
    }
}
