package dev.boyko.aiagentswitcher

import com.google.gson.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.atomic.AtomicBoolean

/** Identity and storage boundary shared by all clients; precedence lives in profile-settings.js. */
internal object ClientPreferencePolicy {
    fun key(profile: String, agent: String): String {
        require(profile.isNotBlank() && agent.isNotBlank())
        return "acp.ai-multi-preferences-" + ProfileConfiguration.digest(Gson().toJson(listOf(profile, agent)))
    }
}

internal interface NativeClientPreferenceStore {
    fun read(key: String, options: JsonArray): JsonArray
    fun write(key: String, option: JsonObject, value: String)
}

/** No preference database of our own: rendezvous files are removed after each native-store operation. */
@Service(Service.Level.APP)
internal class ClientPreferences(private val scope: CoroutineScope) {
    private val started = AtomicBoolean()
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            val directory = ProfileConfiguration.directory.resolve("client-preferences")
            while (isActive) {
                if (Files.isDirectory(directory, NOFOLLOW_LINKS)) Files.list(directory).use { files ->
                    files.filter { it.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.request\\.json")) }.toList().forEach { file ->
                        try {
                            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 1_000_000) return@forEach
                            val request = JsonParser.parseString(Files.readString(file)).asJsonObject
                            val nonce = file.fileName.toString().removeSuffix(".request.json")
                            val requester = ProcessHandle.of(request.get("pid").asLong).orElse(null) ?: return@forEach
                            if (request.get("nonce")?.asString != nonce ||
                                System.currentTimeMillis() - request.get("createdAt").asLong !in 0..20_000 ||
                                !requester.isAlive || !generateSequence(requester.parent().orElse(null)) { it.parent().orElse(null) }
                                    .any { it.pid() == ProcessHandle.current().pid() }) return@forEach // Another IDE owns that router's native store.
                            val response = directory.resolve("$nonce.response.json")
                            if (Files.exists(response, NOFOLLOW_LINKS)) return@forEach
                            val answer = runCatching { exchange(request) }.getOrElse { error ->
                                Logger.getInstance(javaClass).warn("Native client preference operation failed", error)
                                JsonObject().apply { addProperty("error", "Native client preferences could not be accessed: ${error.message}") }
                            }.apply { addProperty("nonce", nonce) }
                            val tmp = Files.createTempFile(directory, ".preferences-", ".tmp")
                            try { Files.writeString(tmp, answer.toString()); Files.move(tmp, response, ATOMIC_MOVE); Files.deleteIfExists(file) }
                            finally { Files.deleteIfExists(tmp) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { Logger.getInstance(javaClass).warn("Could not process client preferences", error) }
                    }
                }
                delay(50)
            }
        }
    }

    internal fun exchange(request: JsonObject): JsonObject {
        val key = ClientPreferencePolicy.key(request.get("profile").asString, request.get("agentId").asString)
        val store: NativeClientPreferenceStore = when (request.get("host").asString) {
            "assistant" -> AssistantPreferenceStore
            "air" -> AirPreferenceStore
            else -> error("Unsupported preference client")
        }
        val options = request.getAsJsonArray("options") ?: JsonArray()
        return JsonObject().apply {
            when (request.get("operation").asString) {
                "read" -> add("settings", store.read(key, options))
                "write" -> {
                    val setting = request.getAsJsonObject("setting")
                    val option = options.firstOrNull { it.asJsonObject.get("id") == setting.get("id") && it.asJsonObject.get("operation") == setting.get("operation") }?.asJsonObject
                        ?: error("Preference option was not confirmed by the agent")
                    require(!Regex("access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|secret|password|api[_-]?key|credential|^token$", RegexOption.IGNORE_CASE).containsMatchIn(option.get("id").asString))
                    store.write(key, option, setting.get("value").asString)
                }
                else -> error("Unsupported preference operation")
            }
        }
    }
}

private object PreferenceReflection {
    val native get() = NativeRecoveryContinuation
    fun service(name: String, plugin: String): Any = ApplicationManager.getApplication().getService(
        native.type(name, plugin) ?: error("Native preference service is unavailable: $name"))
    fun setting(option: JsonObject, value: String) = JsonObject().apply {
        add("id", option.get("id")); add("operation", option.get("operation")); addProperty("value", value)
    }
}

private object AssistantPreferenceStore : NativeClientPreferenceStore {
    private val native get() = PreferenceReflection.native
    private fun store() = PreferenceReflection.service("com.intellij.ml.llm.agents.acp.settings.AcpAgentSettingsService", "com.intellij.ml.llm")
    private fun id(key: String): Any {
        val type = native.type("com.intellij.ml.llm.agents.acp.api.AcpAgentId", "com.intellij.ml.llm")!!
        return native.call(type.getField("Companion").get(null), "parse", key)!!
    }
    override fun read(key: String, options: JsonArray): JsonArray {
        val store = store(); val id = id(key)
        val values = native.call(store, "getAgentConfigOptions", id) as Map<*, *>
        return JsonArray().apply { for (item in options) {
            val option = item.asJsonObject
            val value = when (option.get("operation").asString) {
                "config" -> values[option.get("id").asString]
                "model" -> native.call(store, "getAgentModel-", id)
                "mode" -> native.call(store, "getAgentMode-", id)
                else -> null
            } as? String
            if (value != null) add(PreferenceReflection.setting(option, value))
        } }
    }
    override fun write(key: String, option: JsonObject, value: String) {
        val store = store(); val id = id(key)
        when (option.get("operation").asString) {
            "config" -> if (option.get("category")?.asString != "environment_config") native.call(store, "setAgentConfigOption", id, option.get("id").asString, value)
            "model" -> native.call(store, "setAgentModel-", id, value)
            "mode" -> native.call(store, "setAgentMode-", id, value)
        }
    }
}

private object AirPreferenceStore : NativeClientPreferenceStore {
    private val native get() = PreferenceReflection.native
    private fun reasoningCodec() = native.type("com.intellij.air.shared.prompt.AgentPromptModelsKt", "com.intellij.air")!!
    private fun store() = PreferenceReflection.service("com.intellij.air.frontend.launch.AgentSessionLastUsedStoreImpl", "com.intellij.air")
    private fun parameters(store: Any, key: String): Any? {
        val snapshot = (native.call(store, "getStateFlow") as StateFlow<*>).value!!
        return (native.field(snapshot, "lastUsedSessionParametersByAgentId") as Map<*, *>)[key]
    }
    private fun field(option: JsonObject): String? = when {
        option.get("operation").asString == "model" || option.get("category")?.asString == "model" -> "modelId"
        option.get("operation").asString == "mode" || option.get("category")?.asString == "mode" -> "sessionModeId"
        option.get("category")?.asString == "thought_level" -> "reasoningEffort"
        else -> null // The native Air store has no arbitrary ACP-option dictionary.
    }
    override fun read(key: String, options: JsonArray): JsonArray {
        val parameters = parameters(store(), key) ?: return JsonArray()
        val generation = native.field(parameters, "generationPreferences")!!
        return JsonArray().apply { for (item in options) {
            val option = item.asJsonObject
            val value = when (val field = field(option)) {
                "sessionModeId" -> native.field(parameters, field) as? String
                "modelId" -> native.field(generation, field) as? String
                "reasoningEffort" -> native.field(generation, field)?.let {
                    native.call(reasoningCodec(), "explicitReasoningIdOrNull", it) as? String
                }
                else -> null
            }
            if (value != null) add(PreferenceReflection.setting(option, value))
        } }
    }
    override fun write(key: String, option: JsonObject, value: String) {
        val field = field(option) ?: return
        val store = store()
        val parameterType = native.type("com.intellij.air.shared.prompt.AgentSessionParameters", "com.intellij.air")!!
        val previous = parameters(store, key) ?: parameterType.getConstructor().newInstance()
        val generation = native.field(previous, "generationPreferences")!!
        val updatedGeneration = if (field == "sessionModeId") generation else {
            val effort = if (field == "reasoningEffort") {
                native.call(reasoningCodec(), "toAgentPromptReasoningEffortOrNull", value) ?: return
            } else native.field(generation, "reasoningEffort")
            native.call(generation, "copy", if (field == "modelId") value else native.field(generation, "modelId"), effort,
                native.field(generation, "planReasoningEffort"), native.field(generation, "modelDisplayName"))!!
        }
        val updated = native.call(previous, "copy", native.field(previous, "launchMode"),
            if (field == "sessionModeId") value else native.field(previous, "sessionModeId"), updatedGeneration,
            native.field(previous, "planMode"), native.field(previous, "agentOptionId"))!!
        native.call(store, "recordLastUsedSessionParameters-", key, updated)
    }
}
