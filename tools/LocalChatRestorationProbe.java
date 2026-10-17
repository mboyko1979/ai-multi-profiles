import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Isolated IDEA probe. Only synthetic profiles, local history and input; never opens a provider. */
public class LocalChatRestorationProbe {
  static Path dir; static ClassLoader loader; static Object app;
  static Object call(Object o,String n,Object...a)throws Exception{return SettingsRefreshProbe.call(o,n,a);}
  static Class<?> type(String n)throws Exception{return Class.forName(n,true,loader);}
  static Object singleton(String n)throws Exception{return type("dev.boyko.aiagentswitcher."+n).getField("INSTANCE").get(null);}
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
  public static void premain(String arg,Instrumentation inst)throws Exception {
    dir=Path.of(arg);SettingsRefreshProbe.inst=inst;SettingsRefreshProbe.dir=dir;
    if(!System.getProperty("aiaps.home","").replace('\\','/').endsWith("/build/restore65/home"))throw new IllegalStateException("Sandbox only");
    System.setProperty("idea.trust.all.projects","true");
    new Thread(()->{
      try{
        Object project=null;
        for(int i=0;i<100;i++){
          Thread.sleep(1000);app=call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
          if(app==null)continue;SettingsRefreshProbe.app=app;
          Object[] projects=(Object[])call(call(SettingsRefreshProbe.type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");
          if(projects.length>0){project=projects[0];break;}
        }
        check(project!=null,"No sandbox project");
        Object id=call(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
        loader=(ClassLoader)call(call(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",id),"getPluginClassLoader");
        verifyDialog(project);
        verifyLocalInput(project);
        Files.writeString(dir.resolve("result.txt"),"PASS: actual Assistant/Air native history read without AI Multi's old transcript; deleted-profile dropdown and actions; empty-profile local view; disabled native composer/document; unchanged binding and history; target staging/input restoration; saved model without new defaults\n");
        System.exit(0);
      }catch(Throwable e){try{java.io.StringWriter out=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(out));Files.writeString(dir.resolve("error.txt"),out.toString());}catch(Exception ignored){}System.exit(1);}
    },"local-chat-restoration-probe").start();
  }
  static Object entry(String id,String name)throws Exception {
    Object e=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
    call(e,"setId",id);call(e,"setName",name);call(e,"setCommand","unused-synthetic-agent");call(e,"setAgentId","registry.fixture");call(e,"setAgentName","Fixture Agent");return e;
  }
  static void verifyDialog(Object project)throws Exception {
    Object first=entry("one","First fixture"),second=entry("two","Second fixture");
    for(String action:List.of("Cancel","Choose Profile","View Local History","empty")) {
      AtomicReference<Object> created=new AtomicReference<>();
      SettingsRefreshProbe.edt(()->created.set(type("dev.boyko.aiagentswitcher.DeletedProfileDialog").getDeclaredConstructors()[0].newInstance(project,"Deleted fixture",action.equals("empty")?List.of():List.of(first,second))));
      Object dialog=created.get();
      AtomicReference<Throwable> error=new AtomicReference<>();
      SwingUtilities.invokeLater(()->{try{call(dialog,"show");}catch(Throwable t){error.set(t);}});
      SettingsRefreshProbe.await(()->{
        JDialog window=window("Profile Deleted — AI Multi");
        check(window!=null,"Waiting for deleted-profile popup");
        JComboBox<?> box=findCombo(window);check(box!=null,"Missing profile dropdown");
        if(action.equals("empty")){check(box.getItemCount()==0,"Expected no profiles");check(!button(window,"Choose Profile").isEnabled(),"Empty profile selection was enabled");}
        else {check(box.getItemCount()==2,"Wrong profiles");box.setSelectedIndex(1);}
        JButton button=button(window,action.equals("empty")?"View Local History":action);check(button!=null,"Missing action "+action);button.doClick();
      });
      check(error.get()==null,"Dialog failed: "+error.get());
      check((boolean)call(dialog,"isOK")!=action.equals("Cancel"),"Wrong dialog result");
      check((boolean)call(dialog,"getReadOnly")==List.of("View Local History","empty").contains(action),"Wrong read-only choice");
      if(action.equals("Choose Profile"))check(call(dialog,"getProfile").equals("two"),"Wrong selected target");
    }
  }
  static void verifyLocalInput(Object project)throws Exception {
    String uid=UUID.randomUUID().toString(),sid="aiaps-chat-"+UUID.randomUUID();
    Path root=Path.of(System.getProperty("aiaps.home"),".jetbrains","ai-agent-project-switcher"),conversations=root.resolve("conversations"),views=root.resolve("local-chats");
    Files.createDirectories(conversations);Files.createDirectories(views);
    String record="{\"sessionId\":"+quote(sid)+",\"cwd\":"+quote(call(project,"getBasePath").toString())+",\"profile\":\"deleted\",\"revision\":7,\"currentSegment\":\"original\",\"segments\":[{\"id\":\"original\",\"profile\":\"deleted\",\"sessionId\":\"original-provider-session\",\"author\":{\"name\":\"Deleted fixture\",\"agentId\":\"registry.original\"}}],\"exchanges\":[{\"user\":[{\"type\":\"text\",\"text\":\"Preserved local history\"}]}],\"sessionOptions\":{\"configOptions\":[{\"id\":\"model\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\",\"currentValue\":\"retired-model\",\"options\":[{\"value\":\"retired-model\",\"name\":\"Retired Model\"}]}]}}";
    Path recordFile=conversations.resolve(sha(quote(sid))+".json");Files.writeString(recordFile,record);
    Class<?> storage=type("com.intellij.ml.llm.chat.session.SessionHistoryStorage");Object store=call(storage.getField("Companion").get(null),"getInstance");
    Object pid=type("com.intellij.ml.llm.chat.session.SessionHistoryStorage$PersistanceId").getConstructor(String.class).newInstance(uid);
    call(store,"storeAgentSessionId",pid,"acp.ai-multi",sid);
    Object agent=type("com.intellij.ml.llm.chat.shared.ChatSessionAgentId").getConstructor(String.class).newInstance("acp.ai-multi");
    Class<?> eventId=type("com.intellij.ml.llm.chat.shared.ChatSessionEventId");
    Object user=type("com.intellij.ml.llm.chat.shared.ChatSessionUserPromptEvent").getConstructor(eventId,String.class,List.class,type("com.intellij.ml.llm.chat.shared.ChatSessionAgentId")).newInstance(eventId.getConstructor(int.class).newInstance(1),"Native cached question",List.of(),agent);
    Object markdown=type("com.intellij.ml.llm.aui.events.api.MarkdownBlockUpdatedEvent").getConstructor(String.class,List.class,String.class,type("com.intellij.ml.llm.aui.events.api.CloudRunOnboardingMessageType")).newInstance("native-answer",List.of(),"Native cached answer",null);
    Object answer=type("com.intellij.ml.llm.chat.shared.ChatSessionMessageBlockEvent").getConstructor(eventId,type("com.intellij.ml.llm.chat.shared.ChatSessionAgentId"),type("com.intellij.ml.llm.aui.events.api.MessageBlockUpdatedEvent")).newInstance(eventId.getConstructor(int.class).newInstance(2),agent,markdown);
    call(store,"recordEvent",pid,user);call(store,"recordEvent",pid,answer);
    call(store,"flush",pid);
    Object nativeSnapshot=call(singleton("NativeHistory"),"assistant",uid);
    Files.writeString(dir.resolve("native-history-source.txt"),call(store,"getEvents",pid)+"\nSNAPSHOT="+nativeSnapshot);
    check(nativeSnapshot.toString().contains("Native cached question")&&nativeSnapshot.toString().contains("Native cached answer"),"Native history was unavailable");
    check(!nativeSnapshot.toString().contains("Preserved local history"),"Read from AI Multi's legacy duplicate");
    verifyAirSnapshot(project);
    check(call(call(store,"getStoredAgentSession",pid),"getAgentSessionId").equals(sid),"Wrong native binding");
    Object recordJson=call(singleton("ChatProfiles"),"conversationRecord",uid);check(recordJson!=null,"Native chat did not resolve local record");
    AtomicReference<Object> field=new AtomicReference<>();
    SettingsRefreshProbe.edt(()->{
      Object events=call(type("com.intellij.util.EventDispatcher"),"create",type("com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantInputListener"));
      Object text=type("com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantInputEditorTextField").getConstructors()[0].newInstance(project,events);
      call(text,"setText","Unsent local draft");field.set(text);
    });
    Object document=call(field.get(),"getDocument");
    Class<?> inputType=type("com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantInput");
    Object input=Proxy.newProxyInstance(loader,new Class<?>[]{inputType},(p,m,a)->switch(m.getName()){
      case "getDocument"->document;case "getEditorTextField"->field.get();case "getProject"->project;default->null;
    });
    Object associated=call(type("kotlinx.coroutines.flow.StateFlowKt"),"MutableStateFlow",input);
    Class<?> chatType=type("com.intellij.ml.llm.core.chat.session.ChatSession");
    Object vm=Proxy.newProxyInstance(loader,new Class<?>[]{chatType},(p,m,a)->switch(m.getName()){
      case "getUid"->uid;case "getProject"->project;case "getAssociatedInput"->associated;case "getSession"->p;default->null;
    });
    Object context=type("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
    Object scope=call(type("kotlinx.coroutines.CoroutineScopeKt"),"CoroutineScope",context);
    Object job=call(singleton("compat.LocalHistoryPresentation"),"observe",vm,scope);
    Path view=views.resolve(sha(quote(sid))+".view.json");
    Files.writeString(view,"{\"sessionId\":"+quote(sid)+",\"cwd\":"+quote(call(project,"getBasePath").toString())+",\"revision\":7,\"currentSegment\":\"original\",\"readOnly\":true}");
    SettingsRefreshProbe.await(()->{
      check(!(boolean)call(document,"isWritable"),"Input document is writable");check(!(boolean)call(field.get(),"isEnabled"),"Composer is enabled");
    });
    check(call(document,"getText").equals("Unsent local draft"),"Draft was cleared");check(Files.readString(recordFile).equals(record),"Read-only view mutated local history");
    call(singleton("LocalChatState"),"select",recordJson,"two");check(Files.exists(views.resolve(sha(quote(sid))+".target.json")),"Missing staged target");
    call(singleton("compat.ProfileCatalogPresentation"),"seed",vm);
    Object manager=call(type("com.intellij.ml.llm.agents.acp.client.settings.AcpConfigOptionsManager").getField("Companion").get(null),"getInstance",project);
    Object cacheId=call(type("com.intellij.ml.llm.agents.acp.api.AcpAgentId").getField("Companion").get(null),"parse","acp.ai-multi-chat-"+uid);
    Object state=((Map<?,?>)call(call(manager,"getConfigState"),"getValue")).get(cacheId);
    List<?> options=(List<?>)call(state,"getOptions");check(options.size()==1,"Saved options were lost");
    check(call(options.get(0),"toString").toString().contains("retired-model"),"Saved model replaced by defaults");
    Files.delete(view);
    SettingsRefreshProbe.await(()->{check((boolean)call(document,"isWritable"),"Input stayed read-only");check((boolean)call(field.get(),"isEnabled"),"Composer stayed disabled");});
    check(Files.readString(recordFile).equals(record),"Target staging mutated history");
    call(job,"cancel",(Object)null);
  }
  public static class AirHandle {public final Object projection;AirHandle(Object p){projection=p;}public Object getProjection(){return projection;}}
  public static class AirRuntime {public final AirHandle projectionHandle;AirRuntime(Object p){projectionHandle=new AirHandle(p);}}
  public static class AirProjection {public final List<?> entries;AirProjection(List<?> e){entries=e;}public List<?> getTranscript(){return entries;}}
  static void verifyAirSnapshot(Object project)throws Exception {
    Object bridge=singleton("NativeRecoveryContinuation");
    Class<?> message=(Class<?>)Arrays.stream(bridge.getClass().getMethods()).filter(m->m.getName().startsWith("type")&&m.getParameterCount()==2).findFirst().orElseThrow().invoke(bridge,"com.intellij.air.backend.session.log.models.SessionMessage","com.intellij.air");
    if(message==null)throw new AssertionError("Missing Air message API");
    Constructor<?> constructor=Arrays.stream(message.getConstructors()).filter(c->c.getParameterCount()==16&&c.getParameterTypes()[2]==String.class).findFirst().orElseThrow();
    Class<?>[] types=constructor.getParameterTypes();List<Object> entries=new ArrayList<>();
    for(String role:List.of("user","agent")){
      Object[] args=new Object[16];
      for(int i=0;i<args.length;i++){if(types[i]==boolean.class)args[i]=false;if(types[i]==long.class)args[i]=0L;if(types[i]==int.class)args[i]=0;}
      args[0]="air-"+role;args[1]=Arrays.stream(types[1].getEnumConstants()).filter(x->x.toString().equalsIgnoreCase(role)).findFirst().orElseThrow();args[2]="Native Air "+role;
      args[14]=((1<<14)-1)&~7;
      entries.add(constructor.newInstance(args));
    }
    Object snapshot=call(singleton("NativeHistory"),"air",new AirRuntime(new AirProjection(entries)));
    check(snapshot.toString().contains("Native Air user")&&snapshot.toString().contains("Native Air agent"),"Air history source failed");
  }
  static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  static String sha(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  static JDialog window(String title){for(java.awt.Window w:java.awt.Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().equals(title))return d;return null;}
  static JButton button(java.awt.Container c,String text){for(java.awt.Component x:c.getComponents()){if(x instanceof JButton b&&text.equals(b.getText()))return b;if(x instanceof java.awt.Container box){JButton found=button(box,text);if(found!=null)return found;}}return null;}
  static JComboBox<?> findCombo(java.awt.Container c){for(java.awt.Component x:c.getComponents()){if(x instanceof JComboBox<?> box)return box;if(x instanceof java.awt.Container nested){JComboBox<?> box=findCombo(nested);if(box!=null)return box;}}return null;}
}
