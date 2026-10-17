import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

/** Class identity regression probe; runs only in an isolated IDEA with both ACP hosts. */
public class AcpClassIdentityProbe {
  public static void premain(String arg, Instrumentation inst) throws Exception {
    Path dir=Path.of(arg).toAbsolutePath();
    if (!System.getProperty("aiaps.home", "").replace('\\','/').endsWith("/build/restore64/home")) throw new IllegalStateException("Sandbox only");
    SettingsRefreshProbe.inst=inst; SettingsRefreshProbe.dir=dir;
    System.setProperty("idea.trust.all.projects","true");
    new Thread(()->{
      try {
        Object project=null;
        for(int i=0;i<100;i++) {
          Thread.sleep(1000);
          SettingsRefreshProbe.app=SettingsRefreshProbe.call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
          if(SettingsRefreshProbe.app==null)continue;
          Object[] projects=(Object[])SettingsRefreshProbe.call(SettingsRefreshProbe.call(SettingsRefreshProbe.type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");
          if(projects.length>0){project=projects[0];break;}
        }
        if(project==null)throw new AssertionError("No sandbox project");
        Object id=SettingsRefreshProbe.call(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
        Object descriptor=SettingsRefreshProbe.call(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",id);
        ClassLoader plugin=(ClassLoader)SettingsRefreshProbe.call(descriptor,"getPluginClassLoader");
        Class<?> manager=Class.forName("com.intellij.ml.llm.agents.acp.client.settings.AcpConfigOptionsManager",true,plugin);
        List<String> report=new ArrayList<>();
        for(String name:List.of("client.ClientSession","model.SessionConfigOption","model.SessionConfigOption$Select","model.SessionConfigOption$BooleanOption","model.SessionConfigOptionValue","model.ModelInfo","model.AuthMethod")) {
          String fqn="com.agentclientprotocol."+name;
          Class<?> actual=Class.forName(fqn,true,plugin),expected=Class.forName(fqn,true,manager.getClassLoader());
          report.add(name+" same="+(actual==expected)+" actual="+actual.getClassLoader()+" expected="+expected.getClassLoader());
        }
        Files.write(dir.resolve("identity.txt"),report);
        for(String line:report)if(line.contains("same=false"))throw new AssertionError("ACP class identity split: "+line);
        verifyOptions(plugin,manager,project);
        verifyRestoreDialog(project,plugin);
        Files.writeString(dir.resolve("result.txt"),"PASS: all AI Assistant ACP adapter classes use the native owner's SDK with both hosts installed; select/boolean catalog publication; native legacy restore confirmation accepts and declines\n");
        System.exit(0);
      }catch(Throwable e){try{java.io.StringWriter out=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(out));Files.writeString(dir.resolve("error.txt"),out.toString());}catch(Exception ignored){}System.exit(1);}
    },"acp-class-identity-probe").start();
  }
  static Object call(Object o,String name,Object...args)throws Exception{return SettingsRefreshProbe.call(o,name,args);}
  static void verifyOptions(ClassLoader loader,Class<?> manager,Object project)throws Exception {
    Class<?> option=Class.forName("com.agentclientprotocol.model.SessionConfigOption",true,loader);
    Object serializer=call(option.getField("Companion").get(null),"serializer");
    Class<?> json=Class.forName("kotlinx.serialization.json.Json",true,loader);
    Object format=json.getField("Default").get(null);
    Object select=call(format,"decodeFromString",serializer,"{\"id\":\"model\",\"name\":\"Model\",\"type\":\"select\",\"category\":\"model\",\"currentValue\":\"fixture\",\"options\":[{\"value\":\"fixture\",\"name\":\"Fixture\"}]}");
    Object bool=call(format,"decodeFromString",serializer,"{\"id\":\"flag\",\"name\":\"Flag\",\"type\":\"boolean\",\"currentValue\":false}");
    Object nativeManager=call(manager.getField("Companion").get(null),"getInstance",project);
    Class<?> id=Class.forName("com.intellij.ml.llm.agents.acp.api.AcpAgentId",true,loader);
    Object cacheId=call(id.getField("Companion").get(null),"parse","acp.ai-multi-probe");
    call(nativeManager,"announceConfigOptions",cacheId,List.of(select,bool),Map.of());
    Object states=call(call(nativeManager,"getConfigState"),"getValue");
    if(!((Map<?,?>)states).containsKey(cacheId))throw new AssertionError("Catalog was not published");
  }
  static void verifyRestoreDialog(Object project,ClassLoader loader)throws Exception {
    Class<?> broker=Class.forName("dev.boyko.aiagentswitcher.AgentRecoveryDialogs",true,loader);
    call(call(SettingsRefreshProbe.app,"getService",broker),"start");
    Path rendezvous=Path.of(System.getProperty("aiaps.home"),".jetbrains","ai-agent-project-switcher","agent-recovery");Files.createDirectories(rendezvous);
    for(boolean approve:new boolean[]{false,true}){
      String nonce=UUID.randomUUID().toString();long pid=ProcessHandle.current().pid();
      String cwd=String.valueOf(call(project,"getBasePath"));
      String request="{\"kind\":\"restore-existing\",\"nonce\":"+quote(nonce)+",\"pid\":"+pid+",\"createdAt\":"+System.currentTimeMillis()+",\"cwd\":"+quote(cwd)+",\"sessionId\":\"aiaps-chat-probe\",\"from\":{\"profileName\":\"Legacy fixture\",\"agentName\":\"Original agent\",\"agentId\":\"registry.fixture\"},\"to\":{\"profileName\":\"Legacy fixture\",\"agentName\":\"Original agent\",\"agentId\":\"registry.fixture\"}}";
      Files.writeString(rendezvous.resolve(nonce+".request.json"),request);
      SettingsRefreshProbe.await(()->{
        javax.swing.JDialog window=null;
        for(java.awt.Window candidate:java.awt.Window.getWindows())if(candidate instanceof javax.swing.JDialog d&&d.isShowing()&&d.getTitle().equals("Restore Existing Chat"))window=d;
        if(window==null)throw new AssertionError("Waiting for restore dialog");
        javax.swing.JButton button=button(window,approve?"Restore":"Cancel");if(button==null)throw new AssertionError("Missing confirmation button");button.doClick();
      });
      Path response=rendezvous.resolve(nonce+".response.json");SettingsRefreshProbe.await(()->{if(!Files.exists(response))throw new AssertionError("Waiting for response");});
      String answer=Files.readString(response);if(!answer.contains("\"approved\":"+approve)||!answer.contains("\"restoreExisting\":true"))throw new AssertionError("Wrong consent");
      if(approve){Files.writeString(rendezvous.resolve(nonce+".ready.json"),"{\"nonce\":"+quote(nonce)+",\"pid\":"+pid+",\"token\":null}");SettingsRefreshProbe.await(()->{if(Files.exists(rendezvous.resolve(nonce+".ready.json")))throw new AssertionError("Waiting for cleanup");});}
      Files.deleteIfExists(response);Files.deleteIfExists(rendezvous.resolve(nonce+".request.json"));
    }
  }
  static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  static javax.swing.JButton button(java.awt.Container c,String text){for(java.awt.Component child:c.getComponents()){if(child instanceof javax.swing.JButton b&&text.equals(b.getText()))return b;if(child instanceof java.awt.Container nested){javax.swing.JButton found=button(nested,text);if(found!=null)return found;}}return null;}
}
