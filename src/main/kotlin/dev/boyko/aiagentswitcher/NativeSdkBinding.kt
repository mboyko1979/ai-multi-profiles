package dev.boyko.aiagentswitcher

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Rebind accepted ACP metadata using the host's actual SDK types. No catalog,
 * capability conversion or alternate prompt/preparation runner lives here.
 */
internal object NativeSdkBinding {
    data class Binding(val session: Any, val info: Any)
    private val native get() = NativeRecoveryContinuation

    fun set(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try { type.getDeclaredField(name).apply { isAccessible = true }.set(target, value); return }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Unsupported native SDK binding: $name")
    }

    fun create(current: Any, initialize: JsonObject, response: JsonObject): Binding {
        check(initialize.get("protocolVersion")?.asInt == 1) { "Only ACP v1 bindings are supported" }
        val loader = current.javaClass.classLoader
        fun type(name: String) = Class.forName(name, true, loader)
        val jsonType = type("kotlinx.serialization.json.Json")
        val configure: (Any) -> Unit = { native.call(it, "setIgnoreUnknownKeys", true); Unit }
        val codec = native.call(type("kotlinx.serialization.json.JsonKt"), "Json", jsonType.getField("Default").get(null), configure)!!
        fun decode(name: String, source: JsonObject): Any {
            val serializer = native.call(type(name).getField("Companion").get(null), "serializer")!!
            return native.call(codec, "decodeFromString", serializer, source.toString())!!
        }
        val init = decode("com.agentclientprotocol.model.InitializeResponse", initialize)
        val created = decode("com.agentclientprotocol.model.NewSessionResponse", response)
        val client = native.call(current, "getClient")!!
        val infoType = native.call(client, "getAgentInfo")!!.javaClass
        val info = infoType.constructors.single { it.parameterCount == 5 }.newInstance(
            native.call(init, "getProtocolVersion"), native.call(init, "getAgentCapabilities"),
            native.call(init, "getAuthMethods"), native.call(init, "getAgentInfo"), native.call(init, "get_meta"))
        val session = type("com.agentclientprotocol.client.ClientSessionImpl").constructors.single { it.parameterCount == 7 }.newInstance(
            client, native.call(created, "getSessionId"), native.call(current, "getParameters"),
            native.call(current, "getOperations"), created, native.call(client, "getProtocol"), null)
        return Binding(session, info)
    }

    fun installInfo(client: Any, info: Any) = set(client, "_agentInfo", CompletableDeferred(info))

    /** Air's facade keeps references to its SDK sessions. Retain their identity
     * and live flows while replacing the SDK's accepted creation response.
     */
    fun updateSession(current: Any, binding: Binding) {
        val next = binding.session
        check(native.call(current, "getSessionId") == native.call(next, "getSessionId")) { "Profile binding changed the logical session ID" }
        set(current, "createdResponse", native.field(next, "createdResponse"))
        for ((delegate, supported) in listOf("_currentMode" to "getModesSupported", "_currentModel" to "getModelsSupported", "_configOptions" to "getConfigOptionsSupported")) {
            val field = delegate + "\$delegate"
            @Suppress("UNCHECKED_CAST") val old = native.field(current, field) as Lazy<Any>
            @Suppress("UNCHECKED_CAST") val replacement = native.field(next, field) as Lazy<Any>
            if (native.call(next, supported) == true && old.isInitialized()) {
                @Suppress("UNCHECKED_CAST") val flow = old.value as MutableStateFlow<Any?>
                flow.value = native.call(replacement.value, "getValue")
            } else set(current, field, replacement)
        }
        installInfo(native.call(current, "getClient")!!, binding.info)
    }
}
