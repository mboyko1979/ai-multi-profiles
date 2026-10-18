package dev.boyko.aiagentswitcher.compat

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.runtime.config.LocalAcpAgentConfig
import com.intellij.ml.llm.agents.ChatAgent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import kotlinx.coroutines.runBlocking
import javax.swing.Icon

/** Uses the same installed configurations and lazy runtime preparation as native AI Chat. */
internal object InstalledAgents {
    data class Choice(val id: String, val name: String) { override fun toString() = name }

    fun icon(id: String): Icon? = ExtensionPointName.create<ChatAgent>("com.intellij.ml.llm.agents.chatAgent")
        .extensionList.firstOrNull { it.id == id }?.icon

    fun all(): List<Choice> = try {
        installedEntries().map { Choice(it.id.fullId, it.name) }
    } catch (missing: ClassNotFoundException) {
        Logger.getInstance(javaClass).info("Installed AI agents are unavailable", missing)
        emptyList()
    } catch (missing: LinkageError) {
        // NoClassDefFoundError is an Error. Route sync runs on a pooled thread whose handler only catches Exception.
        Logger.getInstance(javaClass).info("Installed AI agents are unavailable", missing)
        emptyList()
    }

    /** Called on the saving worker, never the UI thread. No binaries or account credentials are copied. */
    fun resolve(project: Project, profile: AgentProfiles.Entry): AgentProfiles.Entry {
        val entry = installedEntries().firstOrNull { it.id.fullId == profile.agentId }
            ?: error("${profile.agentName}: install or enable this agent in AI Assistant → Agents")
        val dynamicType = nativeType("com.intellij.ml.llm.agents.acp.registry.DynamicAcpChatAgent")
        val agent = ExtensionPointName.create<Any>("com.intellij.ml.llm.agents.chatAgent").extensionList
            .firstOrNull { dynamicType.isInstance(it) && dynamicType.getMethod("getAcpAgentId").invoke(it) == entry.id }
        // Air can install into the shared ACP store after AI Assistant's registry was initialized.
        // Resolve that installed distribution directly without registering aliases or touching chats.
        val config = (entry.local ?: entry.external?.let { external ->
                val factory = nativeType("com.intellij.ml.llm.agents.acp.runtime.registry.AcpAgentFactory")
                factory.getMethod("createAgentConfig", external.javaClass, Project::class.java)
                    .invoke(factory.getField("INSTANCE").get(null), external, project)
            } ?: agent?.let { dynamicType.getMethod("getConfig").invoke(it) }) as? LocalAcpAgentConfig
            ?: error("${entry.name}: agent does not have a local launch configuration")
        val start = runBlocking { config.resolveAgentStartConfig(project) }
        require(start.args.none { '\n' in it || '\r' in it }) { "Agent arguments contain unsupported line breaks" }
        return profile.snapshot().copy(agentName = entry.name, command = start.command,
            arguments = start.args.joinToString("\n"), authenticationArguments = start.baseArgs.joinToString("\n"),
            workingDirectory = start.workingDir?.toString().orEmpty(),
            environment = start.env.entries.joinToString("\n") { "${it.key}=${it.value}" }, inheritEnvironment = true)
    }

    private data class Entry(val id: AcpAgentId, val name: String, val external: Any? = null, val local: LocalAcpAgentConfig? = null)

    private fun nativeType(name: String): Class<*> = Class.forName(name, true, mlPluginClassLoader())

    /** Calls the exact row provider used by Settings → AI Assistant → Agents, preserving its order. */
    private fun installedEntries(): List<Entry> {
        val stateType = nativeType("com.intellij.ml.llm.agents.acp.runtime.registry.AcpAgentInstallationState")
        val registryType = nativeType("com.intellij.ml.llm.agents.acp.runtime.registry.AcpRegistryManager")
        val app = ApplicationManager.getApplication()
        // The host service caches installed.json and does not observe installs/removals by Air.
        // A short-lived read-only snapshot loads the current native store on each refresh.
        val state = stateType.getConstructor().newInstance()
        val rows = nativeType("com.intellij.ml.llm.agents.acp.settings.AcpAgentsListPanelKt")
            .getMethod("fetchAgentListItems", stateType, registryType)
            .invoke(null, state, app.getService(registryType)) as List<*>
        val rowType = nativeType("com.intellij.ml.llm.agents.acp.settings.AgentListItem")
        val isInstalled = rowType.getMethod("isInstalled")
        val id = rowType.getMethod("getId")
        val name = rowType.getMethod("getName")
        val choices = rows.mapNotNull { row ->
            val displayName = name.invoke(row) as String
            if (isInstalled.invoke(row) != true || displayName == GenericAutoRegistration.AGENT ||
                displayName.startsWith(dev.boyko.aiagentswitcher.ProfileRegistrations.PREFIX)) null
            else Entry(id.invoke(row) as AcpAgentId, displayName)
        }.associateByTo(linkedMapOf()) { it.id.fullId }
        // Installed metadata remains authoritative even before the host's registry/catalog catches up.
        val installedIds = stateType.getMethod("getInstalledAgentIds").invoke(state) as Set<*>
        for (installedId in installedIds) {
            val external = stateType.getMethod("getInstalledAgent", AcpAgentId::class.java).invoke(state, installedId) ?: continue
            val agentId = installedId as AcpAgentId
            val displayName = external.javaClass.getMethod("getName").invoke(external) as String
            if (displayName == GenericAutoRegistration.AGENT || displayName.startsWith(dev.boyko.aiagentswitcher.ProfileRegistrations.PREFIX)) continue
            choices[agentId.fullId] = Entry(agentId, displayName, external)
        }
        // The registry settings row provider deliberately excludes custom local ACP agents.
        // Load their native configuration afresh too, rather than relying on its VFS watcher.
        val loaderType = nativeType("com.intellij.ml.llm.agents.acp.runtime.config.AcpConfigurationLoader")
        val loader = loaderType.getField("INSTANCE").get(null)
        val file = loaderType.getMethod("getConfigFilePath").invoke(loader) as java.nio.file.Path
        val configuration = if (java.nio.file.Files.isRegularFile(file))
            loaderType.getMethod("loadFromPath", java.nio.file.Path::class.java).invoke(loader, file) else null
        if (configuration != null) {
            val local = configuration.javaClass.getMethod("toAgentConfigs").invoke(configuration) as Map<*, *>
            for ((id, config) in local) {
                val agentId = id as? AcpAgentId ?: continue
                val spec = config as? LocalAcpAgentConfig ?: continue
                if (spec.name == GenericAutoRegistration.AGENT || spec.name.startsWith(dev.boyko.aiagentswitcher.ProfileRegistrations.PREFIX)) continue
                choices[agentId.fullId] = Entry(agentId, spec.name, local = spec)
            }
        }
        return choices.values.toList()
    }
}

/** AI Assistant class loader. Do not resolve AcpAgentsCatalog: IDEA 2026.3 may not expose that class. */
internal fun mlPluginClassLoader(): ClassLoader =
    PluginManagerCore.getPlugin(PluginId.getId("com.intellij.ml.llm"))?.pluginClassLoader
        ?: throw ClassNotFoundException("com.intellij.ml.llm")
