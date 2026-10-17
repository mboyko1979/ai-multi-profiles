import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Isolated runIde integration probe. Never attaches to or reads primary account data. */
public class SettingsRefreshProbe {
  static Instrumentation inst;
  static Path dir;
  static final Map<String,Class<?>> types = new java.util.concurrent.ConcurrentHashMap<>();
  static Class<?> type(String name) throws Exception {
    Class<?> cached=types.get(name);if(cached!=null)return cached;
    Class<?> resolved=resolveType(name);types.put(name,resolved);return resolved;
  }
  static Class<?> resolveType(String name) throws Exception {
    for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(name)) return c;
    for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().startsWith("dev.boyko.") || c.getName().startsWith("com.intellij.ml.llm.")) {
      try { return Class.forName(name, true, c.getClassLoader()); } catch (ClassNotFoundException ignored) {}
    }
    return Class.forName(name);
  }
  static Object call(Object target, String name, Object... args) throws Exception {
    Class<?> cls = target instanceof Class<?> c ? c : target.getClass();
    for (Method m : cls.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) {
      boolean match = true;
      for (int i=0;i<args.length;i++) if (args[i]!=null && !m.getParameterTypes()[i].isInstance(args[i]) && !m.getParameterTypes()[i].isPrimitive()) match=false;
      if (match) { m.setAccessible(true); return m.invoke(target instanceof Class<?> ? null : target, args); }
    }
    for (Method m : cls.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount()==args.length) {
      m.setAccessible(true); return m.invoke(target, args);
    }
    throw new NoSuchMethodException(cls+"."+name);
  }
  static Object field(Object o,String name) throws Exception { Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
  static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
  static void edt(Checked action) throws Exception { final Throwable[] error={null}; SwingUtilities.invokeAndWait(()->{try{action.run();}catch(Throwable t){error[0]=t;}}); if(error[0]!=null)throw new RuntimeException(error[0]); }
  interface Checked { void run() throws Exception; }
  static void await(Checked condition) throws Exception { Throwable last=null; for(int i=0;i<160;i++){try{edt(condition);return;}catch(Throwable t){last=t;}Thread.sleep(50);}throw new RuntimeException("Timed out",last); }
  static String text(Object o,String field) throws Exception { return String.valueOf(call(field(o,field),"getText")); }
  static boolean enabled(Object o,String field) throws Exception { return (boolean)call(field(o,field),"isEnabled"); }
  static String nativeName(String n){return "com.intellij.ml.llm.agents.acp.runtime.registry."+n;}
  static Object app;
  static Object service(String n)throws Exception{return call(app,"getService",type(n));}
  public static void premain(String arg,Instrumentation instrumentation) throws Exception {
    inst=instrumentation;dir=Path.of(arg).toAbsolutePath();
    if(!System.getProperty("aiaps.home","").replace('\\','/').endsWith("/build/settings53/home"))throw new IllegalStateException("Sandbox only");
    System.setProperty("idea.trust.all.projects","true");
    Thread thread=new Thread(()->{
      Throwable last=null;
      for(int i=0;i<120;i++)try{
        Thread.sleep(1000);app=call(type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
        Object[] projects=(Object[])call(call(type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");
        if(projects.length==0){Files.writeString(dir.resolve("waiting.txt"),"Waiting for sandbox project");continue;}
        run(projects[0]);System.exit(0);return;
      }catch(Throwable e){last=e;if(e instanceof InvocationTargetException it)last=it.getTargetException();try{Files.writeString(dir.resolve("waiting.txt"),last.toString());}catch(Exception ignored){}if(Files.exists(dir.resolve("started")))break;}
      try{java.io.StringWriter out=new java.io.StringWriter();if(last!=null)last.printStackTrace(new java.io.PrintWriter(out));else out.append("Sandbox project did not open");Files.writeString(dir.resolve("error.txt"),out.toString());}catch(Exception ignored){}System.exit(1);
    },"settings-refresh-sandbox-probe");thread.setDaemon(true);thread.start();
  }
  static void run(Object project)throws Exception {
    // Force plugin loading before accessing its compatibility classes.
    Object pluginId=call(type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
    Object descriptor=call(type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",pluginId);
    ClassLoader loader=(ClassLoader)call(descriptor,"getPluginClassLoader");
    Class<?> configurableClass=Class.forName("dev.boyko.aiagentswitcher.SwitcherConfigurable",true,loader);
    Files.writeString(dir.resolve("started"),"isolated");
    Object paths=service(nativeName("AcpPaths"));
    // Redirect the native installation store as well as plugin configuration into this sandbox.
    Object companion=type(nativeName("AcpPaths")).getField("Companion").get(null);
    Path nativeData=Files.createTempDirectory(dir,"native-data-");
    call(companion,"setTestDataDirOverride$intellij_ml_llm_agents_acp",nativeData);
    Class<?> stateType=type(nativeName("AcpAgentInstallationState"));
    Object cached=service(nativeName("AcpAgentInstallationState"));
    Object writer=stateType.getConstructor().newInstance();
    Class<?> distribution=type(nativeName("Distribution")), external=type(nativeName("ExternalAgent"));
    Object dist=Arrays.stream(distribution.getConstructors()).filter(c->c.getParameterCount()==2).findFirst().orElseThrow().newInstance(null,null);
    Object agent=Arrays.stream(external.getConstructors()).filter(c->c.getParameterCount()==12).findFirst().orElseThrow()
      .newInstance("refresh-fixture","Refresh fixture","1.0.0","fixture",null,null,List.of(),null,null,dist,List.of("acp"),false);
    Object agentId=call(agent,"getId");
    check(!(boolean)call(cached,"isInstalled",agentId),"Fixture already cached");
    call(writer,"markInstalled",agent,true);
    Object installed=Class.forName("dev.boyko.aiagentswitcher.compat.InstalledAgents",true,loader).getField("INSTANCE").get(null);
    check(call(installed,"all").toString().contains("Refresh fixture"),"Refresh missed external install");
    check(!(boolean)call(cached,"isInstalled",agentId),"Refresh mutated host cached state");
    Path localFile=Path.of(System.getProperty("aiaps.home"),".jetbrains","acp.json");
    String original=Files.readString(localFile);
    Object json=call(type("com.google.gson.JsonParser"),"parseString",original);
    Object servers=call(call(json,"getAsJsonObject"),"getAsJsonObject","agent_servers");
    Object localSpec=call(type("com.google.gson.JsonParser"),"parseString","{\"command\":\"fixture-do-not-run\",\"args\":[\"--acp\"]}");
    call(servers,"add","Local refresh fixture",localSpec);
    Files.writeString(localFile,json.toString());
    List<?> withLocal=(List<?>)call(installed,"all");
    Object localChoice=withLocal.stream().filter(x->{try{return call(x,"getName").equals("Local refresh fixture");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow(()->new AssertionError("Local ACP agent was excluded"));
    Object localProfile=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
    call(localProfile,"setAgentId",call(localChoice,"getId"));call(localProfile,"setAgentName","Local refresh fixture");
    check(call(call(installed,"resolve",project,localProfile),"getCommand").equals("fixture-do-not-run"),"Fresh local launch config was not resolved");
    Files.writeString(localFile,original);
    check(!call(installed,"all").toString().contains("Local refresh fixture"),"Removed local config remained selectable");
    Object profiles=service("dev.boyko.aiagentswitcher.AgentProfiles");
    Object entry=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
    call(entry,"setId","fixture-profile");call(entry,"setName","Fixture profile");call(entry,"setAgentId",call(agentId,"getFullId"));call(entry,"setAgentName","Refresh fixture");
    call(entry,"setCommand","fixture-do-not-run");
    call(profiles,"replace",List.of(entry));
    Object configurable=configurableClass.getConstructor(type("com.intellij.openapi.project.Project")).newInstance(project);
    edt(()->call(configurable,"createComponent"));Object editor=field(configurable,"ui");
    await(()->check(!(boolean)field(editor,"refreshingAgents") && !(boolean)field(editor,"registrationBusy"),"Initial reads still busy"));
    edt(()->{
      call(editor,"refreshAgents");check(!enabled(editor,"refreshAgentsLink"),"Refresh clickable while busy");
      check(text(editor,"refreshAgentsLink").equals("Refreshing agents…"),"Missing progress text");
      check((boolean)call(field(editor,"refreshAgentsLink"),"isVisible"),"Disabled refresh link hides progress text");
      check(!(boolean)call(field(editor,"agentStatus"),"isVisible"),"Refresh added a status row");
      call(editor,"refreshAgents");
    });
    Thread.sleep(150);
    edt(()->check((boolean)field(editor,"refreshingAgents"),"Loading vanished before readable interval"));
    await(()->check(!(boolean)field(editor,"refreshingAgents"),"Refresh did not finish"));
    edt(()->check(text(editor,"agentStatus").isEmpty() && !(boolean)call(field(editor,"agentStatus"),"isVisible"),"Successful refresh added a status row"));
    call(writer,"uninstall",agentId);
    check(!call(installed,"all").toString().contains("Refresh fixture"),"Refresh retained removed agent");
    edt(()->call(editor,"showProfile",entry));
    edt(()->{
      Object presets=field(editor,"presets");
      check(((JComponent)field(presets,"refreshRow")).isVisible(),"First defaults refresh unavailable without a catalog");
      check(((JComponent)field(presets,"refresh")).isVisible(),"First defaults refresh link hidden");
      call(presets,"refreshOptions");
      check(!enabled(presets,"refresh"),"Defaults refresh clickable while busy");
      check(text(presets,"refresh").equals("Refreshing agents defaults..."),"Missing full defaults progress caption");
      check(((JComponent)field(presets,"refresh")).isVisible(),"Disabled defaults refresh hides progress");
      check(((JComponent)field(presets,"refreshRow")).isVisible(),"Defaults progress row hidden");
    });
    await(()->check(enabled(field(editor,"presets"),"refresh"),"Defaults refresh did not unlock"));
    await(()->check(text(editor,"agentStatus").contains("no longer installed"),"No missing-agent warning"));
    edt(()->check(!enabled(editor,"authorize"),"Removed agent permits authorization"));
    edt(()->{
      call(editor,"repairRegistration");check(!enabled(editor,"repairRegistrationLink") && !enabled(editor,"refreshRegistrationLink"),"Registration actions clickable during repair");
      check(text(editor,"status").equals("Repairing registration…"),"Missing repair state");
      call(editor,"refreshStatus");check(text(editor,"status").equals("Repairing registration…"),"Concurrent refresh overwrote repair state");
      call(editor,"repairRegistration");
    });
    await(()->check(!(boolean)field(editor,"registrationBusy"),"Repair completion did not unlock actions"));
    edt(()->check(enabled(editor,"repairRegistrationLink") && enabled(editor,"refreshRegistrationLink"),"Actions remain locked"));
    edt(()->{
      call(editor,"updateAccount");
      check(!enabled(editor,"refreshAccountLink"),"Account refresh clickable while busy");
      check(text(editor,"refreshAccountLink").equals("Refreshing account info…"),"Missing account refresh progress");
    });
    await(()->check(enabled(editor,"refreshAccountLink") && text(editor,"refreshAccountLink").equals("Refresh account info"),"Account refresh did not unlock"));
    edt(()->{
      call(editor,"refreshStatus");
      check(!enabled(editor,"refreshRegistrationLink"),"Registration refresh clickable while busy");
      check(text(editor,"refreshRegistrationLink").equals("Refreshing…"),"Missing registration refresh progress");
    });
    await(()->check(enabled(editor,"refreshRegistrationLink"),"Registration refresh did not unlock"));
    edt(()->call(configurable,"disposeUIResources"));
    Files.writeString(dir.resolve("result.txt"),"PASS: native external installation/removal with stale host state, local ACP add/resolve/remove without waiting for VFS, current disk metadata fallback, refresh progress in action link without progress/success status row, visible disabled full defaults-refresh caption and completion, missing-agent warning and disabled authorization, actual repair completion, mutual exclusion and disposal\n");
  }
}
