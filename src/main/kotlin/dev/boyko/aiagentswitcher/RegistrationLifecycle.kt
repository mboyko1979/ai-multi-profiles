package dev.boyko.aiagentswitcher

import com.intellij.ide.plugins.*
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/** Installed plugin state changes also cover removals/disable operations requiring a restart. */
@Service(Service.Level.APP)
class RegistrationLifecycle : Disposable, PluginStateListener, DynamicPluginListener {
    private val ownId = PluginId.getId("dev.boyko.ai-multi-profiles")
    private val legacyId = PluginId.getId("dev.boyko.codex-project-switcher")
    private val disabledListener = Runnable {
        val disabled = DisabledPluginsState.getDisabledIds()
        cleanup(current = ownId in disabled, legacy = legacyId in disabled)
        if (ownId !in disabled && !removed) {
            GenericAutoRegistration.resumeRegistration()
            ProjectManager.getInstance().openProjects.forEach { GenericAutoRegistration.ensureAsync(it) }
        }
    }
    @Volatile private var removed = false

    init {
        DisabledPluginsState.addDisablePluginListener(disabledListener)
        PluginStateManager.addStateListener(this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(DynamicPluginListener.TOPIC, this)
    }

    fun reconcileLegacy() {
        if (!PluginManagerCore.isLoaded(legacyId) || legacyId in DisabledPluginsState.getDisabledIds())
            cleanup(current = false, legacy = true)
    }

    override fun install(descriptor: IdeaPluginDescriptor) = Unit

    override fun uninstall(descriptor: IdeaPluginDescriptor) {
        if (descriptor.pluginId == ownId) removed = true
        cleanup(descriptor.pluginId == ownId, descriptor.pluginId == legacyId)
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (!isUpdate) {
            if (pluginDescriptor.pluginId == ownId) removed = true
            cleanup(pluginDescriptor.pluginId == ownId, pluginDescriptor.pluginId == legacyId)
        }
    }

    private fun cleanup(current: Boolean, legacy: Boolean) {
        if (!current && !legacy) return
        try {
            if (GenericAutoRegistration.removeRegistrations(current, legacy)) {
                val acp = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")), ".jetbrains", "acp.json")
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(acp)?.refresh(true, false)
            }
        } catch (e: Exception) {
            Logger.getInstance(javaClass).warn("Could not remove switcher ACP registrations; configuration was preserved", e)
        }
    }

    override fun dispose() {
        // Normal IDE shutdown keeps registrations. Profiles, credentials and chats are never deleted.
        DisabledPluginsState.removeDisablePluginListener(disabledListener)
        PluginStateManager.removeStateListener(this)
    }
}

/** Listen from the welcome screen too, before any project service has been requested. */
class RegistrationLifecycleBootstrap : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) { service<RegistrationLifecycle>() }
}
