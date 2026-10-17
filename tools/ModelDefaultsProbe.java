import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Isolated runIde probe. Synthetic provider and credentials only. */
public class ModelDefaultsProbe {
  static Object call(Object o,String name,Object... args)throws Exception{return SettingsRefreshProbe.call(o,name,args);}
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
  static Object singleton(ClassLoader loader,String name)throws Exception{return Class.forName("dev.boyko.aiagentswitcher."+name,true,loader).getField("INSTANCE").get(null);}
  public static void premain(String arg,Instrumentation instrumentation)throws Exception {
    SettingsRefreshProbe.inst=instrumentation;SettingsRefreshProbe.dir=Path.of(arg).toAbsolutePath();
    if(!System.getProperty("aiaps.home","").replace('\\','/').endsWith("/build/defaults61/home"))throw new IllegalStateException("Sandbox only");
    System.setProperty("idea.trust.all.projects","true");
    new Thread(()->{
      Throwable failure=null;
      for(int i=0;i<100;i++)try{
        Thread.sleep(1000);
        SettingsRefreshProbe.app=call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
        if(SettingsRefreshProbe.app==null)continue;
        Object[] projects=(Object[])call(call(SettingsRefreshProbe.type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");
        if(projects.length==0)continue;
        Files.writeString(SettingsRefreshProbe.dir.resolve("started"),"started");run(projects[0]);failure=null;break;
      }catch(Throwable e){failure=e;if(Files.exists(SettingsRefreshProbe.dir.resolve("started")))break;}
      try{if(failure!=null){java.io.StringWriter out=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(out));Files.writeString(SettingsRefreshProbe.dir.resolve("error.txt"),out.toString());}}
      catch(Exception ignored){}System.exit(failure==null?0:1);
    },"model-defaults-probe").start();
  }
  static void run(Object project)throws Exception {
    Path dir=SettingsRefreshProbe.dir;
    Object pluginId=call(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
    Object descriptor=call(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",pluginId);
    ClassLoader loader=(ClassLoader)call(descriptor,"getPluginClassLoader");
    Object clients=singleton(loader,"compat.DefaultsClients");
    for(String host:List.of("assistant","air"))Files.writeString(dir.resolve("native-"+host+".json"),call(clients,"initialize",project,host).toString());
    Path original=dir.resolve("original");Files.createDirectories(original);Files.writeString(original.resolve("auth.json"),"{\"synthetic\":true}");
    Files.writeString(original.resolve("models_cache.json"),"STALE ORIGINAL CACHE");Files.writeString(original.resolve("config.toml"),"model_reasoning_effort = 'high'\n");
    Path backend=dir.resolve("backend.js");
    Files.writeString(backend,"""
      const fs=require('fs'),path=require('path'),rl=require('readline').createInterface({input:process.stdin});let air=false,model='a',capabilities;
      const models=['a','b','unknown'];
      function options(){return [{id:'model',name:'Model',type:'select',category:'model',currentValue:model,options:models.map(value=>({value,name:value.toUpperCase()}))},
        {id:'reasoning_effort',name:'Reasoning',type:'select',category:'thought_level',currentValue:'high',options:['low','medium','high'].map(value=>({value,name:value[0].toUpperCase()+value.slice(1)})),
        ...(model==='unknown'?{}:{_meta:{jetbrains:{air:{version:1,recommendedValue:model==='a'?'medium':'low'}}}})}];}
      rl.on('line',line=>{const m=JSON.parse(line),send=result=>console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result}));
        if(m.method==='initialize'){capabilities=m.params.clientCapabilities;air=capabilities._meta?.jetbrains?.air?.capabilities?.includes('sessionFailure')===true;send({protocolVersion:1,agentInfo:{name:'fixture',version:'1'},agentCapabilities:{}});}
        else if(m.method==='session/new'){
          if(process.env.FAIL_AIR && air)return console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,error:{code:-1,message:'fixture failure'}}));
          const home=process.env.CODEX_HOME||process.env.HOME;
          if(fs.existsSync(path.join(home,'config.toml'))||fs.existsSync(path.join(home,'models_cache.json')))throw Error('Copied original settings or cache');
          fs.writeFileSync(path.join(home,'models_cache.json'),JSON.stringify({fetched_at:new Date().toISOString(),models:[{slug:'a'}]}));
          send({sessionId:'metadata',configOptions:options()});
        }else if(m.method==='session/set_config_option') {model=m.params.value;send({configOptions:options()});}
        else if(m.method==='session/prompt')throw Error('Metadata discovery sent a prompt');else send({});
      });
      """);
    Class<?> entryType=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$Entry",true,loader);
    Object entry=entryType.getConstructor().newInstance();call(entry,"setId","model-defaults-fixture");call(entry,"setName","Personal fixture");
    call(entry,"setAdvancedOverrides",true);call(entry,"setAgentId","acp.registry.codex-acp");call(entry,"setCommand","C:/Program Files/nodejs/node.exe");call(entry,"setArguments",backend.toString());
    call(entry,"setWorkingDirectory",dir.toString());call(entry,"setProfileHome",original.toString());
    Object configuration=singleton(loader,"ProfileConfiguration"),probe=singleton(loader,"ProfileDefaultsProbe");
    Class<?> settingType=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$SessionSetting",true,loader);
    Object explicit=settingType.getConstructor(String.class,String.class,String.class,String.class).newInstance("acp.registry.codex-acp","config","reasoning_effort","high");
    call(entry,"setSessionSettings",new ArrayList<>(List.of(explicit)));
    // No client catalog yet: both tabs keep a compact selector, never the wide button row.
    SettingsRefreshProbe.edt(()->{
      Object fresh=call(entry,"snapshot");call(fresh,"setId","unrefreshed-"+UUID.randomUUID());
      Class<?> panelType=Class.forName("dev.boyko.aiagentswitcher.ProfilePresetPanel",true,loader);
      Object initial=panelType.getConstructors()[0].newInstance(project);call(initial,"show",fresh);
      JTabbedPane tabs=(JTabbedPane)SettingsRefreshProbe.field(initial,"tabs");
      for(int i=0;i<2;i++){
        tabs.setSelectedIndex(i);JComboBox<?> box=find((java.awt.Container)tabs.getSelectedComponent(),"high (saved; refresh required)");
        check(box!=null,"Before Refresh lost saved selector for client "+i);
        check(tabs.getTitleAt(i).startsWith("●"),"Active client not visibly marked");
      }
      JPanel image=(JPanel)initial;image.setSize(720,220);layout(image);
      java.awt.image.BufferedImage png=new java.awt.image.BufferedImage(720,220,java.awt.image.BufferedImage.TYPE_INT_RGB);
      java.awt.Graphics2D graphics=png.createGraphics();image.printAll(graphics);graphics.dispose();
      javax.imageio.ImageIO.write(png,"png",dir.resolve("before-refresh.png").toFile());
      Object other=call(fresh,"snapshot");call(other,"setId","empty-"+UUID.randomUUID());call(other,"setSessionSettings",new ArrayList<>());
      call(initial,"show",other);for(int i=0;i<2;i++){tabs.setSelectedIndex(i);check(find((java.awt.Container)tabs.getSelectedComponent(),"high (saved; refresh required)")==null,"Hidden previous-profile controls leaked");}
      call(initial,"dispose");
    });
    Map<?,?> values=(Map<?,?>)call(probe,"discoverClients",project,entry);
    check(values.size()==2,"Refresh did not collect both clients");
    check(((List<?>)call(entry,"getSessionSettings")).size()==1&&call(explicit,"getValue").equals("high"),"Refresh overwrote explicit Profile Default");
    check(Files.readString(original.resolve("models_cache.json")).equals("STALE ORIGINAL CACHE"),"Original cache changed");
    String before=call(entry,"getAgentDefaults").toString();
    final Object[] panel={null};
    SettingsRefreshProbe.edt(()->{
      Class<?> panelType=Class.forName("dev.boyko.aiagentswitcher.ProfilePresetPanel",true,loader);
      panel[0]=panelType.getConstructors()[0].newInstance(project);call(panel[0],"show",entry);
      JTabbedPane tabs=(JTabbedPane)SettingsRefreshProbe.field(panel[0],"tabs");
      check(tabs.getTabCount()==2,"Missing client tabs");
      verifyReasoning((java.awt.Container)tabs.getSelectedComponent(),"Use agent default (Medium now)","High");
      JComboBox<?> model=find((java.awt.Container)tabs.getSelectedComponent(),"B");
      for(int i=0;i<model.getItemCount();i++)if(model.getItemAt(i).toString().equals("B")){model.setSelectedIndex(i);break;}
      verifyReasoning((java.awt.Container)tabs.getSelectedComponent(),"Use agent default (Low now)","High");
      tabs.setSelectedIndex(1);verifyReasoning((java.awt.Container)tabs.getSelectedComponent(),"Use agent default (Medium now)","High");
      JComboBox<?> airReasoning=find((java.awt.Container)tabs.getSelectedComponent(),"Low");
      select(airReasoning,"Low");
      tabs.setSelectedIndex(0);verifyReasoning((java.awt.Container)tabs.getSelectedComponent(),"Use agent default (Low now)","High");
      tabs.setSelectedIndex(1);verifyReasoning((java.awt.Container)tabs.getSelectedComponent(),"Use agent default (Medium now)","Low");
      select(airReasoning,"Use agent default (Medium now)");
      tabs.setSelectedIndex(0);verifyReasoning((java.awt.Container)tabs.getSelectedComponent(),"Use agent default (Low now)","High");
      tabs.setSelectedIndex(0);model=find((java.awt.Container)tabs.getSelectedComponent(),"UNKNOWN");
      for(int i=0;i<model.getItemCount();i++)if(model.getItemAt(i).toString().equals("UNKNOWN")){model.setSelectedIndex(i);break;}
      verifyReasoning((java.awt.Container)tabs.getSelectedComponent(),"Use agent default (unknown)","High");

    });
    SettingsRefreshProbe.edt(()->{
      call(panel[0],"refreshOptions");
      check(!SettingsRefreshProbe.enabled(panel[0],"refresh"),"Refresh did not disable while both clients load");
      check(SettingsRefreshProbe.text(panel[0],"refresh").equals("Refreshing agents defaults..."),"Full progress caption missing");
    });
    SettingsRefreshProbe.await(()->check(SettingsRefreshProbe.enabled(panel[0],"refresh"),"Combined refresh still loading"));
    SettingsRefreshProbe.edt(()->{
      JTabbedPane tabs=(JTabbedPane)SettingsRefreshProbe.field(panel[0],"tabs");
      JComboBox<?> box=find((java.awt.Container)tabs.getSelectedComponent(),"High");
      check(box!=null&&box.getSelectedItem().toString().equals("High"),"UI Refresh replaced explicit High");
      call(panel[0],"dispose");
    });
    Files.writeString(original.resolve("config.toml"),"model_reasoning_effort = 'low'\n");
    call(entry,"setName","Work fixture");call(entry,"setSessionSettings",new ArrayList<>());
    Map<?,?> again=(Map<?,?>)call(probe,"discoverClients",project,entry);
    Object a=values.get("assistant"),b=again.get("assistant");
    check(call(a,"getModels").toString().equals(call(b,"getModels").toString()),"Profile/current preference changed Model Defaults");
    call(entry,"setEnvironment","FAIL_AIR=1");String saved=call(entry,"getAgentDefaults").toString();
    try{call(probe,"discoverClients",project,entry);throw new AssertionError("Failed combined refresh accepted");}
    catch(java.lang.reflect.InvocationTargetException expected){}
    check(call(entry,"getAgentDefaults").toString().equals(saved),"Failed refresh changed inherited defaults");
    verifyRestoreDialog(project,loader);
    AirProfileProbe.verify(project,loader,dir);
    Files.writeString(dir.resolve("result.txt"),"PASS: real native client factories and serialization, both-client refresh, isolated clean auth homes, current High vs recommended Medium, A/B model defaults, unknown without fallback, two clearly marked native tabs, before-refresh layout and hidden-tab reset, independent per-client selections/model maps, explicit High retained, profile independence, stable repeat, failed combined refresh retains defaults; no ordinary chat or prompt used\n");
  }
  static void verifyRestoreDialog(Object project,ClassLoader loader)throws Exception {
    Class<?> broker=Class.forName("dev.boyko.aiagentswitcher.AgentRecoveryDialogs",true,loader);
    call(call(SettingsRefreshProbe.app,"getService",broker),"start");
    Path rendezvous=Path.of(System.getProperty("aiaps.home"),".jetbrains","ai-agent-project-switcher","agent-recovery");Files.createDirectories(rendezvous);
    for(boolean approve:new boolean[]{false,true}){
      String nonce=UUID.randomUUID().toString();long pid=ProcessHandle.current().pid();
      String cwd=String.valueOf(call(project,"getBasePath"));
      String request="{\"kind\":\"restore-existing\",\"nonce\":"+quote(nonce)+",\"pid\":"+pid+",\"createdAt\":"+System.currentTimeMillis()+",\"cwd\":"+quote(cwd)+",\"sessionId\":\"aiaps-chat-probe\",\"from\":{\"profileName\":\"Work fixture\",\"agentName\":\"Codex\",\"agentId\":\"registry.codex\"},\"to\":{\"profileName\":\"Work fixture\",\"agentName\":\"Codex\",\"agentId\":\"registry.codex\"}}";
      Files.writeString(rendezvous.resolve(nonce+".request.json"),request);
      SettingsRefreshProbe.await(()->{
        JDialog window=null;for(java.awt.Window candidate:java.awt.Window.getWindows())if(candidate instanceof JDialog d && d.isShowing() && d.getTitle().equals("Restore Existing Chat"))window=d;
        check(window!=null,"Waiting for existing-session confirmation");
        JButton button=button(window,approve?"Restore":"Cancel");check(button!=null,"Missing confirmation button");button.doClick();
      });
      Path response=rendezvous.resolve(nonce+".response.json");SettingsRefreshProbe.await(()->check(Files.exists(response),"Waiting for restoration response"));
      String answer=Files.readString(response);check(answer.contains("\"approved\":"+approve)&&answer.contains("\"restoreExisting\":true"),"Wrong existing-session consent");
      if(approve){Files.writeString(rendezvous.resolve(nonce+".ready.json"),"{\"nonce\":"+quote(nonce)+",\"pid\":"+pid+",\"token\":null}");SettingsRefreshProbe.await(()->check(!Files.exists(rendezvous.resolve(nonce+".ready.json")),"Ready cleanup pending"));}
      Files.deleteIfExists(response);Files.deleteIfExists(rendezvous.resolve(nonce+".request.json"));
    }
  }
  static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  static JButton button(java.awt.Container c,String text){for(java.awt.Component child:c.getComponents()){if(child instanceof JButton b&&text.equals(b.getText()))return b;if(child instanceof java.awt.Container box){JButton found=button(box,text);if(found!=null)return found;}}return null;}
  static void layout(java.awt.Container c){c.doLayout();for(java.awt.Component child:c.getComponents())if(child instanceof java.awt.Container nested)layout(nested);}
  static void select(JComboBox<?> box,String value){check(box!=null,"Missing selector "+value);for(int i=0;i<box.getItemCount();i++)if(box.getItemAt(i).toString().equals(value)){box.setSelectedIndex(i);return;}throw new AssertionError("Missing choice "+value);}
  static JComboBox<?> find(java.awt.Container root,String item){
    for(java.awt.Component child:root.getComponents()){
      if(child instanceof JComboBox<?> box){for(int i=0;i<box.getItemCount();i++)if(box.getItemAt(i).toString().equals(item))return box;}
      if(child instanceof java.awt.Container container){JComboBox<?> found=find(container,item);if(found!=null)return found;}
    }return null;
  }
  static void verifyReasoning(java.awt.Container root,String inherited,String selected){JComboBox<?> box=find(root,inherited);check(box!=null,"Missing "+inherited);check(box.getSelectedItem().toString().equals(selected),"Explicit selection changed");}
}
