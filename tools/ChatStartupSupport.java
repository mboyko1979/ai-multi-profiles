import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Reflection support for ChatStartupProbe's isolated native chat fixture. */
class ChatStartupSupport {
  static java.lang.instrument.Instrumentation inst;
  static Path dir;
  static Object chat;
  static Class<?> type(String name) throws Exception {
    SettingsRefreshProbe.inst = inst;
    return SettingsRefreshProbe.type(name);
  }
  static Object call(Object target, String name, Object... args) throws Exception {
    return SettingsRefreshProbe.call(target, name, args);
  }
  static Object project() throws Exception {
    return ((Object[])call(call(type("com.intellij.openapi.project.ProjectManager"), "getInstance"), "getOpenProjects"))[0];
  }
  static Object callback(Consumer<Object> done) throws Exception {
    Class<?> continuation = type("kotlin.coroutines.Continuation");
    Object context = type("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
    return Proxy.newProxyInstance(continuation.getClassLoader(), new Class<?>[]{continuation}, (p,m,a) -> {
      if (m.getName().equals("getContext")) return context;
      if (m.getName().equals("resumeWith")) done.accept(a[0]);
      return null;
    });
  }
  static void susp(Object target, String name, Consumer<Object> done, Object... args) throws Exception {
    Object[] supplied = Arrays.copyOf(args, args.length + 1);
    supplied[args.length] = callback(done);
    Object result = call(target, name, supplied);
    if (result != null && !result.toString().equals("COROUTINE_SUSPENDED")) done.accept(result);
  }
  static void result(String name, Object value) {
    try { Files.writeString(dir.resolve(name + ".txt"), String.valueOf(value)); }
    catch (Exception e) { throw new RuntimeException(e); }
  }
  static void run(String command) throws Exception {
    Object project = project();
    if (command.equals("seed")) {
      Object app = call(type("com.intellij.openapi.application.ApplicationManager"), "getApplication");
      Object profiles = call(app, "getService", type("dev.boyko.aiagentswitcher.AgentProfiles"));
      List<Object> entries = new ArrayList<>();
      for (String id : List.of("codex", "grok")) {
        Object entry = type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
        call(entry, "setId", id); call(entry, "setName", id + " fixture");
        call(entry, "setCommand", "C:/Program Files/nodejs/node.exe");
        call(entry, "setArguments", dir.resolve("backend.js").toString());
        call(entry, "setInheritEnvironment", false);
        call(entry, "setEnvironment", "PROFILE=" + id + "\nTRACE=" + dir.resolve("backend-trace.jsonl"));
        entries.add(entry);
      }
      call(profiles, "replace", entries);
      call(type("dev.boyko.aiagentswitcher.GenericAutoRegistration").getField("INSTANCE").get(null), "assignNow", project, "codex");
    } else if (command.equals("open")) {
      Object windowManager = call(type("com.intellij.openapi.wm.ToolWindowManager"), "getInstance", project);
      call(call(windowManager, "getToolWindow", "AIAssistant"), "show", (Object)null);
      Object selected = null;
      for (Object mode : (List<?>)call(type("com.intellij.ml.llm.core.chat.ui.chat.input.chatModeSelector.AIAssistantNewChatModeKt"), "getAvailableChatModes", project))
        if (call(mode, "getDisplayName").equals("AI Multi")) selected = mode;
      if (selected == null) throw new AssertionError("AI Multi fixture mode is unavailable");
      Object source = Arrays.stream(type("com.intellij.ml.llm.core.chat.session.ChatSourceAction").getEnumConstants()).filter(x -> x.toString().equals("NEW_CHAT")).findFirst().orElseThrow();
      Object result = call(type("com.intellij.ml.llm.core.chat.ui.NewChatCreatorKt"), "createAndFocusNewChat", project, selected, source, callback(v -> chat = v));
      if (result != null && !result.toString().equals("COROUTINE_SUSPENDED")) chat = result;
    } else throw new IllegalArgumentException(command);
  }
}
