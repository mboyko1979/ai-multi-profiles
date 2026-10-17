import java.lang.instrument.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import javax.swing.*;import java.awt.*;import java.awt.image.*;import javax.imageio.*;
/** Sandbox only: actual Settings editor and native pending session lifecycle, synthetic ACP provider. */
public class ConcreteDefaultsProbe {
 static Path dir;static ClassLoader loader;static Object app,project;static JFrame window;static Object editor,configurable;
 static Class<?> type(String n)throws Exception{return Class.forName(n,true,loader);}
 static Object call(Object o,String n,Object...a)throws Exception{return SettingsRefreshProbe.call(o,n,a);}
 static Object single(String n)throws Exception{return type("dev.boyko.aiagentswitcher."+n).getField("INSTANCE").get(null);}
 static Object field(Object o,String n)throws Exception{for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static java.util.List<Component> components(Component c){java.util.List<Component> out=new ArrayList<>();out.add(c);if(c instanceof Container p)for(Component child:p.getComponents())out.addAll(components(child));return out;}
 static Component link(Component root,String text){return components(root).stream().filter(c->{try{return call(c,"getText").equals(text);}catch(Exception ignored){return false;}}).findFirst().orElseThrow(()->new AssertionError("Missing "+text));}
 public static void premain(String arg,Instrumentation inst)throws Exception{
  dir=Path.of(arg).toAbsolutePath();Files.deleteIfExists(dir.resolve("error.txt"));Files.deleteIfExists(dir.resolve("result.txt"));SettingsRefreshProbe.inst=inst;SettingsRefreshProbe.dir=dir;ChatStartupSupport.inst=inst;ChatStartupSupport.dir=dir;
  if(!Path.of(System.getProperty("aiaps.home")).toAbsolutePath().normalize().equals(dir.resolve("home")))throw new IllegalStateException("Sandbox only");
  System.setProperty("idea.trust.all.projects","true");new Thread(()->{try{
   for(int i=0;i<100;i++){Thread.sleep(1000);app=call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");if(app==null)continue;Object[] ps=(Object[])call(call(SettingsRefreshProbe.type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");if(ps.length>0){project=ps[0];break;}}
   check(project!=null,"No sandbox project");Object id=call(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");loader=(ClassLoader)call(call(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",id),"getPluginClassLoader");
   run();Files.writeString(dir.resolve("result.txt"),"PASS: two native tabs, explicit Assistant load leaves Air unconfigured, concrete choices, per-model editor/default/disabled presentation, deprecated highlighting and save validation, native Settings cancellation leaves creation pending for 35 seconds, correcting settings resumes same backend session, native prompt succeeds. Synthetic provider only.");System.exit(0);
  }catch(Throwable e){try{java.io.StringWriter s=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(s));Files.writeString(dir.resolve("error.txt"),s.toString());}catch(Exception ignored){}System.exit(1);}},"concrete-defaults-probe").start();
 }
 static void screenshot(String name)throws Exception{SettingsRefreshProbe.edt(()->{JComponent root=(JComponent)window.getContentPane();BufferedImage img=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();root.paint(g);g.dispose();ImageIO.write(img,"png",dir.resolve(name+".png").toFile());});}
 @SuppressWarnings("unchecked") static java.util.List<Object> models(Object defaults)throws Exception{return (java.util.List<Object>)call(defaults,"getModels");}
 static Object acquired(Object chat)throws Exception{Object agent=call(chat,"getAgentSession");if(agent==null)return null;return ((java.util.concurrent.atomic.AtomicReference<?>)field(field(agent,"lifecycleManager"),"clientSessionRef")).get();}
 static int liveStarts()throws Exception{return (int)Files.readAllLines(dir.resolve("trace.txt")).stream().filter(s->s.equals("live-new")).count();}
 static void run()throws Exception{
  Files.deleteIfExists(dir.resolve("trace.txt"));
  Path backend=dir.resolve("backend.js");Files.writeString(backend,"""
   const fs=require('fs'),path=require('path'),rl=require('readline').createInterface({input:process.stdin});let model='a',effort='medium',fast='off';
   const home=process.env.CODEX_HOME;
   const option=(id,name,category,value,values)=>({id,name,category,type:'select',currentValue:value,options:values.map(v=>({value:v,name:v==='a'?'6.1 Sol':v==='b'?'6 Astra':v==='c'?'6 Luna':v})),...(id==='effort'?{_meta:{jetbrains:{air:{version:1,recommendedValue:model==='b'?'low':'medium'}}}}:{})});
   const options=()=>[option('model','Model','model',model,['a','b','c']),option('effort','Reasoning effort','thought_level',effort,['low','medium','high']),option('fast-mode','Fast mode','model_config',fast,['off','on'])];
   rl.on('line',line=>{const m=JSON.parse(line),send=result=>console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result}));
    if(m.method==='initialize'){fs.writeFileSync(process.env.TRACE+'.client.json',JSON.stringify(m.params.clientInfo));return send({protocolVersion:1,authMethods:[],agentInfo:{name:'fixture',version:'1.0'},agentCapabilities:{loadSession:true}});}
    if(m.method==='session/new'){
     fs.appendFileSync(process.env.TRACE,(m.params.cwd===home?'discovery':'live-new')+'\\n');
     fs.writeFileSync(path.join(home,'models_cache.json'),JSON.stringify({fetched_at:new Date().toISOString(),models:[{slug:'a'}]}));
     return send({sessionId:'fixture-'+process.pid,configOptions:options()});
    }
    if(m.method==='session/set_config_option'){if(m.params.configId==='model')model=m.params.value;else if(m.params.configId==='effort')effort=m.params.value;else fast=m.params.value;return send({configOptions:options()});}
    if(m.method==='session/prompt'){fs.appendFileSync(process.env.TRACE,'prompt\\n');return send({stopReason:'end_turn'});}
    send({});
   });
   """);
  Object entry=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();call(entry,"setId","concrete-fixture");call(entry,"setName","Personal fixture");call(entry,"setAgentId","acp.registry.codex-acp");call(entry,"setAgentName","Codex fixture");call(entry,"setAdvancedOverrides",true);
  call(entry,"setCommand",Files.readString(dir.resolve("node-path.txt")).trim());call(entry,"setArguments",backend.toString());call(entry,"setWorkingDirectory",dir.toString());Path home=dir.resolve("account");Files.createDirectories(home);Files.writeString(home.resolve("auth.json"),"{\"synthetic\":true}");call(entry,"setProfileHome",home.toString());call(entry,"setEnvironment","TRACE="+dir.resolve("trace.txt"));
  Object profiles=call(app,"getService",type("dev.boyko.aiagentswitcher.AgentProfiles"));call(profiles,"replace",java.util.List.of(entry));
  SettingsRefreshProbe.edt(()->{configurable=type("dev.boyko.aiagentswitcher.SwitcherConfigurable").getConstructor(type("com.intellij.openapi.project.Project")).newInstance(project);JComponent root=(JComponent)call(configurable,"createComponent");call(configurable,"reset");editor=field(configurable,"ui");window=new JFrame("AI Multi Profiles - isolated verification");window.setContentPane(root);window.setSize(1120,720);window.setLocationRelativeTo(null);window.setVisible(true);((JTabbedPane)field(editor,"profileTabs")).setSelectedIndex(1);});
  Object presets=field(editor,"presets");JComponent root=(JComponent)window.getContentPane();
  SettingsRefreshProbe.edt(()->{check(((JTabbedPane)field(editor,"profileTabs")).getTabCount()==4,"Wrong top-level tabs");call(link(root,"Load Agent Defaults"),"doClick");});
  for(int i=0;i<600 && (boolean)call(presets,"isLoading");i++)Thread.sleep(100);
  Object draft=field(presets,"profile"),md=single("ProfileModelDefaults"),defaults=call(md,"get",draft,"assistant");check(defaults!=null,"Load failed");check(call(md,"get",draft,"air")==null,"Assistant load configured Air");check(models(defaults).size()==3,"Models missing");
  SettingsRefreshProbe.edt(()->{
   check(components(root).stream().filter(c->c instanceof JComboBox<?>).noneMatch(c->{for(int i=0;i<((JComboBox<?>)c).getItemCount();i++)if(String.valueOf(((JComboBox<?>)c).getItemAt(i)).contains("Use agent default"))return true;return false;}),"Pseudo default choice remains");
   call(models(defaults).get(1),"setEnabled",false);call(presets,"show",draft,null);window.validate();
  });screenshot("models");
  Object selected=models(defaults).getFirst();java.util.List<?> values=(java.util.List<?>)call(selected,"getSettings");Object fast=values.stream().filter(x->{try{return call(x,"getId").equals("fast-mode");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();call(fast,"setValue","false");
  SettingsRefreshProbe.edt(()->{call(presets,"show",draft,null);window.validate();check(components(root).stream().filter(c->c instanceof JComboBox<?>).anyMatch(c->String.valueOf(((JComboBox<?>)c).getSelectedItem()).contains("Deprecated")),"No Deprecated value");});screenshot("deprecated");
  SettingsRefreshProbe.edt(()->{try{call(configurable,"apply");throw new AssertionError("Saved deprecated value");}catch(InvocationTargetException expected){check(expected.getCause().getMessage().contains("Deprecated"),"Wrong validation error");}});
  SettingsRefreshProbe.edt(()->{
   JComboBox box=(JComboBox)components(root).stream().filter(c->c instanceof JComboBox<?> && String.valueOf(((JComboBox<?>)c).getSelectedItem()).contains("Deprecated")).findFirst().orElseThrow();
   Object retired=box.getSelectedItem();Object valid=null;for(int i=0;i<box.getItemCount();i++)if(String.valueOf(box.getItemAt(i)).equals("off"))valid=box.getItemAt(i);
   check(valid!=null,"No valid replacement");box.setSelectedItem(valid);box.setSelectedItem(retired);check(box.getSelectedItem()==valid,"Deprecated option selected after correction");
   for(Object latest:(java.util.List<?>)call(selected,"getSettings"))if(call(latest,"getId").equals("fast-mode"))call(latest,"setValue","false");call(presets,"show",draft,null);
  });
  // Start an actual native chat with this deliberately incompatible saved fixture.
  call(profiles,"replace",java.util.List.of(draft));call(single("GenericAutoRegistration"),"assignNow",project,"concrete-fixture");call(single("GenericAutoRegistration"),"ensureAsync",project);call(call(app,"getService",type("dev.boyko.aiagentswitcher.AgentRecoveryDialogs")),"start");Thread.sleep(2500);
  SettingsRefreshProbe.edt(()->{window.dispose();call(configurable,"disposeUIResources");});
  Object configLoader=type("com.intellij.ml.llm.agents.acp.runtime.config.AcpConfigurationLoader").getField("INSTANCE").get(null);Object config=call(configLoader,"loadFromPath",dir.resolve("home/.jetbrains/acp.json"));call(call(app,"getService",type("com.intellij.ml.llm.agents.acp.runtime.config.AcpAgentsConfigService")),"setLocalConfigForTesting",config);
  call(single("GenericAutoRegistration"),"assignNow",project,"concrete-fixture");ChatStartupSupport.chat=null;SettingsRefreshProbe.await(()->ChatStartupSupport.run("open"));for(int i=0;i<150&&ChatStartupSupport.chat==null;i++)Thread.sleep(100);Object chat=ChatStartupSupport.chat;check(chat!=null,"Native chat missing");
  boolean[] shown={false};for(int i=0;i<200&&!shown[0];i++){Thread.sleep(100);SettingsRefreshProbe.edt(()->{for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().contains("Settings")){JList<?> list=ChatStartupProbe.selectedProfile(d);if(list==null)return;check(call(list.getSelectedValue(),"getId").equals("concrete-fixture"),"Wrong profile selected");shown[0]=true;ChatStartupProbe.cancel(d).doClick();}});}
  check(shown[0],"No Settings for invalid concrete preset");Thread.sleep(35000);check(acquired(chat)==null,"Cancel completed invalid chat");check(liveStarts()==1,"Duplicate live session before correction");
  Object repaired=call(profiles,"find","concrete-fixture");Object repairedDefaults=call(md,"get",repaired,"assistant");for(Object model:models(repairedDefaults))for(Object value:(java.util.List<?>)call(model,"getSettings"))if(call(value,"getId").equals("fast-mode"))call(value,"setValue","off");call(profiles,"replace",java.util.List.of(repaired));call(single("GenericAutoRegistration"),"assignNow",project,"concrete-fixture");
  for(int i=0;i<300&&acquired(chat)==null;i++)Thread.sleep(100);check(acquired(chat)!=null,"Native pending chat did not continue");check(liveStarts()==1,"Correction created replacement session");
  Thread.sleep(2000);SettingsRefreshProbe.edt(()->{Object input=call(call(chat,"getAssociatedInput"),"getValue");call(input,"setText","synthetic verification");call(input,"invokeSendButtonAction",false,ChatStartupSupport.callback(v->ChatStartupSupport.result("send-result",v)));});for(int i=0;i<150&&!Files.readString(dir.resolve("trace.txt")).contains("prompt");i++)Thread.sleep(100);check(Files.readString(dir.resolve("trace.txt")).contains("prompt"),"Native prompt failed");
 }
}
