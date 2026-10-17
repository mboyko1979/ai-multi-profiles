package dev.boyko.aiagentswitcher

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal data class AgentAccountIdentity(val email: String? = null, val accountId: String? = null,
    val status: String = "Account unknown", val provider: String = "", val verified: Boolean = false)

internal interface AgentAccountIdentityProvider {
    fun supports(profile: AgentProfiles.Entry): Boolean
    fun discover(profile: AgentProfiles.Entry): AgentAccountIdentity
}

/** Offline identity hints only. Local token expiry does not describe the agent's renewable login state. */
internal object CodexAccountIdentityProvider : AgentAccountIdentityProvider {
    override fun supports(profile: AgentProfiles.Entry) = profile.agentId == "acp.registry.codex-acp"
    override fun discover(profile: AgentProfiles.Entry): AgentAccountIdentity {
        val unknown = AgentAccountIdentity(provider = "Codex")
        return try {
            if (profile.command.isBlank() || (profile.profileHome.isBlank() && !profile.advancedOverrides))
                return unknown.copy(status = "Apply this profile before discovering its account")
            val env = ProfileConfiguration.environment(profile)
            if (!env["OPENAI_API_KEY"].isNullOrBlank()) return unknown.copy(status = "API key environment · account identity unavailable")
            val home = env["CODEX_HOME"]?.takeIf { it.isNotBlank() } ?: return unknown
            if (!Path.of(home).isAbsolute) return unknown // A relative home can resolve inside the adapter's child process.
            val file = Path.of(home).resolve("auth.json")
            if (!Files.isRegularFile(file) || Files.size(file) > 1_048_576) return unknown
            val auth = JsonParser.parseString(Files.readString(file)).asJsonObject
            if (auth.get("auth_mode")?.asString == "apikey" ||
                !auth.get("OPENAI_API_KEY")?.takeUnless { it.isJsonNull }?.asString.isNullOrBlank())
                return unknown.copy(status = "API key · account identity unavailable")
            val tokens = auth.getAsJsonObject("tokens") ?: return unknown
            val raw = tokens.get("id_token")?.asString ?: return unknown
            val parts = raw.split('.')
            if (parts.size != 3 || parts[1].length > 131072) return unknown
            val claims = JsonParser.parseString(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).asJsonObject
            fun safe(value: String?): String? = value?.takeIf { it.length <= 320 && it.none { c -> c.isISOControl() } }
            val email = safe(claims.get("email")?.asString)?.takeIf { it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) }
            val claimedAccount = safe(claims.getAsJsonObject("https://api.openai.com/auth")?.get("chatgpt_account_id")?.asString)
            val recordedAccount = safe(tokens.get("account_id")?.asString)
            if (claimedAccount != null && recordedAccount != null && claimedAccount != recordedAccount) return unknown
            val account = claimedAccount ?: recordedAccount
            if (email == null && account == null) unknown else AgentAccountIdentity(email, account,
                "", "Codex")
        } catch (_: Exception) { unknown } // Do not propagate authentication content or parser exceptions.
    }
}

internal object ProfileAccounts {
    private data class Cached(val identity: AgentAccountIdentity, val at: Long)
    private val providers: List<AgentAccountIdentityProvider> = listOf(CodexAccountIdentityProvider, LocalAccountIdentityProvider)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    fun discover(profile: AgentProfiles.Entry): AgentAccountIdentity =
        providers.firstOrNull { it.supports(profile) }?.discover(profile) ?: AgentAccountIdentity(status = "Account identity unavailable for this agent")
    fun request(profile: AgentProfiles.Entry, refresh: Boolean = false): AgentAccountIdentity {
        // Authentication is deliberately never serialized in profile settings or the capabilities cache.
        val key = profile.id + ":" + ProfileConfiguration.key(profile)
        val found = cache[key]
        if ((refresh || found == null || System.currentTimeMillis() - found.at > 30000) && pending.add(key)) {
            val draft = profile.snapshot()
            ApplicationManager.getApplication().executeOnPooledThread {
                try { cache[key] = Cached(discover(draft), System.currentTimeMillis()) }
                finally {
                    pending.remove(key)
                    ApplicationManager.getApplication().messageBus.syncPublisher(ProfilesListener.TOPIC).changed()
                }
            }
        }
        return found?.identity ?: AgentAccountIdentity()
    }
    fun duplicates(identities: List<Pair<String, AgentAccountIdentity>>): List<String> = identities
        .filter { it.second.verified && !it.second.accountId.isNullOrBlank() && it.second.provider.isNotBlank() }
        .groupBy { it.second.provider to it.second.accountId }.values.filter { it.size > 1 }
        .map { "${it.joinToString(" and ") { p -> p.first }} appear to use the same account." }
}

/** Shared ACP fallback for public identity metadata in the selected profile's local storage. */
internal object LocalAccountIdentityProvider : AgentAccountIdentityProvider {
    override fun supports(profile: AgentProfiles.Entry) = profile.agentId != "acp.registry.codex-acp"
    override fun discover(profile: AgentProfiles.Entry): AgentAccountIdentity {
        val unknown = AgentAccountIdentity(status = "Agent does not expose local account identity", provider = profile.agentId)
        return try {
            if (profile.agentId == "acp.registry.grok-build" && !ProfileConfiguration.environment(profile)["XAI_API_KEY"].isNullOrBlank())
                return unknown.copy(status = "API key environment · account identity unavailable")
            // Match router launch precedence, and only inspect explicitly selected directories.
            val selected = parseEnvironment(profile.environment) + ProfileHomes.environment(profile)
            val roots = selected.filterKeys { Regex("(?:_HOME|_CONFIG_DIR)$|^(?:HOME|USERPROFILE)$", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .values.mapNotNull { runCatching { Path.of(it).takeIf { p -> p.isAbsolute } }.getOrNull() }.distinct()
            val identities = mutableListOf<AgentAccountIdentity>()
            fun safe(value: com.google.gson.JsonElement?): String? = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.takeIf { it.isNotEmpty() && it.length <= 320 && it.none { c -> c.isISOControl() } }
            fun visit(record: com.google.gson.JsonObject, depth: Int = 0) {
                if (depth > 4) return
                val email = listOf("email", "account_email", "emailAddress").firstNotNullOfOrNull { safe(record.get(it)) }
                    ?.takeIf { it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) }
                val account = listOf("user_id", "account_id", "accountId", "userId").firstNotNullOfOrNull { safe(record.get(it)) }
                if (email != null || account != null) {
                    identities += unknown.copy(email = email, accountId = account, status = "")
                }
                record.entrySet().forEach { (key, value) ->
                    if ((key in listOf("user", "account", "profile", "oauthAccount") || "::" in key) && value.isJsonObject)
                        visit(value.asJsonObject, depth + 1)
                }
            }
            for (root in roots) for (name in listOf("auth.json", ".credentials.json")) runCatching {
                val file = root.resolve(name)
                if (Files.isRegularFile(file) && Files.size(file) <= 1_048_576) {
                    val content = JsonParser.parseString(Files.readString(file).removePrefix("\uFEFF"))
                    if (content.isJsonObject) visit(content.asJsonObject)
                }
            }
            val unique = identities.distinctBy { it.email to it.accountId }
            when (unique.size) {
                1 -> unique.single()
                0 -> unknown
                else -> unknown.copy(status = "Multiple local accounts · identity ambiguous")
            }
        } catch (_: Exception) { unknown }
    }
}
