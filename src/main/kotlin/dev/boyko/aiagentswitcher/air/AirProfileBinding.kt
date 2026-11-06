package dev.boyko.aiagentswitcher.air

import com.google.gson.JsonObject
import dev.boyko.aiagentswitcher.AirProfileBridge
import dev.boyko.aiagentswitcher.NativeRecoveryContinuation
import dev.boyko.aiagentswitcher.NativeSdkBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.lang.reflect.Modifier

/** Pinned Air 262 facade binding. Keep its connection, callbacks, thread and task
 * owner; replace only accepted SDK metadata and session configuration.
 */
internal object AirProfileBinding {
    private val native get() = NativeRecoveryContinuation
    private fun type(name: String) = native.type("com.intellij.air.acp.$name", "com.intellij.air")
        ?: error("Air profile binding API is unavailable: $name")

    suspend fun bind(binding: AirProfileBridge.Binding, selected: JsonObject) {
        check(AirProfileBridge.ready(binding)) { "Wait for the Air task before rebinding the profile" }
        val owners = native.field(binding.manager, "owners")!!
        val owner = native.call(owners, "get", binding.ref) ?: error("Air session was closed")
        val snapshot = (native.field(owner, "connectionSnapshotState") as StateFlow<*>).value!!
        val admitted = native.suspendCall(owners, "admitCurrentProtocolSession-", binding.ref, native.call(snapshot, "getGeneration-"))!!
        val protocol = admitted.javaClass.getField("session").get(admitted)
        val resources = native.field(protocol, "resources")!!
        val session = native.call(resources, "getClientSession")!!
        val connection = native.call(resources, "getConnection")!!
        val facade = native.field(connection, "facade")!!
        val pooled = native.call(native.field(facade, "lease")!!, "getClient")!!
        val negotiated = native.call(native.call(pooled, "getProtocolConnection")!!, "getSelected")!!
        val wireClient = native.call(negotiated, "getClient")!!
        val served = (native.field(facade, "servedSessions") as Map<*, *>).values.filterNotNull().single {
            native.call(it, "getSessionId") == native.call(session, "getSessionId")
        }
        val wireSession = native.call(served, "getUnderlying")!!
        val init = selected.getAsJsonObject("initialize")
        val response = selected.getAsJsonObject("session")
        // Decode/validate both host SDK layers before changing either of them.
        val bound = NativeSdkBinding.create(session, init, response)
        val wire = NativeSdkBinding.create(wireSession, init, response)
        val info = bound.info
        val capabilities = native.call(wire.info, "getCapabilities")!!
        val auth = (native.call(info, "getAuthMethods") as List<*>).filterNotNull().map {
            native.call(type("AcpV1AuthMethodMapperKt"), "toAcpAuthMethod", it)!!
        }
        val meta = native.call(info, "get_meta")
        fun extension(name: String) = native.call(type(name).getField("Companion").get(null), "from", meta)!!
        val declared = extension("AcpAgentCapabilities")
        val goals = extension("AcpGoalCapabilities")
        val steeringFactory = type("AcpSteeringCapabilities").getField("Companion").get(null)
        val steeringMethod = steeringFactory.javaClass.methods.single { it.name == "from" }
        val steeringSources = java.lang.reflect.Array.newInstance(steeringMethod.parameterTypes.single().componentType, 2)
        java.lang.reflect.Array.set(steeringSources, 0, meta)
        java.lang.reflect.Array.set(steeringSources, 1, native.call(native.call(info,"getCapabilities")!!,"get_meta"))
        val steering = steeringMethod.invoke(steeringFactory, steeringSources)!!
        val changes = mapOf(
            "authenticationMethods" to type("AcpAuthenticationMethods").getConstructor(List::class.java).newInstance(auth),
            "availableCommands" to emptyList<Any>(),
            "steeringSupported" to native.call(steering, "getSupported"),
            "goalControlActions" to native.call(goals, "getActions"),
            "goalCapabilities" to goals,
            "agentCapabilities" to declared,
            "assertedConfigOptionIds" to emptySet<String>(),
            "agentDefaultModelId" to null,
        )
        val fields = snapshot.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val copy = snapshot.javaClass.methods.single { it.name.startsWith("copy") && !Modifier.isStatic(it.modifiers) }
        check(copy.parameterCount == fields.size && changes.keys.all { key -> fields.any { it.name == key } }) { "Unsupported Air connection snapshot" }
        val transform: (Any) -> Any = { current ->
            copy.invoke(current, *fields.map { field -> field.isAccessible = true; if (changes.containsKey(field.name)) changes[field.name] else field.get(current) }.toTypedArray())
        }
        NativeSdkBinding.updateSession(wireSession, wire)
        NativeSdkBinding.set(negotiated, "agentInfo", wire.info)
        NativeSdkBinding.installInfo(wireClient, wire.info)
        NativeSdkBinding.updateSession(session, bound)
        NativeSdkBinding.set(connection, "initializedAgentInfo", info)
        // Update Air's existing pool facts for this exact connection only.
        val poolType = type("pool.AcpAgentClientPool")
        val pool = native.call(poolType.getField("Companion").get(null), "getInstance")!!
        @Suppress("UNCHECKED_CAST") val facts = native.field(pool, "mutableClientFacts") as MutableStateFlow<Map<Any, Any>>
        val key = native.field(pooled, "key")!!
        val oldFacts = facts.value[key] ?: error("Air client facts were released")
        val sessions = native.call(capabilities, "getSessionCapabilities")!!
        val acceptedFacts = type("pool.AcpClientFacts").constructors.single { it.parameterCount == 5 }.newInstance(
            native.call(capabilities, "getProviders") != null, native.call(sessions, "getFork") != null,
            native.call(capabilities, "getLoadSession"), auth, native.call(oldFacts, "getSupportedLlmApiTypes"))
        facts.update { it + (key to acceptedFacts) }
        check(native.call(native.field(owner, "liveState")!!, "updateConnectionSnapshot", transform) == true) { "Air connection changed during profile binding" }
        owner.javaClass.getDeclaredMethod("publishConnectionSnapshot").apply { isAccessible = true }.invoke(owner)
        native.suspendCall(protocol, "recordLiveConfigOptions")
        AirProfileBridge.control(binding, "_ai_multi/binding_ready", JsonObject().apply { add("profile", selected.get("profile")) })
    }
}
