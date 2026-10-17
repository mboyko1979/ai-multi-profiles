package dev.boyko.aiagentswitcher;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Comparator;

/** Standalone filesystem regression checks; all credentials are synthetic and temporary. */
public final class ProfileCredentialsChecks {
    private static Object credentials;
    private static Class<?> entryType;
    private static Path profiles;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Object profile(String name, String agent) throws Exception {
        Object entry = entryType.getConstructor().newInstance();
        entryType.getMethod("setAgentId", String.class).invoke(entry, agent);
        Path home = profiles.resolve(name);
        Files.createDirectories(home);
        entryType.getMethod("setProfileHome", String.class).invoke(entry, home.toString());
        return entry;
    }

    private static boolean hasCredentials(Object entry) throws Exception {
        return (Boolean) credentials.getClass().getMethod("hasCredentials", entryType).invoke(credentials, entry);
    }

    private static void revoke(Object entry) throws Exception {
        credentials.getClass().getMethod("revoke", entryType).invoke(credentials, entry);
    }

    private static void refused(Object entry) throws Exception {
        try {
            revoke(entry);
            throw new AssertionError("Unsafe authorization path was accepted");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException, "Unexpected refusal: " + expected.getCause());
        }
    }

    public static void main(String[] args) throws Exception {
        Path taskRoot = Files.createTempDirectory("aiaps-revoke-test-").toRealPath();
        String previous = System.getProperty("aiaps.home");
        System.setProperty("aiaps.home", taskRoot.toString());
        try {
            profiles = taskRoot.resolve(".jetbrains/ai-agent-project-switcher/profiles");
            entryType = Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$Entry");
            credentials = Class.forName("dev.boyko.aiagentswitcher.ProfileCredentials").getField("INSTANCE").get(null);
            Object personal = profile("personal", "acp.registry.codex-acp");
            profile("company", "acp.registry.codex-acp");
            Path home = profiles.resolve("personal");
            check(!hasCredentials(personal), "Revoke would be offered before authorization");
            Files.writeString(home.resolve("auth.json"), "synthetic personal credentials");
            check(hasCredentials(personal), "Existing auth.json was not recognized");
            Files.writeString(profiles.resolve("company/auth.json"), "synthetic company credentials");
            Files.writeString(home.resolve("config.toml"), "model='test'");
            revoke(personal);
            check(!Files.exists(home.resolve("auth.json")), "Codex auth.json was not physically deleted");
            check(!hasCredentials(personal), "Revoke remains available after auth.json is removed");
            check(Files.readString(profiles.resolve("company/auth.json")).equals("synthetic company credentials"), "Other profile changed");
            check(Files.exists(home.resolve("config.toml")), "Non-authentication data deleted");
            String first = Files.readString(home.resolve(".aiaps-auth-revision"));
            check(!first.isBlank(), "Revocation revision missing");
            revoke(personal);
            check(!Files.readString(home.resolve(".aiaps-auth-revision")).equals(first), "Repeated revoke did not invalidate stale logins");
            Object grok = profile("grok", "acp.registry.grok-build");
            Files.writeString(profiles.resolve("grok/auth.json"), "synthetic grok credentials");
            revoke(grok);
            check(!Files.exists(profiles.resolve("grok/auth.json")), "Grok auth.json was not deleted");
            Object unsafe = profile("unsafe", "acp.registry.codex-acp");
            entryType.getMethod("setProfileHome", String.class).invoke(unsafe, taskRoot.toString());
            refused(unsafe);
            entryType.getMethod("setProfileHome", String.class).invoke(unsafe, profiles.toString());
            refused(unsafe);
            Object directory = profile("directory", "acp.registry.codex-acp");
            Files.createDirectory(profiles.resolve("directory/auth.json"));
            refused(directory);
            check(!Files.exists(profiles.resolve("directory/.aiaps-auth-revision")), "Refused revoke modified revision");
            refused(profile("unsupported", "acp.registry.other"));
            Object redirected = profile("redirected", "acp.registry.codex-acp");
            Path redirectedAuth = profiles.resolve("redirected/auth.json");
            boolean linksSupported = true;
            try { Files.createSymbolicLink(redirectedAuth, profiles.resolve("company/auth.json")); }
            catch (UnsupportedOperationException | java.io.IOException unavailable) { linksSupported = false; }
            if (linksSupported) {
                refused(redirected);
                check(Files.exists(profiles.resolve("company/auth.json")), "Redirected auth target deleted");
            }
            System.out.println("PASS: physical Codex/Grok credential deletion, profile isolation, preserved config, repeated revoke, unsafe paths, unsupported agent"
                    + (linksSupported ? ", symlink refusal" : "; symlink creation unavailable on this host"));
        } finally {
            if (previous == null) System.clearProperty("aiaps.home"); else System.setProperty("aiaps.home", previous);
            try (var files = Files.walk(taskRoot)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(taskRoot)) throw new IllegalStateException("Unsafe test cleanup");
                    Files.delete(path);
                }
            }
        }
    }
}
