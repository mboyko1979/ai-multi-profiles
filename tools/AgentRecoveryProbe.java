import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import javax.swing.*;

/** Sandbox-only native recovery dialog/broker and host-adapter compatibility checks. */
public class AgentRecoveryProbe extends SettingsRefreshProbe {
  static Object dialog;
  static Object call(Object target,String name,Object...args)throws Exception {
    try{return SettingsRefreshProbe.call(target,name,args);}catch(NoSuchMethodException missing){
      Class<?> cls=target instanceof Class<?> c?c:target.getClass();
      for(Class<?> parent=cls.getSuperclass();parent!=null;parent=parent.getSuperclass())for(Method method:parent.getDeclaredMethods())
        if(method.getName().equals(name)&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(target instanceof Class<?>?null:target,args);}
      throw missing;
    }
  }
  static String quote(String text){return "\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  static Object recoveryDialog()throws Exception {
    for(Window w:Window.getWindows())if(w instanceof JDialog d && d.isShowing() && d.getTitle().equals("Agent Changed in Profile"))
      return call(type("com.intellij.openapi.ui.DialogWrapper"),"findInstance",d);
    throw new AssertionError("Recovery dialog not visible");
  }
  static String labels(Component c){String text=c instanceof JLabel l?l.getText()+"\n":"";if(c instanceof Container box)for(Component child:box.getComponents())text+=labels(child);return text;}
  static void action(Object dialog,String getter)throws Exception {
    ((Action)call(dialog,getter)).actionPerformed(new ActionEvent(dialog,ActionEvent.ACTION_PERFORMED,"probe"));
  }
  public static void premain(String arg,Instrumentation instrumentation) {
    inst=instrumentation;dir=Path.of(arg).toAbsolutePath();
    if(!System.getProperty("aiaps.home","").replace('\\','/').endsWith("/build/recovery57/home"))throw new IllegalStateException("Sandbox only");
    System.setProperty("idea.trust.all.projects","true");
    Thread worker=new Thread(()->{
      try{
        Object project=null;
        for(int i=0;i<120;i++){
          try{app=call(type("com.intellij.openapi.application.ApplicationManager"),"getApplication");Object[] projects=(Object[])call(call(type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");if(projects.length>0){project=projects[0];break;}}catch(Exception ignored){}
          Thread.sleep(1000);
        }
        if(project==null)throw new AssertionError("No sandbox project");
        verify(project);
        Files.writeString(dir.resolve("result.txt"),"PASS: actual native recovery dialog, old/new agent and same profile labels, working directory, default count, required sharing consent, adjustable history, approval/cancellation rendezvous; Air prompt defaults/display text and native task admission adapter signatures; no provider prompts or primary IDE attachment.\n");
      }catch(Throwable error){try{java.io.StringWriter out=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(out));Files.writeString(dir.resolve("error.txt"),out.toString());}catch(Exception ignored){}}
      finally{SwingUtilities.invokeLater(()->{try{call(app,"exit",true,true,false);}catch(Exception ignored){}});}
    },"agent-recovery-sandbox-probe");worker.setDaemon(true);worker.start();
  }
  static void verify(Object project)throws Exception {
    Object pluginId=call(type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
    Object plugin=call(type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",pluginId);
    ClassLoader loader=(ClassLoader)call(plugin,"getPluginClassLoader");
    Class<?> broker=Class.forName("dev.boyko.aiagentswitcher.AgentRecoveryDialogs",true,loader);
    call(service(broker.getName()),"start");
    Path rendezvous=Path.of(System.getProperty("aiaps.home"),".jetbrains","ai-agent-project-switcher","agent-recovery");Files.createDirectories(rendezvous);
    String cwd=String.valueOf(call(project,"getBasePath"));
    StringBuilder estimates=new StringBuilder("{");
    for(int count=0;count<=20;count++){if(count>0)estimates.append(',');estimates.append(quote(String.valueOf(count))).append(":{\"availableMessages\":7,\"messages\":").append(Math.min(count,7)).append(",\"estimatedTokens\":").append(Math.min(count,7)*100).append(",\"budget\":32768,\"shortenedReplies\":0,\"attachments\":[]}");}estimates.append('}');
    for(boolean accept:new boolean[]{true,false}){
      String nonce=UUID.randomUUID().toString();long pid=ProcessHandle.current().pid();
      String request="{\"nonce\":"+quote(nonce)+",\"pid\":"+pid+",\"createdAt\":"+System.currentTimeMillis()+",\"cwd\":"+quote(cwd)+",\"sessionId\":\"aiaps-chat-native-probe\",\"clientChatId\":null,\"from\":{\"profileName\":\"Same profile\",\"agentName\":\"Original agent\"},\"to\":{\"profileName\":\"Same profile\",\"agentName\":\"Replacement agent\"},\"estimates\":"+estimates+"}";
      Path requestFile=rendezvous.resolve(nonce+".request.json"),responseFile=rendezvous.resolve(nonce+".response.json"),readyFile=rendezvous.resolve(nonce+".ready.json");Files.writeString(requestFile,request);
      await(()->{dialog=recoveryDialog();});
      edt(()->{
        check(!(boolean)call(call(dialog,"getOKAction"),"isEnabled"),"Sharing did not require consent");
        check(((Number)call(dialog,"getCount")).intValue()==5,"Wrong default history count");
        String shown=labels((Component)call(dialog,"getContentPane"));
        Files.writeString(dir.resolve("dialog-labels.txt"),shown);
        check(shown.contains("Same profile")&&shown.contains("Original agent")&&shown.contains("Replacement agent"),"Missing identity explanation");
        check(shown.replace('\\','/').toLowerCase(Locale.ROOT).contains(cwd.replace('\\','/').toLowerCase(Locale.ROOT)),"Missing working directory");
        if(accept){
          call(field(dialog,"historyCount"),"setValue",3);
          call(field(dialog,"consent"),"doClick");
          check((boolean)call(call(dialog,"getOKAction"),"isEnabled"),"Consent did not enable recovery");
          call(field(dialog,"instructions"),"setText","Continue safely");
          action(dialog,"getOKAction");
        }else action(dialog,"getCancelAction");
      });
      for(int i=0;i<100&&!Files.exists(responseFile);i++)Thread.sleep(50);
      check(Files.exists(responseFile),"Dialog did not publish a response");String response=Files.readString(responseFile);
      check(response.contains("\"approved\":"+accept),"Wrong approval result");
      if(accept){check(response.contains("\"exchanges\":3")&&response.contains("Continue safely"),"History selection not preserved");Files.writeString(readyFile,"{\"nonce\":"+quote(nonce)+",\"pid\":"+pid+",\"token\":null}");}
      else Files.deleteIfExists(requestFile);
      Files.deleteIfExists(responseFile);
      if(accept)for(int i=0;i<100&&Files.exists(readyFile);i++)Thread.sleep(50);
    }
    // Validate the exact installed Air request constructor without submitting a task.
    Object airId=call(type("com.intellij.openapi.extensions.PluginId"),"getId","com.intellij.air");
    Object air=call(type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",airId);
    Class<?> adapter=Class.forName("dev.boyko.aiagentswitcher.NativeRecoveryContinuation",true,loader);
    Method resolve=adapter.getDeclaredMethod("type",String.class,String.class);resolve.setAccessible(true);
    Object adapterInstance=adapter.getField("INSTANCE").get(null);
    Class<?> requestType=(Class<?>)resolve.invoke(adapterInstance,"com.intellij.air.backend.session.api.SessionPromptRequest","com.intellij.air");
    check(requestType!=null,"Air content-module loader was not resolved");
    Method prompt=adapter.getDeclaredMethod("airPrompt",Class.class,String.class,String.class);prompt.setAccessible(true);
    Object nativePrompt=prompt.invoke(adapter.getField("INSTANCE").get(null),requestType,"AI_MULTI_HANDOFF:fixture","");
    check(call(nativePrompt,"getText").equals("AI_MULTI_HANDOFF:fixture"),"Air lost handoff token");
    check(call(nativePrompt,"getDisplayText").equals("Continue with the new agent"),"Air exposes internal token as user text");
    Class<?> manager=(Class<?>)resolve.invoke(adapterInstance,"com.intellij.air.acp.AcpSessionManager","com.intellij.air");
    check(Arrays.stream(manager.getMethods()).anyMatch(m->m.getName().startsWith("sendPrompt-")&&m.getName().endsWith("$default")&&m.getParameterCount()==8),"Air task admission adapter changed");
    Class<?> bridge=Class.forName("dev.boyko.aiagentswitcher.compat.ChatHandoffClient",true,loader);
    check(Arrays.stream(bridge.getMethods()).anyMatch(m->m.getName().equals("continueNow")&&m.getParameterCount()==4),"AI Assistant task adapter changed");
  }
}
