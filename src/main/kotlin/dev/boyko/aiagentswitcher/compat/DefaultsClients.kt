package dev.boyko.aiagentswitcher.compat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry

/** Pinned native v1 capability factories. Never invent a capability or reuse the other host. */
internal object DefaultsClients {
    val hosts = listOf("assistant", "air")
    fun title(host: String) = if (host == "air") "Air" else "AI Assistant"
    fun initialize(project: Project, host: String): JsonObject {
        val capabilities: Any
        val implementation: Any
        if (host == "air") {
            val set = PluginManagerCore::class.java.getMethod("getPluginSet").invoke(null)
            val modules = set.javaClass.getMethod("getEnabledModules").invoke(set) as List<*>
            val module = modules.filterNotNull().firstOrNull { descriptor ->
                descriptor.javaClass.methods.firstOrNull { it.name == "getModuleNameString" && it.parameterCount == 0 }
                    ?.invoke(descriptor) == "intellij.air.backend.session.acp"
            } ?: error("Air's native ACP module is not installed or enabled.")
            val loader = module.javaClass.getMethod("getPluginClassLoader").invoke(module) as ClassLoader
            val type = Class.forName("com.intellij.air.acp.pool.AcpInitializedClientFactoryKt", true, loader)
            val native = type.getDeclaredMethod("acpClientInfo").apply { isAccessible = true }.invoke(null)
            capabilities = native.javaClass.getMethod("getCapabilities").invoke(native)
            implementation = native.javaClass.getMethod("getImplementation").invoke(native)
        } else {
            val loader = mlPluginClassLoader()
            fun type(name: String) = Class.forName(name, true, loader)
            val eel = type("com.intellij.platform.eel.provider.EelProviderProjectUtilKt")
                .getMethod("getEelDescriptor", Project::class.java).invoke(null, project)
            val companion = type("com.intellij.ml.llm.agents.acp.support.ShellProvider").getField("Companion").get(null)
            val shell = companion.javaClass.methods.single { it.name == "detect" && it.parameterCount == 1 }.invoke(companion, eel)
            val terminal = Registry.`is`("llm.chat.agent.acp.terminal.enabled") && shell != null
            val feature = type("com.intellij.ml.llm.agents.acp.elicitation.AcpElicitationFeature").getField("INSTANCE").get(null)
            val elicitation = feature.javaClass.getMethod("isEnabled").invoke(feature) as Boolean
            capabilities = type("com.intellij.ml.llm.agents.acp.client.AcpLocalSessionLifecycleManagerKt")
                .getMethod("buildLocalClientCapabilities", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(null, terminal, elicitation)
            val identity = type("com.intellij.ml.llm.agents.acp.client.AcpClientImplementationInfo").getField("INSTANCE").get(null)
            val info = ApplicationInfo.getInstance()
            implementation = identity.javaClass.getMethod("create", Boolean::class.javaPrimitiveType, String::class.java)
                .invoke(identity, true, "${info.shortCompanyName} ${info.versionName}")
        }
        return JsonObject().apply {
            addProperty("protocolVersion", 1)
            add("clientCapabilities", encode(capabilities))
            add("clientInfo", encode(implementation))
        }
    }
    private fun encode(value: Any): JsonObject {
        val companion = value.javaClass.getField("Companion").get(null)
        val serializer = companion.javaClass.getMethod("serializer").invoke(companion)
        val jsonType = Class.forName("kotlinx.serialization.json.Json", true, value.javaClass.classLoader)
        val json = jsonType.getField("Default").get(null)
        val encoded = jsonType.methods.single { it.name == "encodeToString" && it.parameterCount == 2 }.invoke(json, serializer, value) as String
        return JsonParser.parseString(encoded).asJsonObject
    }
}
