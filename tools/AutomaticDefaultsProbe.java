import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Isolated native creation, clean-home capture and rejected-setting Settings probe. */
public class AutomaticDefaultsProbe {
  static Path dir; static Object project, app; static ClassLoader loader;
  static Object call(Object o,String n,Object...a)throws Exception{return SettingsRefreshProbe.call(o,n,a);}
  static Class<?> type(String n)throws Exception{return loader==null?SettingsRefreshProbe.type(n):Class.forName(n,true,loader);}
  static Object singleton(String n)throws Exception{return type("dev.boyko.aiagentswitcher."+n).getField("INSTANCE").get(null);}
  static Object field(Object o,String n)throws Exception{for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
  static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
  public static void premain(String arg,Instrumentation inst)throws Exception{
    dir=Path.of(arg).toAbsolutePath();SettingsRefreshProbe.inst=inst;SettingsRefreshProbe.dir=dir;
    ChatStartupSupport.inst=inst;ChatStartupSupport.dir=dir;
    check(Path.of(System.getProperty("aiaps.home")).toAbsolutePath().normalize().equals(dir.resolve("home")),"Sandbox only");
    check(dir.getParent().getFileName().toString().equals("build"),"Build sandbox only");
    System.setProperty("idea.trust.all.projects","true");
    Files.deleteIfExists(dir.resolve("error.txt"));Files.deleteIfExists(dir.resolve("result.txt"));
    new Thread(()->{int exit=1;try{
      for(int i=0;i<100;i++){Thread.sleep(1000);app=call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");if(app==null)continue;
        Object[] projects=(Object[])call(call(SettingsRefreshProbe.type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");if(projects.length>0){project=projects[0];break;}}
      check(project!=null,"No sandbox project");Object descriptor=call(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",call(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles"));loader=(ClassLoader)call(descriptor,"getPluginClassLoader");
      run();exit=0;
    }catch(Throwable e){try{java.io.StringWriter s=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(s));Files.writeString(dir.resolve("error.txt"),s.toString());}catch(Exception ignored){}}finally{System.exit(exit);}},"automatic-defaults-probe").start();
  }
  static Object create(String profile)throws Exception{
    ChatStartupSupport.chat=null;SettingsRefreshProbe.await(()->ChatStartupSupport.run("open"));
    for(int i=0;i<150&&ChatStartupSupport.chat==null;i++)Thread.sleep(100);
    check(ChatStartupSupport.chat!=null,"Native New Chat did not open");return ChatStartupSupport.chat;
  }
  static Object acquired(Object chat)throws Exception{Object agent=call(chat,"getAgentSession");if(agent==null)return null;return ((java.util.concurrent.atomic.AtomicReference<?>)field(field(agent,"lifecycleManager"),"clientSessionRef")).get();}
  static int discoveries()throws Exception{if(!Files.exists(dir.resolve("trace.txt")))return 0;return (int)Files.readAllLines(dir.resolve("trace.txt")).stream().filter(s->s.equals("discover")).count();}
  static void noSettings(){for(java.awt.Window w:java.awt.Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().contains("Settings"))throw new AssertionError("Missing defaults opened Settings");}
  static void run()throws Exception{
    Files.deleteIfExists(dir.resolve("trace.txt"));Path original=dir.resolve("account");Files.createDirectories(original);Files.writeString(original.resolve("auth.json"),"{\"synthetic\":true}");
    Path backend=dir.resolve("backend.js");Files.writeString(backend,"""
      const fs=require('fs'),path=require('path'),rl=require('readline').createInterface({input:process.stdin});let effort='low';
      const home=process.env.HOME,original=process.env.ORIGINAL_HOME;
      const options=()=>[{id:'effort',name:'Effort',type:'select',category:'model_config',currentValue:effort,options:[{value:'low',name:'Low'},{value:'high',name:'High'}]}];
      const send=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));
      rl.on('line',line=>{const m=JSON.parse(line);
        if(m.method==='initialize')return send({id:m.id,result:{protocolVersion:1,authMethods:[],agentCapabilities:{loadSession:true}}});
        if(m.method==='session/new'){
          const discovery=m.params.cwd===home&&home!==original;fs.appendFileSync(process.env.TRACE,discovery?'discover\\n':'create\\n');
          if(discovery){if(!fs.existsSync(path.join(home,'auth.json')))throw Error('No synthetic auth');if(fs.existsSync(path.join(home,'config.toml')))throw Error('Copied preferences');fs.writeFileSync(path.join(home,'catalog.json'),JSON.stringify({fetched_at:new Date().toISOString(),models:[{slug:'fixture'}]}));}
          return send({id:m.id,result:{sessionId:'fixture-'+process.pid+'-'+Date.now(),configOptions:options()}});
        }
        if(m.method==='session/set_config_option'){
          fs.appendFileSync(process.env.TRACE,'set:'+m.params.value+'\\n');
          if(m.params.value==='retired')return send({id:m.id,error:{code:-32070,message:'Fixture rejected retired effort',data:{native:true}}});
          effort=m.params.value;return send({id:m.id,result:{configOptions:options()}});
        }
        if(m.method==='session/prompt'){fs.appendFileSync(process.env.TRACE,'prompt\\n');return send({id:m.id,result:{stopReason:'end_turn'}});}
        if(m.id!==undefined)send({id:m.id,result:{}});
      });
      """);
    Object target=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
    call(target,"setId","automatic-target");call(target,"setName","Automatic target");call(target,"setAgentId","fixture.defaults");call(target,"setAdvancedOverrides",true);
    call(target,"setCommand","C:/Program Files/nodejs/node.exe");call(target,"setArguments",backend.toString());call(target,"setProfileHome",original.toString());call(target,"setInheritEnvironment",false);
    call(target,"setEnvironment","ORIGINAL_HOME="+original+"\nTRACE="+dir.resolve("trace.txt"));
    Object explicit=type("dev.boyko.aiagentswitcher.AgentProfiles$SessionSetting").getConstructor(String.class,String.class,String.class,String.class).newInstance("fixture.defaults","config","effort","high");
    call(target,"setSessionSettings",new ArrayList<>(List.of(explicit)));Object other=call(target,"snapshot");call(other,"setId","project-default");call(other,"setName","Project default");call(other,"setAgentId","");call(other,"setSessionSettings",new ArrayList<>());
    Object profiles=call(app,"getService",type("dev.boyko.aiagentswitcher.AgentProfiles"));call(profiles,"replace",List.of(target,other));
    call(singleton("GenericAutoRegistration"),"assignNow",project,"automatic-target");call(singleton("GenericAutoRegistration"),"ensureAsync",project);
    call(call(app,"getService",type("dev.boyko.aiagentswitcher.AgentRecoveryDialogs")),"start");Thread.sleep(3000);
    Object configLoader=type("com.intellij.ml.llm.agents.acp.runtime.config.AcpConfigurationLoader").getField("INSTANCE").get(null);
    Object config=call(configLoader,"loadFromPath",dir.resolve("home/.jetbrains/acp.json"));call(call(app,"getService",type("com.intellij.ml.llm.agents.acp.runtime.config.AcpAgentsConfigService")),"setLocalConfigForTesting",config);Thread.sleep(2000);
    Object first=create("automatic-target");for(int i=0;i<600&&acquired(first)==null;i++){noSettings();Thread.sleep(100);}check(acquired(first)!=null,"Automatic creation did not finish");noSettings();
    Object captured=call(profiles,"find","automatic-target");check(call(captured,"getDefaultsAgentId").equals("fixture.defaults"),"Defaults not committed");
    check(((List<?>)call(captured,"getAgentDefaults")).size()==2,"Both client defaults were not captured");check(call(captured,"getSessionSettings").equals(call(target,"getSessionSettings")),"Explicit settings changed");check(discoveries()==2,"Expected exactly two isolated client discoveries");Path savedConfig=Path.of((String)call(type("com.intellij.openapi.application.PathManager"),"getConfigPath"),"options/AcpSwitcherAgentProfiles.xml");check(Files.readString(savedConfig).contains("defaultsAgentId"),"Automatic defaults were not saved to disk");
    Object projectSelection=call(project,"getService",type("dev.boyko.aiagentswitcher.ProjectSelection"));check(call(projectSelection,"getSelected").equals("automatic-target"),"Chat changed project default");
    Thread.sleep(3000);SettingsRefreshProbe.edt(()->{Object input=call(call(first,"getAssociatedInput"),"getValue");call(input,"setText","fixture prompt");call(input,"invokeSendButtonAction",false,ChatStartupSupport.callback(v->ChatStartupSupport.result("send-result",v)));});for(int i=0;i<150&&!Files.readString(dir.resolve("trace.txt")).contains("prompt");i++)Thread.sleep(100);
    Object second=create("automatic-target");for(int i=0;i<150&&acquired(second)==null;i++){noSettings();Thread.sleep(100);}check(acquired(second)!=null,"Second creation failed");check(discoveries()==2,"Defaults refreshed again");
    check(Files.readString(dir.resolve("trace.txt")).contains("prompt"),"Native prompt was not sent");
    Object rejected=call(profiles,"find","automatic-target");Object stale=type("dev.boyko.aiagentswitcher.AgentProfiles$SessionSetting").getConstructor(String.class,String.class,String.class,String.class).newInstance("fixture.defaults","config","effort","retired");call(rejected,"setSessionSettings",new ArrayList<>(List.of(stale)));
    call(profiles,"replace",List.of(rejected,other));call(singleton("GenericAutoRegistration"),"assignNow",project,"automatic-target");
    create("automatic-target");boolean[] shown={false};for(int i=0;i<200&&!shown[0];i++){Thread.sleep(100);SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().contains("Settings")){JList<?> list=ChatStartupProbe.selectedProfile(d);if(list==null)return;check(call(list.getSelectedValue(),"getId").equals("automatic-target"),"Wrong error profile");shown[0]=true;ChatStartupProbe.cancel(d).doClick();}});}
    check(shown[0],"Agent rejection did not open profile Settings");check(discoveries()==2,"Rejection silently refreshed defaults");
    Files.writeString(dir.resolve("result.txt"),"PASS: native chat auto-captures both clients with synthetic auth, persists defaults, preserves explicit settings/project default, sends prompt, reuses snapshot, opens target Settings only after agent rejection");
  }
}
