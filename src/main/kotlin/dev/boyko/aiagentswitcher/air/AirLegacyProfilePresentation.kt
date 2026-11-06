@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.air

import com.intellij.air.acp.AcpAgentCatalogContributor
import com.intellij.air.backend.session.api.AgentCatalogVisibilityOnlyDescriptor
import com.intellij.air.backend.session.api.AgentDescriptor
import com.intellij.air.backend.session.launch.AgentCatalogContributionSnapshot
import com.intellij.air.backend.session.launch.AgentCatalogContributor
import com.intellij.air.shared.core.thread.AgentId
import com.intellij.openapi.extensions.ExtensionPointName
import java.lang.reflect.InvocationTargetException

/** Presentation lookup for chats created by dev.79–82 under the ACP registration ID.
 * Air's native composer uses a separate source ID. Keep old records unchanged;
 * contribute a non-launchable alias that delegates the actual native descriptor.
 */
class AirLegacyProfilePresentation : AgentCatalogContributor {
    override fun snapshot(): AgentCatalogContributionSnapshot {
        // Use Air's registered owner: Kotlin default-constructor masks changed in
        // 262.8665.512, and a second contributor also duplicates native caches.
        val contributor = ExtensionPointName.create<AgentCatalogContributor>("com.intellij.air.agentCatalogContributor")
            .extensionList.filterIsInstance<AcpAgentCatalogContributor>().singleOrNull()
            ?: return AgentCatalogContributionSnapshot.EMPTY
        // Newer Air separates cold declarations from runtime startup. Keep that
        // boundary while remaining binary compatible with the pinned .497 API.
        val declared = contributor.javaClass.methods.singleOrNull { it.name == "declaredSnapshot" && it.parameterCount == 0 }
        val snapshot = if (declared == null) contributor.snapshot() else try {
            declared.invoke(contributor) as AgentCatalogContributionSnapshot
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        val original = snapshot.agentDescriptors.singleOrNull {
            it.agentId.value == "acp.dynamic.6163702e61692d6d756c7469"
        } ?: return AgentCatalogContributionSnapshot.EMPTY
        return AgentCatalogContributionSnapshot(fallbackAgentDescriptors = listOf(LegacyDescriptor(original)))
    }

    // Reflect the installed interface, not the compile host's removed descriptor
    // methods (for example AgentSubAgentOpenPolicy on .497).
    private fun LegacyDescriptor(original: AgentDescriptor): AgentDescriptor =
        java.lang.reflect.Proxy.newProxyInstance(AgentDescriptor::class.java.classLoader,
            arrayOf(AgentDescriptor::class.java, AgentCatalogVisibilityOnlyDescriptor::class.java)) { _, method, arguments ->
            if (method.name.startsWith("getAgentId-")) AgentId.from("acp.ai-multi").value
            else try { method.invoke(original, *(arguments ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        } as AgentDescriptor
}
