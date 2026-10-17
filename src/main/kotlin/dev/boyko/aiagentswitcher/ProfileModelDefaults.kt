package dev.boyko.aiagentswitcher

import com.google.gson.Gson

/** Concrete settings for each client/model. Catalog observations are only used by explicit load/reset/refresh. */
internal object ProfileModelDefaults {
    private val gson = Gson()
    fun catalog(defaults: AgentProfiles.ClientDefaults): ProfileConfiguration.Capabilities? =
        runCatching { gson.fromJson(defaults.catalogJson, ProfileConfiguration.Capabilities::class.java) }.getOrNull()
    fun modelOption(c: ProfileConfiguration.Capabilities) = c.options.firstOrNull { it.category == "model" || it.operation == "model" }
    fun validationCatalog(profileId: String, defaults: AgentProfiles.ClientDefaults): ProfileConfiguration.Capabilities? {
        val c = runCatching { gson.fromJson(defaults.validationJson, ProfileConfiguration.Capabilities::class.java) }.getOrNull()
            ?: catalog(defaults) ?: return null
        val failure = ProfileSettingsPrompt.failure(profileId, defaults.host) ?: return c
        if (failure.liveOptions.isEmpty()) return c
        val liveModel = failure.liveOptions.firstOrNull { it.category == "model" || it.operation == "model" }
        val options = c.options.map { old -> if (old.id == liveModel?.id && old.operation == liveModel.operation) liveModel else old }
        val models = if (liveModel?.currentValue == failure.modelId) c.models.filterNot { it.modelId == failure.modelId } +
            ProfileConfiguration.ModelOptions(failure.modelId, failure.liveOptions) else c.models
        return c.copy(options = options, models = models)
    }
    fun observe(profileId: String, defaults: AgentProfiles.ClientDefaults) {
        validationCatalog(profileId, defaults)?.let { defaults.validationJson = gson.toJson(it) }
    }
    fun display(profile: AgentProfiles.Entry, host: String, state: com.google.gson.JsonObject): com.google.gson.JsonObject {
        val defaults = get(profile, host) ?: return state
        val copy = state.deepCopy()
        val option = copy.getAsJsonArray("configOptions")?.map { it.asJsonObject }?.firstOrNull {
            it.get("category")?.asString == "model" || (it.get("id")?.asString == defaults.modelOptionId && defaults.modelOperation == "config")
        }
        val current = option?.get("currentValue")?.asString ?: copy.getAsJsonObject("models")?.get("currentModelId")?.asString
        val disabled = defaults.models.filterNot { it.enabled }.map { it.modelId }.toSet()
        val order = defaults.models.mapIndexed { index, m -> m.modelId to index }.toMap()
        fun choices(array: com.google.gson.JsonArray, id: String): com.google.gson.JsonArray {
            val values = array.filter { item ->
                val o = item.asJsonObject
                if (o.has("options")) {
                    o.add("options", choices(o.getAsJsonArray("options"), id)); !o.getAsJsonArray("options").isEmpty
                } else o.get(id)?.asString.let { it == current || it !in disabled }
            }.sortedBy { order[it.asJsonObject.get(id)?.asString] ?: -1 }
            return com.google.gson.JsonArray().apply { values.forEach { add(it) } }
        }
        option?.getAsJsonArray("options")?.let { option.add("options", choices(it, "value")) }
        copy.getAsJsonObject("models")?.let { models -> models.getAsJsonArray("availableModels")?.let { models.add("availableModels", choices(it, "modelId")) } }
        return copy
    }
    fun options(c: ProfileConfiguration.Capabilities, modelId: String): List<ProfileConfiguration.Option> {
        val specific = c.models.firstOrNull { it.modelId == modelId }?.options
        val base = c.options.filterNot { it.category == "model" || it.operation == "model" }
        if (specific == null) return if (c.models.isEmpty() || modelOption(c)?.currentValue == modelId) base else
            base.filterNot { it.category in listOf("thought_level", "model_config") }
        return (base.filterNot { b -> specific.any { it.id == b.id && it.operation == b.operation } } +
            specific.filterNot { it.category == "model" || it.operation == "model" })
    }
    fun initial(option: ProfileConfiguration.Option) = if (option.category == "thought_level") option.modelDefault else option.currentValue
    fun allowed(option: ProfileConfiguration.Option, value: String?) = value != null &&
        if (option.type == "boolean") value in listOf("true", "false") else option.values.any { it.value == value }
    private fun initialSettings(profile: AgentProfiles.Entry, host: String, c: ProfileConfiguration.Capabilities, id: String) =
        options(c, id).mapNotNull { o ->
            val value = initial(o)
            // A recommendation is optional ACP metadata. Keep the catalog usable
            // and let the user choose missing values explicitly in the editor.
            if (value == null) return@mapNotNull null
            require(allowed(o, value)) { "Agent did not provide a confirmed default for ${o.name} (${id.ifBlank { "session" }}). Previous settings were retained." }
            AgentProfiles.SessionSetting(profile.agentId, o.operation, o.id, value, host)
        }.toMutableList()

    /** Legacy values are retained literally; an incompatible value must be corrected explicitly. */
    fun get(profile: AgentProfiles.Entry, host: String): AgentProfiles.ClientDefaults? {
        profile.clientDefaults.firstOrNull { it.agentId == profile.agentId && it.host == host }?.let { return it }
        val explicit = ProfileConfiguration.settings(profile, host)
        val frozen = profile.agentDefaults.filter { it.agentId == profile.agentId && (it.host == host || it.host.isBlank()) }
        if (explicit.isEmpty() && frozen.isEmpty()) return null
        val c = ProfileCatalog.cached(profile, host) ?: ProfileCatalog.cached(profile)
        val model = c?.let(::modelOption)
        val saved = (frozen + explicit).associateBy { it.operation to it.id }.values.toList()
        val selected = saved.firstOrNull { it.id == (model?.id ?: "model") && it.operation == (model?.operation ?: "config") }?.value
            ?: model?.currentValue.orEmpty()
        val ids = (model?.values?.map { it.value }.orEmpty() + selected).distinct()
        val defaults = AgentProfiles.ClientDefaults(host, profile.agentId, selected, model?.currentValue.orEmpty(),
            model?.id ?: "model", model?.operation ?: "config", c?.let { gson.toJson(it) }.orEmpty())
        ids.forEach { id ->
            val settings = c?.let { metadata -> options(metadata, id).mapNotNull { o -> initial(o)?.let {
                AgentProfiles.SessionSetting(profile.agentId, o.operation, o.id, it, host)
            } }.toMutableList() } ?: mutableListOf()
            if (id == selected) saved.filterNot { it.id == defaults.modelOptionId && it.operation == defaults.modelOperation }.forEach { s ->
                settings.removeAll { it.id == s.id && it.operation == s.operation }; settings.add(s.copy(host = host))
            }
            defaults.models.add(AgentProfiles.ModelPreset(id, model?.values?.find { it.value == id }?.name ?: id, true, settings))
        }
        profile.clientDefaults.add(defaults)
        return defaults
    }
    fun refresh(profile: AgentProfiles.Entry, host: String, c: ProfileConfiguration.Capabilities): AgentProfiles.ClientDefaults {
        require(c.warnings.isEmpty()) { c.warnings.joinToString("; ") }
        val option = modelOption(c)
        val available = option?.values ?: listOf(ProfileConfiguration.Value("", "Session"))
        val initialModel = option?.currentValue.orEmpty()
        require(available.any { it.value == initialModel }) { "Agent did not provide an available default model." }
        // Validate the complete observation before changing the draft.
        val fresh = available.map { m -> AgentProfiles.ModelPreset(m.value, m.name, true, initialSettings(profile, host, c, m.value)) }
        val previous = get(profile, host)
        val updated = previous?.snapshot() ?: AgentProfiles.ClientDefaults(host, profile.agentId)
        updated.agentDefaultModelId = initialModel
        updated.modelOptionId = option?.id.orEmpty(); updated.modelOperation = option?.operation ?: "config"
        updated.catalogJson = gson.toJson(c)
        updated.validationJson = ""
        updated.revision = c.observedAt
        val newModels = fresh.filter { n -> updated.models.none { it.modelId == n.modelId } }
        updated.models.forEach { old -> fresh.find { it.modelId == old.modelId }?.let { next ->
            old.name = next.name
            next.settings.filter { n -> old.settings.none { it.id == n.id && it.operation == n.operation } }.forEach { old.settings.add(it) }
        } }
        updated.models.addAll(0, newModels)
        if (previous == null || available.none { it.value == updated.defaultModelId }) {
            updated.defaultModelId = initialModel
            updated.models.first { it.modelId == initialModel }.enabled = true
        }
        profile.clientDefaults.removeAll { it.host == host && it.agentId == profile.agentId }
        profile.clientDefaults.add(updated)
        return updated
    }
    /** Reset list policy only; keep each model's explicit parameter settings. */
    fun resetList(defaults: AgentProfiles.ClientDefaults) {
        val c = catalog(defaults) ?: error("Load the agent model catalog first.")
        val ids = modelOption(c)?.values?.map { it.value } ?: listOf("")
        require(defaults.agentDefaultModelId in ids && ids.all { id -> defaults.models.any { it.modelId == id } }) {
            "Refresh Models before resetting the model list."
        }
        val ordered = ids.map { id -> defaults.models.first { it.modelId == id } }
        val unavailable = defaults.models.filter { it.modelId !in ids }
        ordered.forEach { it.enabled = true }
        unavailable.forEach { it.enabled = false }
        defaults.models.clear(); defaults.models.addAll(ordered); defaults.models.addAll(unavailable)
        defaults.defaultModelId = defaults.agentDefaultModelId
    }
    fun resetModel(profile: AgentProfiles.Entry, defaults: AgentProfiles.ClientDefaults, model: AgentProfiles.ModelPreset) {
        val c = catalog(defaults) ?: error("Load the agent model catalog first.")
        model.settings = initialSettings(profile, defaults.host, c, model.modelId)
    }
    fun remove(profile: AgentProfiles.Entry, host: String) {
        // Split old shared values before clearing just one client.
        ProfileConfiguration.removeDefaults(profile, host)
        profile.clientDefaults.removeAll { it.host == host && it.agentId == profile.agentId }
    }
    /** Observations and legacy migration are not edits to the user's defaults. */
    fun changed(profile: AgentProfiles.Entry, baseline: AgentProfiles.Entry?, host: String): Boolean {
        val current = get(profile.snapshot(), host)?.copy(validationJson = "")
        val previous = baseline?.let { get(it.snapshot(), host) }?.copy(validationJson = "")
        return current != previous
    }
    fun missingValues(defaults: AgentProfiles.ClientDefaults, profileId: String = ""): List<String> {
        val c = validationCatalog(profileId, defaults) ?: return emptyList()
        val available = modelOption(c)?.values?.map { it.value } ?: listOf("")
        return defaults.models.filter { it.enabled && it.modelId in available }.flatMap { model ->
            options(c, model.modelId).filter { option ->
                model.settings.none { it.id == option.id && it.operation == option.operation }
            }.map { "${model.name}: ${it.name} requires a value" }
        }
    }
    fun problems(defaults: AgentProfiles.ClientDefaults, profileId: String = ""): List<String> {
        val c = validationCatalog(profileId, defaults) ?: return listOf("Refresh Models to validate saved settings")
        val models = modelOption(c)?.values?.map { it.value } ?: listOf("")
        val errors = mutableListOf<String>()
        if (defaults.models.none { it.enabled && it.modelId in models }) errors += "Enable at least one available model"
        if (defaults.models.none { it.modelId == defaults.defaultModelId && it.enabled && it.modelId in models }) errors += "Default model — Deprecated or disabled"
        defaults.models.filter { it.enabled && it.modelId in models }.forEach { m ->
            val options = options(c, m.modelId)
            m.settings.forEach { s ->
                val o = options.find { it.id == s.id && it.operation == s.operation }
                if (o == null || !allowed(o, s.value)) errors += "${m.name}: ${o?.name ?: s.id}: ${s.value} — Deprecated"
            }
        }
        errors += missingValues(defaults, profileId)
        return errors
    }
}
