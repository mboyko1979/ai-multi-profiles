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
        System.out.println("native call compatibility checks passed");
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
