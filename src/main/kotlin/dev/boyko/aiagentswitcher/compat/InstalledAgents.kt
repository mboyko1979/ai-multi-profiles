package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.agents.ChatAgent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.Icon

/** Uses the same installed configurations and lazy runtime preparation as native AI Chat. */
internal object InstalledAgents {
    data class Choice(val id: String, val name: String) { override fun toString() = name }

    fun icon(id: String): Icon? = ExtensionPointName.create<ChatAgent>("com.intellij.ml.llm.agents.chatAgent")
        .extensionList.firstOrNull { it.id == id }?.icon

    fun all(): List<Choice> = try {
        (if (airHostEnabled()) airEntries() else installedEntries()).map { Choice(it.fullId, it.name) }
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
        if (airHostEnabled()) return resolveAir(project, profile)
        val entry = installedEntries().firstOrNull { it.fullId == profile.agentId }
            ?: error("${profile.agentName}: install or enable this agent in AI Assistant → Agents")
        val dynamicType = nativeType("com.intellij.ml.llm.agents.acp.registry.DynamicAcpChatAgent")
        val agent = ExtensionPointName.create<Any>("com.intellij.ml.llm.agents.chatAgent").extensionList
            .firstOrNull { dynamicType.isInstance(it) && dynamicType.getMethod("getAcpAgentId").invoke(it)?.let { id -> fullId(id) } == entry.fullId }
        // Air can install into the shared ACP store after AI Assistant's registry was initialized.
        // Resolve that installed distribution directly without registering aliases or touching chats.
        val localConfig = nativeType("com.intellij.ml.llm.agents.acp.runtime.config.LocalAcpAgentConfig")
        val config = entry.local ?: entry.external?.let { external ->
                val factory = nativeType("com.intellij.ml.llm.agents.acp.runtime.registry.AcpAgentFactory")
                factory.getMethod("createAgentConfig", external.javaClass, Project::class.java)
                    .invoke(factory.getField("INSTANCE").get(null), external, project)
            } ?: agent?.let { dynamicType.getMethod("getConfig").invoke(it) }
        if (config == null || !localConfig.isInstance(config)) error("${entry.name}: agent does not have a local launch configuration")
        // The start-config type lives in the ACP content module. Do not checkcast it through this plugin loader.
        val start = runBlocking { dev.boyko.aiagentswitcher.NativeRecoveryContinuation.suspendCall(config, "resolveAgentStartConfig", project) }
            ?: error("${entry.name}: agent did not resolve a launch configuration")
        val args = start.javaClass.getMethod("getArgs").invoke(start) as List<*>
        require(args.none { it is String && ('\n' in it || '\r' in it) }) { "Agent arguments contain unsupported line breaks" }
        return profile.snapshot().copy(agentName = entry.name, command = text(start, "getCommand"),
            arguments = lines(start, "getArgs"), authenticationArguments = lines(start, "getBaseArgs"),
            workingDirectory = textOrEmpty(start, "getWorkingDir"),
            environment = envLines(start), inheritEnvironment = true)
    }

    private data class Entry(val id: Any, val fullId: String, val name: String, val external: Any? = null, val local: Any? = null)

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
        val idClass = nativeType("com.intellij.ml.llm.agents.acp.api.AcpAgentId")
        val rowType = nativeType("com.intellij.ml.llm.agents.acp.settings.AgentListItem")
        val isInstalled = rowType.getMethod("isInstalled")
        val id = rowType.getMethod("getId")
        val name = rowType.getMethod("getName")
        val choices = rows.mapNotNull { row ->
            val displayName = name.invoke(row) as String
            val agentId = id.invoke(row)
            if (!idClass.isInstance(agentId) || isInstalled.invoke(row) != true || displayName == GenericAutoRegistration.AGENT ||
                displayName.startsWith(dev.boyko.aiagentswitcher.ProfileRegistrations.PREFIX)) null
            else Entry(agentId, fullId(agentId), displayName)
        }.associateByTo(linkedMapOf()) { it.fullId }
        // Installed metadata remains authoritative even before the host's registry/catalog catches up.
        val installedIds = stateType.getMethod("getInstalledAgentIds").invoke(state) as Set<*>
        for (installedId in installedIds) {
            val agentId = installedId ?: continue
            if (!idClass.isInstance(agentId)) continue
            val external = stateType.getMethod("getInstalledAgent", idClass).invoke(state, agentId) ?: continue
            val displayName = external.javaClass.getMethod("getName").invoke(external) as String
            if (displayName == GenericAutoRegistration.AGENT || displayName.startsWith(dev.boyko.aiagentswitcher.ProfileRegistrations.PREFIX)) continue
            choices[fullId(agentId)] = Entry(agentId, fullId(agentId), displayName, external)
        }
        // The registry settings row provider deliberately excludes custom local ACP agents.
        // Load their native configuration afresh too, rather than relying on its VFS watcher.
        val localConfig = nativeType("com.intellij.ml.llm.agents.acp.runtime.config.LocalAcpAgentConfig")
        val loaderType = nativeType("com.intellij.ml.llm.agents.acp.runtime.config.AcpConfigurationLoader")
        val loader = loaderType.getField("INSTANCE").get(null)
        val file = loaderType.getMethod("getConfigFilePath").invoke(loader) as java.nio.file.Path
        val configuration = if (java.nio.file.Files.isRegularFile(file))
            loaderType.getMethod("loadFromPath", java.nio.file.Path::class.java).invoke(loader, file) else null
        if (configuration != null) {
            val local = configuration.javaClass.getMethod("toAgentConfigs").invoke(configuration) as Map<*, *>
            for ((rawId, rawConfig) in local) {
                val id = rawId ?: continue
                val spec = rawConfig ?: continue
                if (!idClass.isInstance(id) || !localConfig.isInstance(spec)) continue
                val specName = spec.javaClass.getMethod("getName").invoke(spec) as String
                if (specName == GenericAutoRegistration.AGENT || specName.startsWith(dev.boyko.aiagentswitcher.ProfileRegistrations.PREFIX)) continue
                choices[fullId(id)] = Entry(id, fullId(id), specName, local = spec)
            }
        }
        return choices.values.toList()
    }

    private fun airEntries(): List<Entry> {
        val loader = airApiLoader()
        val catalog = airService("com.intellij.air.acp.api.AcpAgentsCatalog")
        runBlocking { dev.boyko.aiagentswitcher.NativeRecoveryContinuation.suspendCall(catalog, "awaitLocalAgentsRead") }
        val launchableType = Class.forName("com.intellij.air.acp.api.AcpLaunchableAgents", true, loader)
        val launchable = ApplicationManager.getApplication().getService(launchableType)
            ?: error("Air service com.intellij.air.acp.api.AcpLaunchableAgents is not available")
        val flow = launchableType.getMethod("getEntriesFlow").invoke(launchable)
            ?: error("Air did not publish its launchable agents")
        // ReadonlyStateFlow and the catalog entry implementations are not open to this plugin.
        // Read them through the exported interfaces that declare the same public methods.
        val stateFlow = Class.forName("kotlinx.coroutines.flow.StateFlow", true, flow.javaClass.classLoader)
        val agents = apiRead(stateFlow, flow, "getValue") as List<*>
        val entryType = Class.forName("com.intellij.air.acp.api.AcpCatalogEntry", true, loader)
        val idType = Class.forName("com.intellij.air.acp.api.AcpAgentId", true, loader)
        return agents.mapNotNull { agent ->
            if (agent == null) return@mapNotNull null
            val id = apiRead(entryType, agent, "getId") ?: return@mapNotNull null
            val displayName = apiRead(entryType, agent, "getDisplayName") as String
            if (displayName == GenericAutoRegistration.AGENT || displayName.startsWith(dev.boyko.aiagentswitcher.ProfileRegistrations.PREFIX)) null
            else Entry(id, apiRead(idType, id, "getFullId") as String, displayName)
        }
    }

    private fun resolveAir(project: Project, profile: AgentProfiles.Entry): AgentProfiles.Entry {
        val loader = airApiLoader()
        val catalogType = Class.forName("com.intellij.air.acp.api.AcpAgentsCatalog", true, loader)
        val catalog = ApplicationManager.getApplication().getService(catalogType)
            ?: error("Air has not published its agent catalog.")
        val idType = Class.forName("com.intellij.air.acp.api.AcpAgentId", true, loader)
        val companion = idType.getField("Companion").get(null)
        val agentId = companion.javaClass.getMethod("parse", String::class.java).invoke(companion, profile.agentId)
            ?: error("${profile.agentName}: Air does not recognize this agent")
        val entry = catalogType.getMethod("get", idType).invoke(catalog, agentId)
            ?: error("${profile.agentName}: install or enable this agent in Air")
        val start = runBlocking { dev.boyko.aiagentswitcher.NativeRecoveryContinuation.suspendCall(entry, "resolveStartConfig", project) }
            ?: error("${profile.agentName}: Air did not resolve a launch configuration")
        val startType = Class.forName("com.intellij.air.acp.api.AcpAgentStartConfig", true, loader)
        val entryType = Class.forName("com.intellij.air.acp.api.AcpCatalogEntry", true, loader)
        val args = apiRead(startType, start, "getArgs") as List<*>
        require(args.none { it is String && ('\n' in it || '\r' in it) }) { "Agent arguments contain unsupported line breaks" }
        val directory = apiRead(startType, start, "getWorkingDir")
        val env = apiRead(startType, start, "getEnv") as Map<*, *>
        return profile.snapshot().copy(
            agentName = profile.agentName.ifBlank { apiRead(entryType, entry, "getDisplayName") as String },
            command = apiRead(startType, start, "getCommand") as String,
            arguments = args.joinToString("\n"),
            authenticationArguments = (apiRead(startType, start, "getBaseArgs") as List<*>).joinToString("\n"),
            workingDirectory = directory?.toString().orEmpty(),
            environment = env.entries.joinToString("\n") { "${it.key}=${it.value}" },
            inheritEnvironment = true)
    }

    /** Invoke a public API method. The runtime class may be closed to this plugin even when the member is public. */
    private fun apiRead(owner: Class<*>, target: Any, getter: String): Any? {
        val method = owner.getMethod(getter)
        return try {
            method.invoke(target)
        } catch (denied: IllegalAccessException) {
            method.isAccessible = true
            method.invoke(target)
        }
    }

    private fun airService(name: String): Any {
        val type = Class.forName(name, true, airApiLoader())
        return ApplicationManager.getApplication().getService(type) ?: error("Air service $name is not available")
    }

    private fun fullId(id: Any): String = id.javaClass.getMethod("getFullId").invoke(id) as String
    private fun text(value: Any, getter: String): String = value.javaClass.getMethod(getter).invoke(value) as String
    private fun textOrEmpty(value: Any, getter: String): String = value.javaClass.getMethod(getter).invoke(value)?.toString().orEmpty()
    private fun lines(value: Any, getter: String): String = (value.javaClass.getMethod(getter).invoke(value) as List<*>).joinToString("\n")
    private fun envLines(value: Any): String {
        val env = value.javaClass.getMethod("getEnv").invoke(value) as Map<*, *>
        return env.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }
}

/** ACP lives in the internal Assistant module intellij.ml.llm.agents.acp.
 * IDEA 2026.3 does not put that module on this plugin's loader.
 * PluginMainDescriptor.getContentModules() throws if any content item has no descriptor, so the enabled-module set is authoritative.
 * A compile-time AcpAgentId reference fails here with NoClassDefFoundError before any catalog load.
 */
internal fun airHostEnabled(): Boolean = hostEnabled("com.intellij.air")

internal fun assistantHostEnabled(): Boolean = hostEnabled("com.intellij.ml.llm")

private fun hostEnabled(id: String): Boolean {
    val pluginId = com.intellij.openapi.extensions.PluginId.getId(id)
    return !com.intellij.ide.plugins.PluginManagerCore.isDisabled(pluginId) &&
        com.intellij.ide.plugins.PluginManagerCore.getPluginSet().isPluginEnabled(pluginId)
}

@Volatile private var airLoader: ClassLoader? = null

private fun airApiLoader(): ClassLoader {
    airLoader?.let { return it }
    val name = "com.intellij.air.acp.api.AcpLaunchableAgents"
    val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
        com.intellij.openapi.extensions.PluginId.getId("com.intellij.air"))
        ?: throw ClassNotFoundException("Air is not installed, so its agent catalog is unavailable.")
    val loaders = LinkedHashSet<ClassLoader>()
    plugin.pluginClassLoader?.let { loaders += it; loaders += acpLoaders(it) }
    val set = com.intellij.ide.plugins.PluginManagerCore.getPluginSet()
    for (moduleName in listOf("intellij.air.backend.acp.api", "intellij.air.backend.acp", "intellij.air.backend.acp.runtime")) {
        val module = set.findEnabledModule(com.intellij.ide.plugins.PluginModuleId.getId(
            moduleName, com.intellij.ide.plugins.PluginModuleId.JETBRAINS_NAMESPACE)) ?: continue
        if (module.pluginClassLoader == null) {
            val platform = com.intellij.ide.plugins.PluginManagerCore::class.java.classLoader
            runCatching { com.intellij.ide.plugins.ClassLoaderConfigurator(set, platform).configureModule(module) }
        }
        module.pluginClassLoader?.let { loaders += it }
    }
    val found = findLoaded(name, loaders.toList())
    val loader = found?.classLoader ?: throw ClassNotFoundException("Air ACP catalog is not visible on this IDE build.")
    airLoader = loader
    return loader
}

@Volatile private var acpLoader: ClassLoader? = null

internal fun mlPluginClassLoader(): ClassLoader {
    acpLoader?.let { return it }
    val registry = "com.intellij.ml.llm.agents.acp.runtime.registry.AcpAgentInstallationState"
    val name = "com.intellij.ml.llm.agents.acp.api.AcpAgentId"
    val loaders = LinkedHashSet<ClassLoader>()
    InstalledAgents::class.java.classLoader?.let { loaders += it }
    val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
        com.intellij.openapi.extensions.PluginId.getId("com.intellij.ml.llm"))
        ?: throw ClassNotFoundException("AI Assistant is not installed, so ACP classes are unavailable.")
    loaders += acpLoaders(plugin)
    runCatching { loaders += enabledAcpLoaders(plugin) }
    val found = findLoaded(registry, loaders.toList()) ?: findLoaded(name, loaders.toList())
    val loader = found?.classLoader
    if (loader != null) {
        acpLoader = loader
        return loader
    }
    throw ClassNotFoundException("AI Assistant ACP classes are not visible on this IDE build. Saved launch settings were kept.")
}

private fun enabledAcpLoaders(assistant: com.intellij.ide.plugins.IdeaPluginDescriptor): List<ClassLoader> {
    val set = com.intellij.ide.plugins.PluginManagerCore.getPluginSet()
    val moduleId = com.intellij.ide.plugins.PluginModuleId.getId(
        "intellij.ml.llm.agents.acp", com.intellij.ide.plugins.PluginModuleId.JETBRAINS_NAMESPACE)
    val module = set.findEnabledModule(moduleId)
    val loaders = LinkedHashSet<ClassLoader>()
    fun take(loader: ClassLoader?) { if (loader != null) loaders += loader }
    take(module?.pluginClassLoader)
    if (module != null && module.pluginClassLoader == null) {
        // The module is enabled but has not been given a loader yet. This is the host initializer;
        // it returns without replacing a loader that already exists.
        val platform = com.intellij.ide.plugins.PluginManagerCore::class.java.classLoader
        runCatching { com.intellij.ide.plugins.ClassLoaderConfigurator(set, platform).configureModule(module) }
        take(module.pluginClassLoader)
    }
    val enabled = set.javaClass.getMethod("getEnabledModules").invoke(set) as Iterable<*>
    for (item in enabled) {
        if (item == null) continue
        val moduleName = item.javaClass.methods.firstOrNull { it.name == "getModuleNameString" && it.parameterCount == 0 }
            ?.invoke(item) as? String ?: continue
        if (moduleName != "intellij.ml.llm.agents.acp") continue
        take(item.javaClass.getMethod("getPluginClassLoader").invoke(item) as? ClassLoader)
    }
    val main = assistant.pluginClassLoader
    if (main != null) loaders += acpLoaders(main)
    return loaders.toList()
}

private fun findLoaded(name: String, loaders: List<ClassLoader>): Class<*>? {
    for (loader in loaders) {
        try { return Class.forName(name, false, loader) }
        catch (_: ClassNotFoundException) {}
        catch (_: LinkageError) {}
    }
    return null
}

internal fun acpLoaders(root: Any): List<ClassLoader> {
    val loaders = LinkedHashSet<ClassLoader>()
    val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    fun walk(value: Any?, depth: Int) {
        if (value == null || depth > 8 || !seen.add(value)) return
        when (value) {
            is ClassLoader -> loaders += value
            is Iterable<*> -> value.forEach { walk(it, depth + 1) }
            else -> {
                // IDEA 2026.3: loaded ACP modules are PluginMainDescriptor.getContentModules().
                // PluginContentDescriptor.modules is a public field of ModuleItem, not getModules().
                // ModuleItem.getDescriptor() is the ContentModuleDescriptor that owns the class loader.
                for (methodName in listOf(
                    "getPluginClassLoader", "getClassLoader", "getContentModules", "getContent", "getModules",
                    "getDescriptorOrNull", "getDescriptor", "_getParents"
                )) {
                    val method = value.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
                    val result = try { method.invoke(value) } catch (_: Throwable) { continue }
                    walk(result, depth + 1)
                }
                val modules = value.javaClass.fields.firstOrNull { it.name == "modules" } ?: return
                val result = try { modules.get(value) } catch (_: Throwable) { return }
                walk(result, depth + 1)
            }
        }
    }
    walk(root, 0)
    return loaders.toList()
}
