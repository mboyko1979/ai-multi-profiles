package dev.boyko.aiagentswitcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Signature shapes pinned from Air 262.8665.497 and 262.8665.521. */
public final class NativeCallCompatibilityChecks {
    private NativeCallCompatibilityChecks() {}

    public static final class OldPick {
        String hit;
        public void pickOld(Object profile, String origin, Object kind, boolean force, Long revision) {
            hit = "old:" + force + ":" + revision;
        }
        public void pickDecoy(Object profile) { hit = "decoy"; }
    }

    public static final class NewPick {
        String hit;
        public void pickNew(Object profile, String origin, Object kind, boolean force) { hit = "new:" + force; }
        public void pickDecoy(Object profile) { hit = "decoy"; }
    }

    public static final class Named {
        String hit;
        public void pick(String value) { hit = "exact:" + value; }
        public void pickOther(String value) { hit = "other:" + value; }
    }

    public static final class RequiredTail {
        String hit;
        public void pickOnly(String value) { hit = "only:" + value; }
    }

    public static void main(String[] args) throws Exception {
        OldPick oldPick = new OldPick();
        invoke(oldPick, "pick", "profile", "origin", "preset", false, null);
        check("old:false:null".equals(oldPick.hit), oldPick.hit);

        NewPick newPick = new NewPick();
        invoke(newPick, "pick", "profile", "origin", "preset", false, null);
        check("new:false".equals(newPick.hit), newPick.hit);

        Named named = new Named();
        invoke(named, "pick", "agent");
        check("exact:agent".equals(named.hit), named.hit);

        RequiredTail required = new RequiredTail();
        try {
            invoke(required, "pick", "agent", "kept");
            throw new AssertionError("non-null tail was dropped");
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getTargetException();
            check(cause instanceof IllegalStateException, String.valueOf(cause));
            String message = cause.getMessage();
            check(message.contains("Unsupported native call pick"), message);
            check(message.contains("pickOnly(java.lang.String)"), message);
            check(!message.contains("NoSuchElementException"), message);
        }
        check(required.hit == null, String.valueOf(required.hit));

        Class<?> adapter = Class.forName("dev.boyko.aiagentswitcher.NativeRecoveryContinuation");
        Object instance = adapter.getField("INSTANCE").get(null);
        Method admissionLookup = null;
        for (Method method : adapter.getDeclaredMethods()) {
            if (method.getName().startsWith("promptAdmission") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Class.class) admissionLookup = method;
        }
        if (admissionLookup == null) throw new NoSuchMethodException("promptAdmission");
        admissionLookup.setAccessible(true);
        Method admission = (Method) admissionLookup.invoke(instance, Class.forName("dev.boyko.aiagentswitcher.AirPromptAdmissionFixture"));
        check(admission.getParameterCount() == 8, String.valueOf(admission));
        check("SessionPromptRequest".equals(admission.getParameterTypes()[2].getSimpleName()), admission.toString());
        try {
            admissionLookup.invoke(instance, Class.forName("dev.boyko.aiagentswitcher.AirPromptAdmissionMismatch"));
            throw new AssertionError("short sendPrompt bridge was accepted");
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getTargetException();
            check(cause instanceof IllegalStateException, String.valueOf(cause));
            check(cause.getMessage().contains("Unsupported Air prompt admission"), cause.getMessage());
            check(cause.getMessage().contains("sendPrompt-short$default"), cause.getMessage());
        }
        checkAirPayloads();
        checkAcpContentLoaders();
        System.out.println("native call compatibility checks passed");
    }

    /** IDEA 2026.3 shape: content modules are not reachable through getModules(). */
    public static final class AssistantPlugin {
        public ClassLoader getPluginClassLoader() { return AssistantPlugin.class.getClassLoader(); }
        public java.util.List<AcpModule> getContentModules() { return java.util.List.of(new AcpModule()); }
    }

    public static final class AcpModule {
        static final ClassLoader LOADER = new java.net.URLClassLoader(new java.net.URL[0], null);
        public ClassLoader getPluginClassLoader() { return LOADER; }
    }

    public static final class FieldContentPlugin {
        public FieldContent getContent() { return new FieldContent(); }
    }

    public static final class FieldContent {
        public final java.util.List<FieldModuleItem> modules = java.util.List.of(new FieldModuleItem());
    }

    public static final class FieldModuleItem {
        public AcpModule getDescriptor() { return new AcpModule(); }
    }

    private static void checkAcpContentLoaders() throws Exception {
        Class<?> helper = Class.forName("dev.boyko.aiagentswitcher.compat.InstalledAgentsKt");
        Method loaders = helper.getDeclaredMethod("acpLoaders", Object.class);
        loaders.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<ClassLoader> fromModules = (java.util.List<ClassLoader>) loaders.invoke(null, new AssistantPlugin());
        check(fromModules.contains(AcpModule.LOADER), "content module loader missing: " + fromModules);
        @SuppressWarnings("unchecked")
        java.util.List<ClassLoader> fromField = (java.util.List<ClassLoader>) loaders.invoke(null, new FieldContentPlugin());
        check(fromField.contains(AcpModule.LOADER), "content field loader missing: " + fromField);
    }

    public static final class LegacyPayload {
        public String string(String key) { return "agentSessionId".equals(key) ? "saved-session" : null; }
        public LegacyPayload withString(String key, String value) { return this; }
    }

    private static void checkAirPayloads() throws Exception {
        Class<?> helper = Class.forName("dev.boyko.aiagentswitcher.air.AirChatRoutingRepair");
        Object instance = helper.getField("INSTANCE").get(null);
        Method read = helper.getDeclaredMethod("string", Object.class, String.class);
        Method write = helper.getDeclaredMethod("withString", Object.class, String.class, String.class);
        read.setAccessible(true); write.setAccessible(true);
        LegacyPayload legacy = new LegacyPayload();
        check("saved-session".equals(read.invoke(instance, legacy, "agentSessionId")), "Legacy payload read failed");
        check(write.invoke(instance, legacy, "agentId", "AI Multi") == legacy, "Legacy payload write failed");
        Class<?> primitives = Class.forName("kotlinx.serialization.json.JsonElementKt");
        Object sid = primitives.getMethod("JsonPrimitive", String.class).invoke(null, "saved-session");
        Class<?> json = Class.forName("kotlinx.serialization.json.JsonObject");
        Object payload = json.getConstructor(java.util.Map.class).newInstance(java.util.Map.of("agentSessionId", sid, "futureField", sid));
        Object corrected = write.invoke(instance, payload, "agentId", "AI Multi");
        check("saved-session".equals(read.invoke(instance, corrected, "agentSessionId")), "Session ID changed");
        check("AI Multi".equals(read.invoke(instance, corrected, "agentId")), "Route was not corrected");
        check(((java.util.Map<?, ?>) corrected).get("futureField") == sid, "Unknown payload field changed");
        check(!((java.util.Map<?, ?>) payload).containsKey("agentId"), "Original payload mutated");
    }

    private static void invoke(Object target, String name, Object... args) throws Exception {
        Class<?> type = Class.forName("dev.boyko.aiagentswitcher.NativeRecoveryContinuation");
        Object instance = type.getField("INSTANCE").get(null);
        Method call = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().startsWith("call") && parameters.length == 3 && parameters[0] == Object.class
                    && parameters[1] == String.class && parameters[2] == Object[].class) {
                call = method;
                break;
            }
        }
        if (call == null) throw new NoSuchMethodException("native call");
        call.setAccessible(true);
        call.invoke(instance, target, name, (Object) args);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
