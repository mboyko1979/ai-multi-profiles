import java.lang.instrument.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import javax.swing.*;
public class QuotaSwitchProbe {
 static Path dir;static Object chat;static Throwable failure;static boolean passed;static long promptsBefore;
 static Object call(Object o,String n,Object...a)throws Exception{return ChatStartupSupport.call(o,n,a);}
 static Class<?> type(String n)throws Exception{if(n.startsWith("com.intellij.ml."))return Class.forName(n,true,ChatStartupSupport.type("dev.boyko.aiagentswitcher.AgentProfiles").getClassLoader());return ChatStartupSupport.type(n);}
 static Object field(Object o,String n)throws Exception{for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
 static void edt(ChatStartupSupportTask task)throws Exception{SwingUtilities.invokeAndWait(()->{try{task.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new RuntimeException(failure);}
 interface ChatStartupSupportTask{void run()throws Exception;}
 static JList<?> selectedProfile(java.awt.Component c)throws Exception {if(c instanceof JList<?> l&&l.getSelectedValue()!=null&&l.getSelectedValue().getClass().getName().equals("dev.boyko.aiagentswitcher.AgentProfiles$Entry"))return l;if(c instanceof java.awt.Container p)for(java.awt.Component child:p.getComponents()){JList<?> found=selectedProfile(child);if(found!=null)return found;}return null;}
 static JButton cancel(java.awt.Component c){if(c instanceof JButton b&&b.getText().equals("Cancel"))return b;if(c instanceof java.awt.Container p)for(java.awt.Component child:p.getComponents()){JButton found=cancel(child);if(found!=null)return found;}return null;}
 static void verifyRefresh(Object app)throws Exception {
 Object profiles=call(app,"getService",type("dev.boyko.aiagentswitcher.AgentProfiles"));List<?> entries=(List<?>)call(profiles,"all");
 for(Object entry:entries)if(call(entry,"getId").equals("codex")){call(entry,"setAgentId","acp.registry.codex-acp");call(entry,"setAdvancedOverrides",true);call(entry,"setDefaultsAgentId","");}
 call(profiles,"replace",entries);call(type("dev.boyko.aiagentswitcher.GenericAutoRegistration").getField("INSTANCE").get(null),"assignNow",ChatStartupSupport.project(),"codex");
 edt(()->ChatStartupSupport.run("open"));
 boolean[] shown={false};for(int i=0;i<100&&!shown[0];i++){Thread.sleep(100);edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().contains("Settings")){JList<?> list=selectedProfile(d);if(list==null)return;if(!call(list.getSelectedValue(),"getId").equals("codex"))throw new AssertionError("Wrong Settings profile");JButton button=cancel(d);if(button==null)throw new AssertionError("No Settings Cancel");shown[0]=true;button.doClick();}});}
 if(!shown[0])throw new AssertionError("New Chat did not open profile Settings");
 Object newAgent=call(ChatStartupSupport.chat,"getAgentSession");
 if(((java.util.concurrent.atomic.AtomicReference<?>)field(field(newAgent,"lifecycleManager"),"clientSessionRef")).get()!=null)throw new AssertionError("Started a session before refreshing defaults");
 Files.writeString(dir.resolve("refresh-result.txt"),"PASS: native New Chat opens Settings with codex selected when its defaults snapshot is missing");
 }
 static void dump(String name,Object o)throws Exception{StringBuilder s=new StringBuilder(o.getClass().getName()+"\n");for(Method m:o.getClass().getMethods())s.append(m).append('\n');Files.writeString(dir.resolve(name+".txt"),s);}
 static void consent(java.awt.Component c){if(c instanceof JCheckBox b&&b.getText().contains("Share selected history"))b.doClick();if(c instanceof java.awt.Container p)for(java.awt.Component ch:p.getComponents())consent(ch);}
 static JButton findButton(java.awt.Component c,String text){if(c instanceof JButton b&&b.getText().equals(text))return b;if(c instanceof java.awt.Container p)for(java.awt.Component ch:p.getComponents()){JButton b=findButton(ch,text);if(b!=null)return b;}return null;}
 public static void premain(String arg,Instrumentation inst){dir=Path.of(arg);ChatStartupSupport.inst=inst;ChatStartupSupport.dir=dir;
 if(!Path.of(System.getProperty("aiaps.home", "")).toAbsolutePath().normalize().equals(dir.resolve("home").toAbsolutePath().normalize()) || !dir.toAbsolutePath().getParent().getFileName().toString().equals("build"))throw new IllegalStateException("Sandbox only");
 try{Files.deleteIfExists(dir.resolve("quota"));Files.deleteIfExists(dir.resolve("error.txt"));Files.deleteIfExists(dir.resolve("result.txt"));Files.deleteIfExists(dir.resolve("refresh-result.txt"));}catch(Exception e){throw new RuntimeException(e);} System.setProperty("idea.trust.all.projects","true");new Thread(()->{try{
 for(int i=0;;i++){try{if(ChatStartupSupport.project()!=null)break;}catch(Exception e){}if(i>100)throw new Exception("No project");Thread.sleep(1000);}
 edt(()->{ChatStartupSupport.run("seed");call(type("dev.boyko.aiagentswitcher.GenericAutoRegistration").getField("INSTANCE").get(null),"ensureAsync",ChatStartupSupport.project());}); Thread.sleep(3000);
 Object loader=type("com.intellij.ml.llm.agents.acp.runtime.config.AcpConfigurationLoader").getField("INSTANCE").get(null);
 Object config=call(loader,"loadFromPath",Path.of(System.getProperty("aiaps.home"),".jetbrains/acp.json"));
 Object app=call(type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
 Object configService=call(app,"getService",type("com.intellij.ml.llm.agents.acp.runtime.config.AcpAgentsConfigService"));call(configService,"setLocalConfigForTesting",config);
 Thread.sleep(3000);
 for(int attempt=0;attempt<100;attempt++){boolean found=false;for(Object mode:(List<?>)call(type("com.intellij.ml.llm.core.chat.ui.chat.input.chatModeSelector.AIAssistantNewChatModeKt"),"getAvailableChatModes",ChatStartupSupport.project()))if(call(mode,"getDisplayName").equals("AI Multi"))found=true;if(found)break;Thread.sleep(200);}
 if(Files.exists(dir.resolve("native-state.xml"))){
 Object host=call(ChatStartupSupport.project(),"getService",type("com.intellij.ml.llm.core.chat.session.ChatSessionHost"));
 Object store=call(ChatStartupSupport.project(),"getService",type("com.intellij.ml.llm.core.chat.session.ChatSessionStorage"));Object xml=call(type("com.intellij.openapi.util.JDOMUtil"),"load",dir.resolve("native-state.xml"));Object state=call(type("com.intellij.util.xmlb.XmlSerializer"),"deserialize",xml,type("com.intellij.ml.llm.core.chat.session.ChatSessionStorage$State"));call(store,"loadState",state);String uid=Files.readString(dir.resolve("saved-uid.txt")).trim();
 for(int attempt=0;attempt<100&&ChatStartupSupport.chat==null;attempt++){ChatStartupSupport.susp(store,"refreshDeferredChatsForCurrentAgents$intellij_ml_llm_chat",v->{});for(Object candidate:(List<?>)call(host,"getAllChats"))if(call(candidate,"getUid").equals(uid))ChatStartupSupport.chat=candidate;if(ChatStartupSupport.chat==null)Thread.sleep(100);}
 if(ChatStartupSupport.chat==null)throw new Exception("No saved chat "+uid);
 edt(()->{Object p=ChatStartupSupport.project();Object wm=call(type("com.intellij.openapi.wm.ToolWindowManager"),"getInstance",p);call(call(wm,"getToolWindow","AIAssistant"),"show",(Object)null);Object focused=call(p,"getService",type("com.intellij.ml.llm.core.chat.session.FocusedChatSessionHost"));call(focused,"focusChatSession",ChatStartupSupport.chat);});
 }else edt(()->ChatStartupSupport.run("open"));
 for(int i=0;ChatStartupSupport.chat==null;i++){if(i>100)throw new Exception("No chat");Thread.sleep(100);}

 // Non-AI-Multi native modes must receive no settings component at all.
 Object nonMulti=null;for(Object mode:(List<?>)call(type("com.intellij.ml.llm.core.chat.ui.chat.input.chatModeSelector.AIAssistantNewChatModeKt"),"getAvailableChatModes",ChatStartupSupport.project()))if(!call(mode,"getDisplayName").equals("AI Multi")){nonMulti=mode;break;}
 if(nonMulti==null)throw new AssertionError("No standard native mode fixture");
 Object standardMode=nonMulti;
 Object standard=Proxy.newProxyInstance(type("com.intellij.ml.llm.core.chat.session.ChatSession").getClassLoader(),new Class<?>[]{type("com.intellij.ml.llm.core.chat.session.ChatSession")},(proxy,m,args)->m.getName().equals("getCurrentChatMode")?standardMode:m.invoke(ChatStartupSupport.chat,args));
 edt(()->{Object provider=type("dev.boyko.aiagentswitcher.compat.AiProfileSetupPanelProvider").getConstructor().newInstance();Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);Object panel=((sun.misc.Unsafe)uf.get(null)).allocateInstance(type("com.intellij.ml.llm.core.chat.ui.chat.AIAssistantChatPanel"));Object comp=call(provider,"createChatPanelComponent",ChatStartupSupport.project(),panel,standard,call(ChatStartupSupport.chat,"getSessionScope"));if(((JComponent)call(comp,"getJComponent")).isVisible()||(boolean)call(call(comp,"getIsVisibleProperty"),"get"))throw new AssertionError("Settings leaked into standard mode");Files.writeString(dir.resolve("visibility-result.txt"),"PASS: standard native mode receives invisible empty settings component, with no chat panel hooks");});
 chat=ChatStartupSupport.chat;for(int wait=0;wait<100&&call(call(chat,"getAssociatedInput"),"getValue")==null;wait++)Thread.sleep(100);Thread.sleep(5000);dump("chat",chat);dump("host",call(ChatStartupSupport.project(),"getService",type("com.intellij.ml.llm.core.chat.session.ChatSessionHost")));
 Object agent=call(chat,"getAgentSession");dump("agent",agent);Object manager=field(agent,"lifecycleManager");dump("manager",manager);
 if(Files.exists(dir.resolve("native-state.xml"))){Object acquired=((java.util.concurrent.atomic.AtomicReference<?>)field(manager,"clientSessionRef")).get();if(acquired==null)throw new AssertionError("Restore has no acquired session");Object nativeSession=call(acquired,"getSession");if((boolean)call(nativeSession,"getConfigOptionsSupported"))throw new AssertionError("Fixture did not restore the optional-options case");}
 Files.writeString(dir.resolve(Files.exists(dir.resolve("reopen"))?"reopened-state.txt":"state.txt"),"live="+field(manager,"clientSessionRef")+"\nstate="+call(chat,"getState")+"\n");
 edt(()->call(call(call(chat,"getAssociatedInput"),"getValue"),"setText","fixture question"));Thread.sleep(700);
 edt(()->{Object input=call(call(chat,"getAssociatedInput"),"getValue");if(input==null)throw new Exception("Restored input missing");dump("input",input);call(input,"setText","fixture question");promptsBefore=Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().filter(line->line.contains("session/prompt")).count();Object dm=call(type("com.intellij.ide.DataManager"),"getInstance");Object context=call(dm,"getDataContext",input);Object action=call(call(type("com.intellij.openapi.actionSystem.ActionManager"),"getInstance"),"getAction","AIAssistant.Chat.SendActions.Send");Object event=call(type("com.intellij.openapi.actionSystem.AnActionEvent"),"createFromAnAction",action,null,"AIAssistantChatInputRight",context);call(action,"update",event);Object pres=call(event,"getPresentation");Files.writeString(dir.resolve("send-state.txt"),"chat="+call(chat,"getState")+"\ninput="+call(input,"getSendActionState")+"\npresentation="+pres+"\nenabled="+call(pres,"isEnabled")+"\n");if(!(boolean)call(pres,"isEnabled"))throw new AssertionError("Normal Send disabled");call(action,"actionPerformed",event);});Thread.sleep(5000);if(Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().filter(line->line.contains("session/prompt")).count()<=promptsBefore)throw new AssertionError("Normal Send did not reach backend");

 // A second ordinary native prompt receives the exact provider quota error.
 Files.writeString(dir.resolve("quota"),"yes");
 edt(()->call(call(call(chat,"getAssociatedInput"),"getValue"),"setText","quota fixture question"));Thread.sleep(700);
 edt(()->{Object input=call(call(chat,"getAssociatedInput"),"getValue");Object dm=call(type("com.intellij.ide.DataManager"),"getInstance");Object context=call(dm,"getDataContext",input);Object action=call(call(type("com.intellij.openapi.actionSystem.ActionManager"),"getInstance"),"getAction","AIAssistant.Chat.SendActions.Send");Object ev=call(type("com.intellij.openapi.actionSystem.AnActionEvent"),"createFromAnAction",action,null,"AIAssistantChatInputRight",context);call(action,"actionPerformed",ev);});
 for(int i=0;i<100;i++){Thread.sleep(100);if(call(chat,"getState").toString().equals("Idle")&&((java.util.concurrent.atomic.AtomicReference<?>)field(manager,"clientSessionRef")).get()==null)break;}
 Files.writeString(dir.resolve("quota-state.txt"),"state="+call(chat,"getState")+" live="+field(manager,"clientSessionRef"));
 if(((java.util.concurrent.atomic.AtomicReference<?>)field(manager,"clientSessionRef")).get()!=null)throw new AssertionError("Quota did not discard native session");
 Object chooser=type("dev.boyko.aiagentswitcher.compat.CurrentChatProfileSwitch").getField("INSTANCE").get(null);
 for(boolean approve:new boolean[]{false,true}){
 ChatStartupSupport.susp(chooser,"choose",v->ChatStartupSupport.result("choose-"+approve,v),chat,"grok");
 boolean[] seen={false};for(int i=0;i<150&&!seen[0];i++){Thread.sleep(100);edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().equals("Switch AI Profile")){seen[0]=true;JButton b=findButton(d,approve?"Start New Agent Session":"Cancel");if(b==null)throw new AssertionError("Missing dialog action");if(approve)consent(d);if(!b.isEnabled())throw new AssertionError("Dialog action disabled");b.doClick();}});}
 if(!seen[0])throw new AssertionError("No profile dialog after quota; approve="+approve);
 Thread.sleep(3000);
 if(!approve&&Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().anyMatch(l->l.contains("grok-")&&l.contains("session/prompt")))throw new AssertionError("Cancel transferred history");
 }
 for(int i=0;i<100;i++){Thread.sleep(200);if(Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().anyMatch(l->l.contains("grok-")&&l.contains("session/prompt")))break;}
 if(!Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().anyMatch(l->l.contains("grok-")&&l.contains("session/prompt")))throw new AssertionError("Approved switch did not continue");
 Files.writeString(dir.resolve("result.txt"),"PASS: ordinary native prompt quota discards session; selection opens explicit dialog; cancellation sends no prompt; retry approval creates target session and continues");passed=true;
 }catch(Throwable e){try{java.io.StringWriter s=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(s));Files.writeString(dir.resolve("error.txt"),s.toString());}catch(Exception ignored){}}finally{System.exit(passed?0:1);}},"startup-probe").start();}
}
