package dev.boyko.aiagentswitcher;

/** The composer writes the same Grok config key the router reads. */
public final class GrokPermissionModeChecks {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("dev.boyko.aiagentswitcher.GrokPermissionMode");
        Object mode = type.getField("INSTANCE").get(null);
        eq(call(mode, "readMode", "[ui]\npermission_mode = \"auto\"\n"), "auto");
        eq(call(mode, "readMode", "[cli]\npermission_mode = \"auto\"\n[ui]\nyolo = false\n"), null);
        String rewritten = (String) call(mode, "writeText", "[cli]\nauto_update = true\n\n[ui]\nyolo = false\ncompact_mode = false\n", "auto");
        eq(call(mode, "readMode", rewritten), "auto");
        if (!rewritten.contains("yolo = false")) throw new AssertionError(rewritten);
        eq(call(mode, "readMode", call(mode, "writeText", rewritten, "ask")), "ask");
        eq(call(mode, "readMode", call(mode, "writeText", "", "always-approve")), "always-approve");
        System.out.println("grok permission mode config checks passed");
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (var method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            return method.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }

    private static void eq(Object actual, Object expected) {
        if (actual == null ? expected != null : !actual.equals(expected))
            throw new AssertionError("expected " + expected + " but was " + actual);
    }
}
