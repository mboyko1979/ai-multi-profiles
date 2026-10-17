package dev.boyko.aiagentswitcher.air

import com.intellij.air.frontend.launch.*
import com.intellij.air.shared.core.thread.AgentLaunchTargetId
import com.intellij.air.shared.core.thread.acpAgentIdFromLaunchTargetId
import com.intellij.air.shared.session.AgentDescriptorDto
import dev.boyko.aiagentswitcher.MultiIcons
import kotlinx.coroutines.CoroutineScope
import javax.swing.Icon

/** Air's ACP provider exclusively owns every ACP target, including custom agents. */
class AirAgentPresentationService(scope: CoroutineScope) : AgentLaunchTargetPresentationService {
    private val native = AgentLaunchTargetPresentationServiceImpl(scope)
    override val snapshot get() = native.snapshot
    override fun request(targetIds: Set<AgentLaunchTargetId>) = native.request(targetIds)
    override fun resolveIcon(targetId: AgentLaunchTargetId?, descriptor: AgentDescriptorDto?, monochrome: Boolean, fallback: Icon?): Icon? =
        if (AirSessionRowIcons.renderingOwnedSession ||
            (targetId != null && acpAgentIdFromLaunchTargetId(targetId) == "acp.ai-multi") ||
            descriptor?.agentId?.value in setOf("acp.ai-multi", "acp.dynamic.6163702e61692d6d756c7469")) MultiIcons.Agent
        else native.resolveIcon(targetId, descriptor, monochrome, fallback)
}
