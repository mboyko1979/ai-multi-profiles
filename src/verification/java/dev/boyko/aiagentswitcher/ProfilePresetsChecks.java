package dev.boyko.aiagentswitcher;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Synthetic account metadata only; never reads production authentication data. */
public final class ProfilePresetsChecks {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> cls = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        for (Method method : cls.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == args.length) {
            boolean match = true;
            for (int i = 0; i < args.length; i++) if (args[i] != null && !method.getParameterTypes()[i].isInstance(args[i]) && !method.getParameterTypes()[i].isPrimitive()) match = false;
            if (match) return method.invoke(target instanceof Class<?> ? null : target, args);
        }
        throw new NoSuchMethodException(name);
    }
    static Object singleton(String name) throws Exception { return Class.forName("dev.boyko.aiagentswitcher." + name).getField("INSTANCE").get(null); }
    static String jwt(String email, String account, long exp) {
        String body = "{\"email\":\"" + email + "\",\"exp\":" + exp + ",\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"" + account + "\"}}";
        return "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".signature";
    }
    static void auth(Path home, String email, String account, long exp) throws Exception {
        Files.createDirectories(home);
        Files.writeString(home.resolve("auth.json"), "{\"tokens\":{\"id_token\":\"" + jwt(email, account, exp) + "\",\"access_token\":\"SYNTHETIC_SECRET\",\"refresh_token\":\"SYNTHETIC_SECRET\"}}");
    }
    static void checkProfileRegistrations() throws Exception {
        Object parser = Class.forName("com.google.gson.JsonParser");
        String fixture = "{\"AI Multi\":{\"command\":\"router\"},\"Unrelated\":{\"command\":\"keep\"},"
            + "\"AI Multi · Personal\":{\"command\":\"owned\"},\"AI Multi · Work\":{\"command\":\"manual\"}}";
        Object servers = call(call(parser, "parseString", fixture), "getAsJsonObject");
        Object previous = call(call(parser, "parseString", "{\"AI Multi · Personal\":{\"command\":\"owned\"},"
            + "\"AI Multi · Work\":{\"command\":\"old\"},\"Unrelated\":{\"command\":\"keep\"}}"), "getAsJsonObject");
        Object registrations = singleton("ProfileRegistrations");
        Object remaining = call(registrations, "cleanup", servers, previous);
        check(!servers.toString().contains("Personal"), "Owned profile alias remains");
        check(servers.toString().contains("manual") && servers.toString().contains("Unrelated")
            && servers.toString().contains("router"), "Cleanup changed manual, unrelated or shared registration");
        check(remaining.toString().equals("{}"), "Cleanup retained profile alias ownership");
        String cleaned = servers.toString();
        call(registrations, "cleanup", servers, previous);
        check(cleaned.equals(servers.toString()), "Profile alias cleanup is not idempotent");
        Object emptyManifest = call(call(parser, "parseString", "{}"), "getAsJsonObject");
        call(registrations, "cleanup", servers, emptyManifest);
        check(cleaned.equals(servers.toString()), "Repeated startup changed the shared registry");
    }
    public static void main(String[] args) throws Exception {
        checkProfileRegistrations();
        Path root = Files.createTempDirectory("ai-multi-preset-checks-");
        try {
            Class<?> entryClass = Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$Entry");
            Class<?> serializer = Class.forName("com.intellij.util.xmlb.XmlSerializer");
            Class<?> jdom = Class.forName("com.intellij.openapi.util.JDOMUtil");
            // Load the old XML shape without any new fields.
            Object xml = call(jdom, "load", "<Entry><option name=\"id\" value=\"stable-id\"/><option name=\"name\" value=\"Personal\"/><option name=\"command\" value=\"mock-acp\"/><option name=\"agentId\" value=\"acp.registry.codex-acp\"/></Entry>");
            Object entry = call(serializer, "deserialize", xml, entryClass);
            check(call(entry, "getId").equals("stable-id"), "Legacy profile ID changed");
            check(((List<?>)call(entry, "getSessionSettings")).isEmpty(), "Migration invented defaults");
            check(call(entry, "getInstructionsSource").equals("none"), "Migration injected instructions");
            Class<?> settingClass = Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$SessionSetting");
            Object setting = settingClass.getConstructor(String.class, String.class, String.class, String.class).newInstance("acp.registry.codex-acp", "config", "effort", "high");
            call(entry, "setSessionSettings", new ArrayList<>(List.of(setting)));
            Object draft = call(entry, "snapshot");
            call(((List<?>)call(draft, "getSessionSettings")).getFirst(), "setValue", "low");
            check(call(((List<?>)call(entry, "getSessionSettings")).getFirst(), "getValue").equals("high"), "Draft changed saved value");
            Object roundtrip = call(serializer, "deserialize", call(serializer, "serialize", entry), entryClass);
            check(call(((List<?>)call(roundtrip, "getSessionSettings")).getFirst(), "getValue").equals("high"), "XML roundtrip lost preset");
            Object baseline = settingClass.getConstructor(String.class, String.class, String.class, String.class).newInstance("acp.registry.codex-acp", "config", "effort", "low");
            call(entry, "setAgentDefaults", new ArrayList<>(List.of(baseline)));
            call(entry, "setDefaultsAgentId", "acp.registry.codex-acp");call(entry, "setDefaultsObservedAt", 123L);
            Object frozenDraft=call(entry,"snapshot");
            call(((List<?>)call(frozenDraft,"getAgentDefaults")).getFirst(),"setValue","high");
            check(call(((List<?>)call(entry,"getAgentDefaults")).getFirst(),"getValue").equals("low"),"Draft changed frozen defaults");
            Object frozenRoundtrip=call(serializer,"deserialize",call(serializer,"serialize",entry),entryClass);
            check(call(((List<?>)call(frozenRoundtrip,"getAgentDefaults")).getFirst(),"getValue").equals("low"),"XML lost frozen defaults");
            check(call(frozenRoundtrip,"getDefaultsObservedAt").equals(123L),"XML lost refresh timestamp");
            check(call(frozenRoundtrip,"getDefaultsAgentId").equals("acp.registry.codex-acp"),"XML lost defaults agent identity");
            Object profiles = Class.forName("dev.boyko.aiagentswitcher.AgentProfiles").getConstructor().newInstance();
            call(profiles, "replace", List.of(entry));
            ((List<?>)call(draft, "getSessionSettings")).clear();
            check(((List<?>)call(((List<?>)call(profiles, "all")).getFirst(), "getSessionSettings")).size() == 1, "Profile switching lost saved settings");
            call(entry, "setInheritEnvironment", false);
            call(entry, "setProfileHome", root.resolve("personal").toString());
            call(entry, "setEnvironment", "CODEX_HOME=" + root.resolve("wrong") + "\nPROFILE_FLAG=personal");
            Object configuration = singleton("ProfileConfiguration");
            Map<?,?> env = (Map<?,?>)call(configuration, "environment", entry);
            check(env.get("CODEX_HOME").equals(root.resolve("personal").toString()), "Profile-specific home did not win");
            check(env.get("PROFILE_FLAG").equals("personal") && !env.containsKey("PATH"), "Environment inheritance incorrect");
            Object provider = singleton("CodexAccountIdentityProvider");
            Object unknown = call(provider, "discover", entry);
            check(call(unknown, "getEmail") == null, "Missing auth claimed an account");
            auth(root.resolve("personal"), "personal@example.com", "personal-account", System.currentTimeMillis()/1000 + 3600);
            Object personal = call(provider, "discover", entry);
            check(call(personal, "getEmail").equals("personal@example.com"), "Personal identity wrong");
            check(!(Boolean)call(personal, "getVerified"), "Unsigned identity claimed verified login");
            check(!personal.toString().contains("SYNTHETIC_SECRET"), "Credential exposed in identity");
            Object work = call(entry, "snapshot");call(work, "setProfileHome", root.resolve("work").toString());
            auth(root.resolve("work"), "work@example.com", "work-account", 1);
            Object workIdentity = call(provider, "discover", work);
            check(call(workIdentity, "getEmail").equals("work@example.com"), "Work used personal identity");
            check(call(workIdentity, "getStatus").equals("") && !(Boolean)call(workIdentity, "getVerified"), "Local token expiry must not report login state");
            Files.writeString(root.resolve("work/auth.json"), "broken data with SYNTHETIC_SECRET");
            check(call(call(provider, "discover", work), "getEmail") == null, "Malformed data claimed identity");
            Object accounts = singleton("ProfileAccounts");
            Class<?> identityClass = Class.forName("dev.boyko.aiagentswitcher.AgentAccountIdentity");
            Constructor<?> identity = identityClass.getConstructor(String.class, String.class, String.class, String.class, boolean.class);
            Object reliable = identity.newInstance("one@example.com", "same-id", "Verified", "fixture-provider", true);
            Class<?> pair = Class.forName("kotlin.Pair");Constructor<?> pairCtor = pair.getConstructor(Object.class, Object.class);
            List<Object> same = List.of(pairCtor.newInstance("Personal", reliable), pairCtor.newInstance("Work", reliable));
            check(((List<?>)call(accounts, "duplicates", same)).size()==1, "Reliable duplicate IDs not detected");
            check(((List<?>)call(accounts, "duplicates", List.of(pairCtor.newInstance("Personal", personal), pairCtor.newInstance("Work", personal)))).isEmpty(), "Unsigned IDs triggered duplicate warning");
            call(work, "setAgentId", "unknown-provider");
            check(call(call(accounts, "discover", work), "getEmail") == null, "Unknown provider inherited Codex identity");
            Path genericHome = root.resolve("generic");Files.createDirectories(genericHome);
            Object generic = call(work, "snapshot");call(generic, "setProfileHome", genericHome.toString());
            call(generic, "setEnvironment", "");call(generic, "setAgentId", "acp.registry.grok-build");
            String metadata = "{\"email\":\"grok@example.com\",\"user_id\":\"grok-user\",\"key\":\"SYNTHETIC_SECRET\",\"refresh_token\":\"SYNTHETIC_SECRET\",\"expires_at\":\"2099-01-01T00:00:00Z\"}";
            Files.writeString(genericHome.resolve("auth.json"), "{\"issuer::client\":" + metadata + "}");
            Object genericIdentity = call(accounts, "discover", generic);
            check(call(genericIdentity, "getEmail").equals("grok@example.com"), "OAuth-map identity missing");
            check(call(genericIdentity, "getAccountId").equals("grok-user"), "Generic account ID missing");
            check(!(Boolean)call(genericIdentity, "getVerified") && !genericIdentity.toString().contains("SYNTHETIC_SECRET"), "Generic identity exposed credentials or claimed verified login");
            call(generic, "setEnvironment", "XAI_API_KEY=synthetic");
            check(call(call(accounts, "discover", generic), "getEmail") == null, "API key override displayed a cached account");
            call(generic, "setEnvironment", "");call(generic, "setAgentId", "acp.registry.future-vendor");
            Files.writeString(genericHome.resolve("auth.json"), "{\"account\":" + metadata + "}");
            check(call(call(accounts, "discover", generic), "getEmail").equals("grok@example.com"), "Common metadata fallback depends on vendor ID");
            Files.writeString(genericHome.resolve("auth.json"), "{\"issuer::one\":" + metadata + ",\"issuer::two\":" + metadata.replace("grok-user", "other-user") + "}");
            check(call(call(accounts, "discover", generic), "getEmail") == null, "Multiple local accounts were guessed");
            Files.writeString(genericHome.resolve("auth.json"), "{\"account\":" + metadata.replace("2099", "2000") + "}");
            Object expiredIdentity = call(accounts, "discover", generic);
            check(call(expiredIdentity, "getEmail").equals("grok@example.com") && call(expiredIdentity, "getStatus").equals("") && !(Boolean)call(expiredIdentity, "getVerified"), "Local credential expiry must not report login state");
            Files.writeString(genericHome.resolve("auth.json"), " ".repeat(1048577));
            check(call(call(accounts, "discover", generic), "getEmail") == null, "Oversized identity file was accepted");
            String originalHome = System.getProperty("aiaps.home");
            try {
                System.setProperty("aiaps.home", root.toString());
                Object catalog = singleton("ProfileCatalog");
                Class<?> parser = Class.forName("com.google.gson.JsonParser");
                String payload = "{\"observedAt\":1,\"warnings\":[],\"options\":[{\"id\":\"model\",\"operation\":\"config\",\"name\":\"Model\",\"type\":\"select\",\"category\":\"model\",\"currentValue\":\"a\",\"options\":[{\"value\":\"a\",\"name\":\"A\"},{\"value\":\"b\",\"name\":\"B\"}]}]}";
                Object defaults = call(configuration, "parseCapabilities", call(call(parser, "parseString", payload), "getAsJsonObject"));
                Object emptyDefaultsProfile = call(entry, "snapshot");
                call(emptyDefaultsProfile, "setSessionSettings", new ArrayList<>());
                call(emptyDefaultsProfile, "setAgentDefaults", new ArrayList<>());
                check(!(Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "assistant"), "Empty profile shows Assistant defaults");
                check(!(Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "air"), "Empty profile shows Air defaults");
                Object assistantDefaults = call(configuration, "parseCapabilities", call(call(parser, "parseString", payload.replace("\"observedAt\":1", "\"host\":\"assistant\",\"observedAt\":1")), "getAsJsonObject"));
                call(configuration, "captureDefaults", emptyDefaultsProfile, assistantDefaults);
                check((Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "assistant"), "Saved Assistant defaults hidden");
                check(!(Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "air"), "Assistant defaults enabled Air dropdowns");
                Object modelOption = ((List<?>)call(defaults, "getOptions")).getFirst();
                call(configuration, "select", emptyDefaultsProfile, modelOption, "b", "air");
                call(configuration, "removeDefaults", emptyDefaultsProfile, "assistant");
                check(!(Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "assistant"), "Remove retained Assistant defaults");
                check((Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "air"), "Remove erased Air's explicit setting");
                call(configuration, "removeDefaults", emptyDefaultsProfile, "air");
                check(!(Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "air"), "Remove retained explicit Air setting");
                call(configuration, "captureDefaults", emptyDefaultsProfile, defaults);
                call(configuration, "removeDefaults", emptyDefaultsProfile, "assistant");
                check(!(Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "assistant"), "Legacy defaults retained for removed client");
                check((Boolean)call(configuration, "hasSavedDefaults", emptyDefaultsProfile, "air"), "Legacy defaults removed from other client");
                call(configuration, "captureDefaults", entry, defaults);
                check(call(((List<?>)call(entry,"getAgentDefaults")).getFirst(),"getValue").equals("a"),"Refresh did not capture default");
                Object invalid = call(configuration,"parseCapabilities",call(call(parser,"parseString",payload.replace("currentValue\":\"a", "currentValue\":\"invalid")),"getAsJsonObject"));
                try { call(configuration,"captureDefaults",entry,invalid); throw new AssertionError("Unavailable default accepted"); }
                catch (InvocationTargetException expected) { check(expected.getCause() instanceof IllegalArgumentException,"Unexpected refresh failure"); }
                check(call(((List<?>)call(entry,"getAgentDefaults")).getFirst(),"getValue").equals("a"),"Failed refresh replaced defaults");
                call(catalog, "store", entry, defaults, true);
                check(call(catalog, "cached", entry) != null, "Authorized catalog was not persisted");
                Object live = call(configuration, "parseCapabilities", call(call(parser, "parseString", payload.replace("currentValue\":\"a", "currentValue\":\"b")), "getAsJsonObject"));
                call(catalog, "store", entry, live, false);
                Object cachedOptions = call(call(catalog, "cached", entry), "getOptions");
                check(call(((List<?>)cachedOptions).getFirst(), "getCurrentValue").equals("a"), "Chat selection became profile default");
                String metadataPayload = """
                    {"host":"assistant","clientKey":"native-assistant","observedAt":2,"warnings":[],"options":[
                      {"id":"model","operation":"config","name":"Model","type":"select","category":"model","currentValue":"a","options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]},
                      {"id":"effort","operation":"config","name":"Reasoning","type":"select","category":"thought_level","currentValue":"high","modelDefault":"medium","defaultSource":"recommendedValue","options":[{"value":"low","name":"Low"},{"value":"medium","name":"Medium"},{"value":"high","name":"High"}]}],
                      "models":[{"modelId":"b","warnings":[],"options":[{"id":"effort","operation":"config","name":"Reasoning","type":"select","category":"thought_level","currentValue":"high","modelDefault":"low","defaultSource":"recommendedValue","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]}]}]}
                    """;
                Object recommendationMetadata = call(configuration,"parseCapabilities",call(call(parser,"parseString",metadataPayload),"getAsJsonObject"));
                List<?> parsedOptions=(List<?>)call(recommendationMetadata,"getOptions");
                check(call(parsedOptions.get(1),"getCurrentValue").equals("high")&&call(parsedOptions.get(1),"getModelDefault").equals("medium"),"Session value became model recommendationMetadata");
                call(catalog,"store",entry,recommendationMetadata,true);
                Object metadataCached=call(catalog,"cached",entry,"assistant");
                check(call(((List<?>)call(metadataCached,"getOptions")).get(1),"getModelDefault").equals("medium"),"Metadata cache lost recommendation");
                check(((List<?>)call(metadataCached,"getModels")).size()==1,"Metadata cache lost model map");
                check(call(catalog,"cached",entry,"air")==null,"Client catalogs were mixed");
                String selections=call(entry,"getSessionSettings").toString();
                call(configuration,"captureModelDefaults",entry,recommendationMetadata);
                check(call(entry,"getSessionSettings").toString().equals(selections),"Metadata refresh changed explicit user settings");
                check(((List<?>)call(entry,"getAgentDefaults")).stream().anyMatch(x->{try{return call(x,"getId").equals("effort")&&call(x,"getValue").equals("medium");}catch(Exception e){throw new RuntimeException(e);}}),"Inherited reasoning used session value");
                Object separate=call(entry,"snapshot"),reasoningOption=parsedOptions.get(1);
                call(configuration,"select",separate,reasoningOption,"low","air");
                check(call(configuration,"saved",separate,reasoningOption,"assistant").equals("high"),"Air changed migrated Assistant preset");
                check(call(configuration,"saved",separate,reasoningOption,"air").equals("low"),"Air selection was not saved");
                call(configuration,"select",separate,reasoningOption,null,"air");
                check(call(configuration,"saved",separate,reasoningOption,"air")==null,"Use default restored legacy Air override");
                check(call(configuration,"saved",separate,reasoningOption,"assistant").equals("high"),"Clearing Air changed Assistant");
                Object separateXml=call(serializer,"deserialize",call(serializer,"serialize",separate),entryClass);
                check(call(configuration,"saved",separateXml,reasoningOption,"assistant").equals("high"),"XML lost per-client scope");
                check(call(configuration,"saved",separateXml,reasoningOption,"air")==null,"XML mixed client scopes");
                Object airMetadata=call(configuration,"parseCapabilities",call(call(parser,"parseString",metadataPayload.replace("\"assistant\"","\"air\"").replace("\"modelDefault\":\"medium\"","\"modelDefault\":\"low\"")),"getAsJsonObject"));
                call(configuration,"captureModelDefaults",separate,airMetadata);
                check(((List<?>)call(separate,"getAgentDefaults")).stream().anyMatch(x->{try{return call(x,"getHost").equals("assistant")&&call(x,"getId").equals("effort")&&call(x,"getValue").equals("medium");}catch(Exception e){throw new RuntimeException(e);}}),"Air refresh overwrote Assistant metadata");
                Object unknownRoot=call(call(parser,"parseString",metadataPayload),"getAsJsonObject");
                Object unknownReasoning=call(call(call(unknownRoot,"getAsJsonArray","options"),"get",1),"getAsJsonObject");
                call(unknownReasoning,"remove","modelDefault");
                Object missingRecommendation=call(configuration,"parseCapabilities",unknownRoot);
                check(call(((List<?>)call(missingRecommendation,"getOptions")).get(1),"getModelDefault")==null,"Missing recommendation fell back to currentValue");
                Object different = call(entry, "snapshot");call(different, "setProfileHome", root.resolve("different-home").toString());
                check(call(catalog, "cached", different) == null, "Catalog crossed profile account homes");
                Files.writeString(root.resolve("personal/.aiaps-auth-revision"), "revoked");
                check(call(catalog, "cached", entry) == null, "Revoked authorization retained its catalog");
            } finally { if (originalHome == null) System.clearProperty("aiaps.home"); else System.setProperty("aiaps.home", originalHome); }
            System.out.println("PASS: legacy XML migration, stable IDs, preset XML/deep copies, profile environment, account isolation, missing/malformed/expired identity, verified duplicates");
        } finally {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
