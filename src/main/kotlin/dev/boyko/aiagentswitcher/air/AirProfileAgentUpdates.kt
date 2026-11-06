package dev.boyko.aiagentswitcher.air

import com.intellij.openapi.application.ApplicationManager
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.NativeRecoveryContinuation

/** Bind real launch usage to Air's own updater. It retains its policies,
 * consent, attempted-version set, installation coordinator and installer UI.
 */
internal object AirProfileAgentUpdates {
    suspend fun beforeSwitch(profile: AgentProfiles.Entry, cwd: String) {
        if (profile.advancedOverrides || profile.agentId.isBlank()) return
        val native = NativeRecoveryContinuation
        val idType = native.type("com.intellij.air.acp.api.AcpAgentId", "com.intellij.air")!!
        val id = native.call(idType.getField("Companion").get(null), "parse", profile.agentId)!!
        val stateType = native.type("com.intellij.air.acp.runtime.registry.AcpAgentInstallationState", "com.intellij.air")!!
        val state = ApplicationManager.getApplication().getService(stateType)
        if (native.call(state, "isInstalled", id) != true) return
        // An in-chat switch has no launch admission screen. Ask the same native
        // resolver and keep the current binding if native admission is pending.
        val resolver = native.type("com.intellij.air.acp.AcpAgentProvisioningResolverKt", "com.intellij.air")!!
        val verdict = native.suspendCall(resolver, "acpAgentProvisioningVerdict", id, java.nio.file.Path.of(cwd))
        check(verdict.toString() == "READY") {
            "Air requires agent setup ($verdict). Complete setup for ${profile.name} in Air's agent settings, then select this profile again."
        }
        beforeLaunch(profile)
    }

    suspend fun beforeLaunch(profile: AgentProfiles.Entry) {
        if (profile.advancedOverrides || profile.agentId.isBlank()) return
        val native = NativeRecoveryContinuation
        fun type(name: String) = native.type("com.intellij.air.acp.$name", "com.intellij.air")
            ?: error("Air's native agent update API is unavailable: $name")
        val app = ApplicationManager.getApplication()
        val id = native.call(type("api.AcpAgentId").getField("Companion").get(null), "parse", profile.agentId)!!
        val state = app.getService(type("runtime.registry.AcpAgentInstallationState"))
        if (native.call(state, "isInstalled", id) != true) return // Local command agents have no registry distribution.
        native.call(state, "updateLastUsed", id)
        val registryManager = app.getService(type("runtime.registry.AcpRegistryManager"))
        val registry = native.call(registryManager, "getRegistry") ?: return
        val agents = (native.call(registry, "getAgents") as List<*>).filterNotNull()
            .filter { native.call(it, "getId") == id }
        if (agents.isEmpty()) return
        // Scope the native sweep to the actually launched profile's agent.
        val selectedRegistry = native.call(registry, "copy", native.call(registry, "getVersion"), agents)!!
        val updaterType = type("AcpAgentAutoUpdater")
        // Air 521 inserts AgentRuntimePolicyState before the continuation. Air 497 does not.
        val sweep = mutableListOf<Any?>(app.getService(updaterType), selectedRegistry)
        val takesPolicy = updaterType.methods.any { method ->
            method.name == "access\$sweep" && method.parameterTypes.any { it.name.endsWith("AgentRuntimePolicyState") }
        }
        if (takesPolicy) {
            val policyType = native.type("com.intellij.air.backend.settings.api.AgentRuntimePolicyState", "com.intellij.air")
                ?: error("Air's runtime policy API is unavailable")
            sweep += app.getService(policyType)
        }
        native.suspendCall(updaterType, "access\$sweep", *sweep.toTypedArray())
        // A background sweep may already hold the native installation lock.
        // Join that coordinator before resolving a distribution it may replace.
        val ready: suspend () -> Unit = {}
        native.suspendCall(app.getService(type("AcpAgentInstallCoordinator")), "withAgent", id, ready)
    }
}
