package dev.boyko.aiagentswitcher

import com.intellij.openapi.components.*
import java.util.UUID

/** Stable profile IDs make reordering/renaming safe for project assignments. */
@Service(Service.Level.APP)
@State(name = "AcpSwitcherAgentProfiles", storages = [Storage("AcpSwitcherAgentProfiles.xml")])
class AgentProfiles : PersistentStateComponent<AgentProfiles.Data> {
    data class SessionSetting @JvmOverloads constructor(var agentId: String = "", var operation: String = "config", var id: String = "", var value: String = "", var host: String = "")
    data class ModelPreset(var modelId: String = "", var name: String = "", var enabled: Boolean = true,
        var settings: MutableList<SessionSetting> = mutableListOf()) {
        fun snapshot() = copy(settings = settings.map { it.copy() }.toMutableList())
    }
    data class ClientDefaults(var host: String = "", var agentId: String = "", var defaultModelId: String = "",
        var agentDefaultModelId: String = "", var modelOptionId: String = "model", var modelOperation: String = "config",
        var catalogJson: String = "", var models: MutableList<ModelPreset> = mutableListOf(), var validationJson: String = "",
        var revision: Long = 0) {
        fun snapshot() = copy(models = models.map { it.snapshot() }.toMutableList())
    }
    data class Entry(
        var id: String = "",
        var name: String = "",
        var command: String = "",
        var arguments: String = "",
        var workingDirectory: String = "",
        var environment: String = "",
        var inheritEnvironment: Boolean = true,
        var agentId: String = "",
        var agentName: String = "",
        var profileHome: String = "",
        var advancedOverrides: Boolean = false,
        var authenticationArguments: String = "",
        var sessionSettings: MutableList<SessionSetting> = mutableListOf(),
        var agentDefaults: MutableList<SessionSetting> = mutableListOf(),
        var defaultsAgentId: String = "",
        var defaultsObservedAt: Long = 0,
        var instructionsSource: String = "none",
        var instructionsText: String = "",
        var instructionsFile: String = "",
        var clientDefaults: MutableList<ClientDefaults> = mutableListOf()
    ) {
        fun snapshot(): Entry = copy(sessionSettings = sessionSettings.map { it.copy() }.toMutableList(),
            agentDefaults = agentDefaults.map { it.copy() }.toMutableList(),
            clientDefaults = clientDefaults.map { it.snapshot() }.toMutableList())
    }
    data class Data(var profiles: MutableList<Entry> = mutableListOf(), var migrated: Boolean = false)
    private var data = Data()
    @Synchronized override fun getState(): Data = Data(data.profiles.map { it.snapshot() }.toMutableList(), data.migrated)
    @Synchronized override fun loadState(state: Data) { data = Data(state.profiles.map { it.snapshot() }.toMutableList(), state.migrated) }
    @Synchronized fun all(): List<Entry> = data.profiles.map { it.snapshot() }
    fun find(id: String): Entry? = all().find { it.id == id }
    @Synchronized fun replace(entries: List<Entry>) {
        val ids = entries.map { it.id }
        require(ids.size == ids.toSet().size && ids.none { it.isBlank() }) { "Profile IDs must be unique" }
        require(entries.map { it.name.lowercase() }.distinct().size == entries.size) { "Profile names must be unique" }
        require(entries.all { it.name.isNotBlank() && it.command.isNotBlank() }) { "Name and command are required" }
        require(entries.all { it.sessionSettings.none { option -> ProfileConfiguration.sensitive(option.id) } }) { "Credentials cannot be saved as session options" }
        require(entries.all { it.agentDefaults.none { option -> ProfileConfiguration.sensitive(option.id) } }) { "Credentials cannot be saved as agent defaults" }
        require(entries.all { entry -> entry.clientDefaults.all { defaults ->
            defaults.host in listOf("assistant", "air") && defaults.models.map { it.modelId }.distinct().size == defaults.models.size &&
                defaults.models.all { model -> model.settings.none { ProfileConfiguration.sensitive(it.id) } }
        } }) { "Invalid client defaults or credential option" }
        data.profiles = entries.map { it.snapshot() }.toMutableList()
    }
    companion object { fun newEntry() = Entry(id = UUID.randomUUID().toString(), name = "New Profile") }
}
