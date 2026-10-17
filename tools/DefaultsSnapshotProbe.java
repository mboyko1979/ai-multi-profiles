import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

/** Runs only in the isolated settings sandbox, with synthetic credentials and agents. */
public class DefaultsSnapshotProbe {
  public static void premain(String arg, Instrumentation instrumentation) throws Exception {
    SettingsRefreshProbe.inst=instrumentation;
    SettingsRefreshProbe.dir=Path.of(arg).toAbsolutePath();
    if(!System.getProperty("aiaps.home","").replace('\\','/').endsWith("/build/settings53/home"))throw new IllegalStateException("Sandbox only");
    System.setProperty("idea.trust.all.projects","true");
    new Thread(()->{
      Throwable failure=null;
      try {
        Object project=null;
        for(int i=0;i<100;i++) {
          Thread.sleep(1000);
          SettingsRefreshProbe.app=SettingsRefreshProbe.call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
          if(SettingsRefreshProbe.app==null)continue;
          Object[] projects=(Object[])SettingsRefreshProbe.call(SettingsRefreshProbe.call(SettingsRefreshProbe.type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");
          if(projects.length>0){project=projects[0];break;}
        }
        if(project==null)throw new AssertionError("Sandbox project did not open");
        run(project);
        Files.copy(SettingsRefreshProbe.dir.resolve("result.txt"), SettingsRefreshProbe.dir.resolve("defaults-result.txt"), StandardCopyOption.REPLACE_EXISTING);
        SettingsRefreshProbe.run(project);
      }catch(Throwable e){failure=e;}
      try {
        if(failure!=null){java.io.StringWriter out=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(out));Files.writeString(SettingsRefreshProbe.dir.resolve("error.txt"),out.toString());}
      }catch(Exception ignored){}
      System.exit(failure==null?0:1);
    },"defaults-snapshot-probe").start();
  }
  static Object invoke(Object o,String name,Object...args)throws Exception{return SettingsRefreshProbe.call(o,name,args);}
  static void check(boolean value,String message){SettingsRefreshProbe.check(value,message);}
  static void run(Object project)throws Exception {
    Path dir=SettingsRefreshProbe.dir;
    Path backend=dir.resolve("backend.js");
    Files.writeString(backend,"""
      const fs=require('fs'),path=require('path'),rl=require('readline').createInterface({input:process.stdin});
      const home=process.env.HOME;
      if(process.cwd().toLowerCase()!==process.env.ORIGINAL_HOME.toLowerCase())throw Error('Lost installed launch directory');
      if(home===process.env.ORIGINAL_HOME||fs.existsSync(path.join(home,'models_cache.json'))||fs.existsSync(path.join(home,'config.toml')))
        throw Error('Reused provider cache or config');
      if(!fs.existsSync(path.join(home,'auth.json')))throw Error('Missing account authorization');
      fs.writeFileSync(process.env.HOME_TRACE,home);
      rl.on('line',line=>{const m=JSON.parse(line),send=x=>console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,...x}));
        if(m.method==='initialize')send({result:{protocolVersion:1,agentCapabilities:{},agentInfo:{name:'mock'}}});
        else if(m.method==='session/new'){
          if(m.params.cwd!==home)throw Error('Session cwd was not isolated');
          if(!process.env.FAIL_FETCH)fs.writeFileSync(path.join(home,'provider-catalog.json'),JSON.stringify({fetched_at:new Date().toISOString(),models:process.env.OBJECT_CATALOG?{mock:{info:{}}}:[{slug:'mock'}]}));
          send({result:{sessionId:'mock',configOptions:[{id:'reasoning_effort',name:'Reasoning',type:'select',category:'thought_level',currentValue:'low',options:[{value:'low',name:'Low'},{value:'high',name:'High'}]}]}});
        }else send({result:{}});
      });
      """);
    // Load the plugin using the installed descriptor's classloader.
    Object pluginId=invoke(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
    Object descriptor=invoke(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",pluginId);
    ClassLoader loader=(ClassLoader)invoke(descriptor,"getPluginClassLoader");
    Class<?> entryType=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$Entry",true,loader);
    Object probe=Class.forName("dev.boyko.aiagentswitcher.ProfileDefaultsProbe",true,loader).getField("INSTANCE").get(null);
    for(String agent:List.of("fixture.one","fixture.two")) {
      Path original=Files.createTempDirectory(dir,"original-");
      Files.writeString(original.resolve("auth.json"),"{\"synthetic\":true}");
      Files.writeString(original.resolve("models_cache.json"),"ORIGINAL_STALE_CACHE");
      Files.writeString(original.resolve("config.toml"),"[ui]\ncompact_mode = true\n");
      Path trace=original.resolve("home.txt");
      Object entry=entryType.getConstructor().newInstance();
      invoke(entry,"setId",UUID.randomUUID().toString());invoke(entry,"setName","Fixture");invoke(entry,"setAgentId",agent);
      invoke(entry,"setProfileHome",original.toString());invoke(entry,"setCommand","C:/Program Files/nodejs/node.exe");
      invoke(entry,"setWorkingDirectory",original.toString());
      invoke(entry,"setArguments",backend.toString());
      String env="ORIGINAL_HOME="+original+"\nHOME_TRACE="+trace+(agent.endsWith("two")?"\nOBJECT_CATALOG=1":"");
      invoke(entry,"setEnvironment",env);
      invoke(probe,"discover",project,entry);
      check(invoke(entry,"getDefaultsAgentId").equals(agent),"Default identity not captured");
      List<?> defaults=(List<?>)invoke(entry,"getAgentDefaults");
      check(defaults.size()==1&&invoke(defaults.getFirst(),"getValue").equals("low"),"Fresh defaults not captured");
      check(!Files.exists(Path.of(Files.readString(trace))),"Temporary credential home remains");
      check(Files.readString(original.resolve("models_cache.json")).equals("ORIGINAL_STALE_CACHE"),"Live provider cache changed");
      String saved=defaults.toString();Object observed=invoke(entry,"getDefaultsObservedAt");
      invoke(entry,"setEnvironment",env+"\nFAIL_FETCH=1");
      try {invoke(probe,"discover",project,entry);throw new AssertionError("Offline catalog accepted");}
      catch(java.lang.reflect.InvocationTargetException expected){}
      check(invoke(entry,"getAgentDefaults").toString().equals(saved)&&invoke(entry,"getDefaultsObservedAt").equals(observed),"Failed refresh changed saved defaults");
      check(!Files.exists(Path.of(Files.readString(trace))),"Failed refresh left temporary authorization");
    }
    Files.writeString(dir.resolve("result.txt"),"PASS: two unknown agents, clean homes with auth only, stale caches/config excluded, fresh catalogs in array/object formats captured, temporary credential homes removed, offline refresh preserves saved defaults and live provider caches\n");
  }
}
