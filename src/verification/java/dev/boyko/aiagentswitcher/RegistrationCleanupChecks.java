package dev.boyko.aiagentswitcher;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.Comparator;

/** Synthetic files only: ownership, backup, idempotence and fail-closed cleanup. */
public final class RegistrationCleanupChecks {
    private static Object registration;
    private static Path base;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static boolean remove(boolean current, boolean legacy) throws Exception {
        return (Boolean) registration.getClass().getMethod("removeRegistrations", boolean.class, boolean.class)
            .invoke(registration, current, legacy);
    }
    private static String quoted(Path path) { return "\"" + path.toString().replace("\\", "\\\\") + "\""; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("aiaps-registration-check-");
        String previous = System.getProperty("aiaps.home");
        System.setProperty("aiaps.home", root.toString());
        try {
            base = root.resolve(".jetbrains");
            Path own = Files.createDirectories(base.resolve("ai-agent-project-switcher"));
            Path legacy = Files.createDirectories(base.resolve("codex-project-switcher"));
            Path node = root.resolve("node.exe"), npx = root.resolve("npx.cmd");
            Path home = Files.createDirectories(root.resolve("personal"));
            Files.writeString(home.resolve("auth.json"), "synthetic credentials");
            Files.writeString(own.resolve("conversations.json"), "synthetic history");
            String owned = "{\"command\":" + quoted(node) + ",\"args\":[" + quoted(own.resolve("router.js")) + "]}";
            Files.writeString(own.resolve("registration.json"), owned);
            Files.writeString(legacy.resolve("routes.json"), "{\"adapter\":{\"command\":" + quoted(node)
                + "},\"homes\":{\"Personal\":" + quoted(home) + ",\"Work\":" + quoted(root.resolve("work")) + ",\"Build\":" + quoted(root.resolve("build")) + "}}");
            String personal = "{\"command\":" + quoted(npx) + ",\"args\":[\"-y\",\"@agentclientprotocol/codex-acp\"],\"env\":{\"CODEX_HOME\":" + quoted(home) + "}}";
            String build = personal.replace(quoted(home), quoted(root.resolve("build")));
            String auto = "{\"command\":" + quoted(node) + ",\"args\":[" + quoted(legacy.resolve("cps-router.js")) + "," + quoted(legacy.resolve("routes.json")) + "]}";
            String before = "{\"other_setting\":42,\"agent_servers\":{\"AI Multi\":" + owned
                + ",\"AI Auto (Project Switcher)\":" + owned + ",\"Unrelated\":{\"command\":\"keep\"},"
                + "\"Codex - Personal (CPS)\":" + personal + ",\"Codex - Auto (CPS experimental)\":" + auto
                + ",\"Codex - Build (CPS)\":" + build
                + ",\"Codex - Work (CPS)\":{\"command\":\"manually modified\"}}}";
            Path acp = base.resolve("acp.json");
            Files.writeString(acp, before);
            registration = Class.forName("dev.boyko.aiagentswitcher.RegistrationCleanup").getField("INSTANCE").get(null);
            check(remove(false, true), "Legacy cleanup changed nothing");
            String cleaned = Files.readString(acp);
            check(cleaned.contains("AI Multi") && cleaned.contains("Unrelated"), "Legacy cleanup removed another plugin");
            check(!cleaned.contains("Codex - Personal") && !cleaned.contains("CPS experimental"), "Owned CPS entries remain");
            check(!cleaned.contains("Codex - Build"), "Owned custom-name legacy profile remains");
            check(cleaned.contains("manually modified"), "Manually changed CPS entry removed");
            try (var backups = Files.list(base)) {
                Path backup = backups.filter(p -> p.getFileName().toString().startsWith("acp.json.aiaps-backup-")).findFirst().orElseThrow();
                check(Files.readString(backup).equals(before), "Backup differs from original bytes");
            }
            check(!remove(false, true), "Legacy cleanup is not idempotent");
            check(remove(true, false), "Own cleanup changed nothing");
            cleaned = Files.readString(acp);
            check(!cleaned.contains("AI Multi") && !cleaned.contains("AI Auto"), "Owned current/previous names remain");
            check(cleaned.contains("other_setting") && cleaned.contains("Unrelated"), "Unrelated settings removed");
            check(Files.readString(home.resolve("auth.json")).equals("synthetic credentials"), "Credentials changed");
            check(Files.exists(own.resolve("conversations.json")), "Conversation history removed");
            Files.writeString(own.resolve("profile-registrations.json"), "{\"AI Multi · Personal\":" + owned + ",\"AI Multi · Work\":" + owned + "}");
            Files.writeString(acp, "{\"agent_servers\":{\"AI Multi · Personal\":" + owned + ",\"AI Multi · Work\":{\"command\":\"manual\"},\"Unrelated\":{\"command\":\"keep\"}}}");
            check(remove(true, false), "Profile entries were not cleaned up");
            cleaned = Files.readString(acp);
            check(!cleaned.contains("Personal") && cleaned.contains("manual") && cleaned.contains("Unrelated"), "Profile cleanup crossed ownership boundaries");
            Files.writeString(acp, "{\"agent_servers\":{\"AI Multi\":{\"command\":\"changed\"}}}");
            check(!remove(true, false), "Modified AI Multi registration removed");
            Files.delete(own.resolve("registration.json"));
            Files.writeString(acp, "{\"agent_servers\":{\"AI Multi\":" + owned + "}}");
            check(!remove(true, false), "Registration removed without ownership manifest");
            Files.writeString(acp, "invalid JSON");
            try { remove(true, true); throw new AssertionError("Invalid JSON accepted"); }
            catch (InvocationTargetException expected) { check(Files.readString(acp).equals("invalid JSON"), "Invalid file rewritten"); }
            Files.delete(acp);
            check(!remove(true, true) && !Files.exists(acp), "Missing ACP file recreated");
            System.out.println("PASS: registration cleanup ownership, CPS migration, backups, idempotence and retained credentials/history");
        } finally {
            if (previous == null) System.clearProperty("aiaps.home"); else System.setProperty("aiaps.home", previous);
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
}
