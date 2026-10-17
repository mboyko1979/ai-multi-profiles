import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Actual Air manager/session admission + synthetic ACP router, in the isolated IDE only. */
public class AirProfileProbe {
  static ClassLoader loader;static Object nativeBridge;
  static Object launchContext,showingBinding;
  static Object call(Object o,String name,Object...args)throws Exception {
    Class<?> c=o instanceof Class<?> t?t:o.getClass();
    for(Method m:Arrays.stream(c.getMethods()).sorted(Comparator.comparing(m->!m.getName().equals(name))).toList())if(m.getName().startsWith(name)&&m.getParameterCount()==args.length){
      boolean matches=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isInstance(args[i])&&!m.getParameterTypes()[i].isPrimitive())matches=false;
      if(matches){m.setAccessible(true);try{return m.invoke(o instanceof Class<?>?null:o,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
    }throw new NoSuchMethodException(c+" "+name);
  }
  static Class<?> type(String name)throws Exception{return (Class<?>)call(nativeBridge,"type",name,"com.intellij.air");}
  static Object field(Object o,String name)throws Exception{return call(nativeBridge,"field",o,name);}
  static Object suspend(Object o,String name,Object...args)throws Exception {
    Class<?> continuation=Class.forName("kotlin.coroutines.Continuation",true,loader),empty=Class.forName("kotlin.coroutines.EmptyCoroutineContext",true,loader);
    CompletableFuture<Object> future=new CompletableFuture<>();
    Object cont=Proxy.newProxyInstance(loader,new Class<?>[]{continuation},(proxy,m,a)->{
      if(m.getName().equals("getContext"))return empty.getField("INSTANCE").get(null);
      if(m.getName().equals("resumeWith")){try{Class.forName("kotlin.ResultKt",true,loader).getMethod("throwOnFailure",Object.class).invoke(null,a[0]);future.complete(a[0]);}catch(Throwable e){future.completeExceptionally(e);}return null;}return null;
    });
    Object[] parameters=Arrays.copyOf(args,args.length+1);parameters[args.length]=cont;Object result=call(o,name,parameters);
    if(result!=null&&result.toString().equals("COROUTINE_SUSPENDED"))return future.get(40,TimeUnit.SECONDS);return result;
  }
  static Object construct(Class<?> type,Object...args)throws Exception {
    for(Constructor<?> c:type.getDeclaredConstructors())if(c.getParameterCount()==args.length){boolean match=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!c.getParameterTypes()[i].isInstance(args[i])&&!c.getParameterTypes()[i].isPrimitive())match=false;if(match){c.setAccessible(true);return c.newInstance(args);}}throw new NoSuchMethodException(type.toString());
  }
  static void verify(Object project,ClassLoader pluginLoader,Path dir)throws Exception {
    loader=pluginLoader;nativeBridge=Class.forName("dev.boyko.aiagentswitcher.NativeRecoveryContinuation",true,loader).getField("INSTANCE").get(null);
    Path cwd=Path.of(String.valueOf(call(project,"getBasePath"))),backend=dir.resolve("air-chat-backend.js"),trace=dir.resolve("air-chat-trace.txt");Files.deleteIfExists(trace);
    Files.writeString(backend,"""
      const fs=require('fs'),rl=require('readline');let reasoning='medium',model='a';
      const options=()=>[{id:'model',name:'Model',category:'model',type:'select',currentValue:model,options:['a','b','c'].map(value=>({value,name:'Model '+value.toUpperCase()}))},{id:'reasoning',name:'Reasoning',category:'thought_level',type:'select',currentValue:reasoning,options:['low','medium','high'].map(value=>({value,name:value}))}];
      rl.createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);fs.appendFileSync(process.env.TRACE,m.method+'\\n');fs.appendFileSync(process.env.TRACE+'.sessions',JSON.stringify({method:m.method,profile:process.env.PROFILE,pid:process.pid,sessionId:m.params?.sessionId})+'\\n');const send=result=>console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result}));
      if(m.method==='initialize'){send({protocolVersion:1,agentInfo:{name:'native-air-fixture',version:'1'},agentCapabilities:{loadSession:true,sessionCapabilities:{resume:{},close:{}},providers:{},_meta:{authStatus:{}}},authMethods:[]});console.log(JSON.stringify({jsonrpc:'2.0',method:'_auth/status_update',params:{authStatus:{kind:'account',label:'Fixture account',account:{email:process.env.PROFILE.toLowerCase()+'@example.test'},vendor:{fixture:process.env.PROFILE}}}}));}
      else if(m.method==='providers/list'){send({providers:[{providerId:'fixture',supported:['openai'],required:false,current:{apiType:'openai',baseUrl:'https://fixture.example.test/v1'}}]});}
      else if(m.method==='session/new'){const result={sessionId:'fixture-'+process.env.PROFILE+'-'+require('crypto').randomUUID(),configOptions:options()};if(fs.existsSync(process.env.TRACE+'.slow'))setTimeout(()=>send(result),3000);else send(result);}
      else if(m.method==='session/set_config_option'){if(m.params.configId==='model')model=m.params.value;else reasoning=m.params.value;send({configOptions:options()});}
      else if(m.method==='session/prompt'){console.log(JSON.stringify({jsonrpc:'2.0',method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'fixture response'}}}}));send({stopReason:'end_turn'});}else send({});});
      """);
    // Register the controlled agent in Air's real catalog. A made-up AgentId
    // cannot exercise the native bound-agent cache/launch path.
    Object realCatalog=call(type("com.intellij.air.acp.api.AcpAgentsCatalog").getField("Companion").get(null),"getInstance");
    Path nativeConfig=Path.of(System.getProperty("aiaps.home"),".jetbrains","acp.json");
    Object gson=Class.forName("com.google.gson.Gson",true,loader).getConstructor().newInstance();
    Object root=call(call(Class.forName("com.google.gson.JsonParser",true,loader),"parseString",Files.readString(nativeConfig)),"getAsJsonObject");
    Object fixtureConfig=call(Class.forName("com.google.gson.JsonParser",true,loader),"parseString",call(gson,"toJson",Map.of("command","C:/Program Files/nodejs/node.exe","args",List.of(backend.toString()),"env",Map.of("PROFILE","Native","TRACE",trace.toString()))));
    call(call(root,"getAsJsonObject","agent_servers"),"add","Fixture Air",fixtureConfig);Files.writeString(nativeConfig,root.toString());
    Object fixtureId=call(type("com.intellij.air.acp.api.AcpAgentId").getField("Companion").get(null),"parse","acp.fixture-air");
    for(int i=0;i<150&&call(realCatalog,"get",fixtureId)==null;i++)Thread.sleep(100);
    ModelDefaultsProbe.check(call(realCatalog,"get",fixtureId)!=null,"Controlled native agent did not enter Air's catalog");
    Class<?> entryClass=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$Entry",true,loader),profilesClass=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles",true,loader);
    String boundAgent=Files.exists(dir.resolve("folded-agent"))?"acp.registry.codex-acp":"acp.fixture-air";
    List<Object> entries=new ArrayList<>();for(String name:List.of("Personal","Work")){
      Object p=entryClass.getConstructor().newInstance();call(p,"setId","air-"+name);call(p,"setName",name);call(p,"setAgentId",boundAgent);call(p,"setDefaultsAgentId",boundAgent);call(p,"setAdvancedOverrides",true);
      call(p,"setCommand","C:/Program Files/nodejs/node.exe");call(p,"setArguments",backend.toString());call(p,"setWorkingDirectory",cwd.toString());call(p,"setEnvironment","PROFILE="+name+"\nTRACE="+trace);entries.add(p);
      if(name.equals("Work")&&Boolean.getBoolean("aiaps.verifyNativePreferences")) {
        String preset="{\"host\":\"air\",\"agentId\":\"fixture.air\",\"defaultModelId\":\"b\",\"modelOptionId\":\"model\",\"modelOperation\":\"config\",\"models\":[{\"modelId\":\"b\",\"enabled\":true,\"settings\":[{\"agentId\":\"fixture.air\",\"id\":\"reasoning\",\"operation\":\"config\",\"value\":\"high\"}]}]}";
        Object defaults=call(gson,"fromJson",preset.replace("fixture.air",boundAgent),Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$ClientDefaults",true,loader));call(p,"setClientDefaults",new ArrayList<>(List.of(defaults)));
      }
    }
    Object profiles=call(SettingsRefreshProbe.app,"getService",profilesClass);call(profiles,"replace",entries);
    Object registration=Class.forName("dev.boyko.aiagentswitcher.GenericAutoRegistration",true,loader).getField("INSTANCE").get(null);call(registration,"assignNow",project,"air-Personal");
    Path routerHome=Path.of(System.getProperty("aiaps.home"),".jetbrains","ai-agent-project-switcher");
    Object catalogId=call(type("com.intellij.air.acp.api.AcpAgentId").getField("Companion").get(null),"parse",type("com.intellij.air.acp.api.AcpAgentId").getField("CUSTOM_PREFIX").get(null)+"ai-multi");
    Class<?> configType=type("com.intellij.air.acp.api.AcpAgentStartConfig");
    Object start=call(configType.getField("Companion").get(null),"create","C:/Program Files/nodejs/node.exe",List.of(routerHome.resolve("router.js").toString(),routerHome.resolve("routes.json").toString()),List.of(),Map.of(),cwd);
    Class<?> catalogType=type("com.intellij.air.acp.api.AcpCatalogEntry");
    Object entry=Proxy.newProxyInstance(catalogType.getClassLoader(),new Class<?>[]{catalogType},(proxy,m,a)->switch(m.getName()){
      case "getId"->catalogId;case "getDisplayName"->"AI Multi";case "resolveStartConfig"->start;case "getCatalogGenerationFingerprint"->"fixture";case "toString"->"AI Multi fixture";case "hashCode"->123;case "equals"->proxy==a[0];default->null;});
    Object surface=call(type("com.intellij.air.shared.core.thread.AgentThreadInteractionSurfaces").getField("INSTANCE").get(null),"getNATIVE-");
    Object launchTarget=call(type("com.intellij.air.shared.core.thread.AgentLaunchTargetIdKt"),"acpAgentLaunchTargetId",call(catalogId,"getFullId"));
    Object route=construct(type("com.intellij.air.shared.session.AgentLaunchRoute"),call(catalogId,"getFullId"),launchTarget,surface,null);
    Object launchMode=type("com.intellij.air.shared.core.thread.AgentThreadLaunchMode").getField("DEFAULT").get(null);
    Object facts=construct(type("com.intellij.air.acp.AcpLaunchFacts"),route,entry,cwd,false,launchMode,List.of(),null,true);
    Object input=construct(type("com.intellij.air.acp.AcpPrepareInput$NewSession"),facts);
    Object ref=construct(type("com.intellij.air.backend.session.api.SessionRef"),cwd.toString().replace('\\','/'),UUID.randomUUID().toString(),null);
    Object manager=call(type("com.intellij.air.acp.AcpSessionManager").getField("Companion").get(null),"getInstance");
    Object prepared=suspend(manager,"prepare",ref,input);ModelDefaultsProbe.check(Boolean.TRUE.equals(prepared),"Native Air preparation failed");
    Object runtime=call(field(manager,"runtimeStore"),"get",ref);ModelDefaultsProbe.check(runtime!=null,"No native Air runtime");
    String thread=String.valueOf(call(runtime,"getThreadId-"));
    Object projection=call(field(runtime,"projectionHandle"),"getProjection"),runtimeBinding=call(projection,"getRuntimeBinding");
    String sid=runtimeBinding==null?"":String.valueOf(call(runtimeBinding,"getAgentSessionId"));
    Class<?> contextType=type("com.intellij.air.thread.view.shared.AgentThreadViewEditorTabActionContext");
    Object tab=construct(contextType,project,construct(type("com.intellij.air.shared.core.identity.SessionWorkspaceId"),"fixture-workspace"),cwd.toString(),"fixture-tab","fixture-identity",thread,"Fixture",type("com.intellij.air.shared.core.AgentSessionActivity").getField("READY").get(null),null,null);
    Object contextKey=type("com.intellij.air.frontend.session.view.api.AgentSessionViewTabContextKt").getField("AGENT_SESSION_VIEW_TAB_CONTEXT").get(null);
    String keyName=String.valueOf(call(contextKey,"getName"));
    Class<?> dataContext=SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.DataContext");
    Object context=Proxy.newProxyInstance(dataContext.getClassLoader(),new Class<?>[]{dataContext},(proxy,m,a)->m.getName().equals("getData")?((keyName.equals(a[0])||a[0]==contextKey||keyName.equals(String.valueOf(a[0])))?tab:"project".equals(String.valueOf(a[0]))?project:null):null);
    Object bridge=Class.forName("dev.boyko.aiagentswitcher.AirProfileBridge",true,loader).getField("INSTANCE").get(null),binding=call(bridge,"binding",context);
    ModelDefaultsProbe.check(binding!=null,"Air dropdown failed to bind real native session");
    Object actionManager=call(SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.ActionManager"),"getInstance"),action=call(actionManager,"getAction","dev.boyko.aiagentswitcher.AirProfileSelector");ModelDefaultsProbe.check(action!=null,"Air dropdown not registered");
    Object group=call(actionManager,"getAction","Air.Prompt.Preferences");Class<?> actionEvent=SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.AnActionEvent");Object groupEvent=call(actionEvent,"createFromAnAction",action,null,"Air.Prompt.Preferences",context);Object[] children=(Object[])group.getClass().getMethod("getChildren",actionEvent).invoke(group,groupEvent);ModelDefaultsProbe.check(Arrays.asList(children).contains(action),"Air bottom toolbar omits dropdown");
    Object upper=call(actionManager,"getAction","Air.Prompt.Launch");Object[] upperChildren=(Object[])upper.getClass().getMethod("getChildren",actionEvent).invoke(upper,groupEvent);ModelDefaultsProbe.check(!Arrays.asList(upperChildren).contains(action),"Air upper toolbar still contains duplicate profile dropdown");
    if(!Boolean.getBoolean("aiaps.verifyNativePreferences")) {
      Method popup=action.getClass().getDeclaredMethod("createPopupActionGroup",JComponent.class,dataContext);popup.setAccessible(true);
      final Object[] menu={null};SettingsRefreshProbe.edt(()->menu[0]=popup.invoke(action,new JPanel(),context));
      Object[] choices=(Object[])menu[0].getClass().getMethod("getChildren",actionEvent).invoke(menu[0],(Object)null);ModelDefaultsProbe.check(choices.length>=4,"Profile menu missing choices");
    }
    if(Boolean.getBoolean("aiaps.verifyNativePreferences")) {
      Object request=construct(type("com.intellij.air.acp.AcpConnectionRequest"),entry,cwd,false,true,null,false);
      Object connection=suspend(field(manager,"connectionExecutor"),"connect",runtime,request);
      Files.writeString(dir.resolve("air-native-connection-result.txt"),String.valueOf(connection));
    }
    binding=suspend(bridge,"connect",binding,false);
    awaitReady(bridge,binding);
    verifyAccount(binding,"personal@example.test");
    if(Boolean.getBoolean("aiaps.verifyNativePreferences")) {
      Object owners=field(manager,"owners"),owner=call(owners,"get",ref),snapshot=call(field(owner,"connectionSnapshotState"),"getValue");
      Object admitted=suspend(owners,"admitCurrentProtocolSession-",ref,call(snapshot,"getGeneration-"));
      Object protocol=admitted.getClass().getField("session").get(admitted),resources=field(protocol,"resources"),sdk=call(resources,"getClientSession");
      ClientPreferencesProbe.liveModelFilters("air","air-Personal",()->call(call(sdk,"getConfigOptions"),"getValue"),trace);
    }
    Object result=suspend(bridge,"control",binding,"_ai_multi/describe",Class.forName("com.google.gson.JsonObject",true,loader).getConstructor().newInstance());ModelDefaultsProbe.check(result.toString().contains("air-Personal"),"Native Air raw control wrong chat");
    if(Boolean.getBoolean("aiaps.verifyNativePreferences")) {
      Object record=call(bridge,"record",binding);ModelDefaultsProbe.check(record.toString().contains("\"currentValue\":\"high\""),"Air did not inherit native profile preferences: "+record);
      Object params=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,"{\"configId\":\"reasoning\",\"value\":\"low\"}");
      suspend(bridge,"control",binding,"session/set_config_option",params);
    }
    suspend(bridge,"choose",project,binding,"air-Work");
    result=suspend(bridge,"control",binding,"_ai_multi/describe",Class.forName("com.google.gson.JsonObject",true,loader).getConstructor().newInstance());ModelDefaultsProbe.check(result.toString().contains("air-Work"),"Air dropdown selection did not switch chat");
    verifyAccount(binding,"work@example.test");
    if(Boolean.getBoolean("aiaps.verifyNativePreferences")) {Object record=call(bridge,"record",binding);ModelDefaultsProbe.check(record.toString().contains("\"currentValue\":\"b\"")&&record.toString().contains("\"currentValue\":\"high\""),"Configured Air defaults lost to remembered preferences: "+record);}
    Object selection=call(project,"getService",Class.forName("dev.boyko.aiagentswitcher.ProjectSelection",true,loader));ModelDefaultsProbe.check(call(selection,"getSelected").equals("air-Personal"),"Air selection changed project Default Profile");
    ModelDefaultsProbe.check(!Files.readString(trace).contains("session/prompt"),"Empty chat switch sent a prompt");
    if(Files.exists(dir.resolve("verify-visible"))) {
      visibleChat(project,ref,thread,cwd,dir,bridge);
      ModelDefaultsProbe.check(call(bridge,"record",binding).toString().contains("\"profile\":\"air-Work\""),"New Air chat changed the previous chat's profile");
      ModelDefaultsProbe.check(Files.readAllLines(trace).stream().filter(x->x.equals("session/prompt")).count()==1,"Unexpected provider prompt during profile selection");
    }
    Files.writeString(dir.resolve("air-result.txt"),Boolean.getBoolean("aiaps.verifyNativePreferences")?"PASS: actual Air admission, inherited native reasoning, accepted explicit selection, profile model/reasoning defaults override conflicting native preferences, switch with unchanged project default and no provider prompt.\n":"PASS: actual Air prepare before first prompt, native session admission and raw extension; registered dropdown in native Preferences toolbar; Personal/Work menu, switching the bound empty chat, unchanged project default, no provider prompt.\n");
    suspend(manager,"closeAllRuntimes");
  }
  static void visibleChat(Object project,Object ref,String thread,Path cwd,Path dir,Object bridge)throws Exception {
    Object editors=call(SettingsRefreshProbe.type("com.intellij.openapi.fileEditor.FileEditorManager"),"getInstance",project);
    Object[] files=(Object[])call(editors,"getOpenFiles");
    for(Object file:files)if(String.valueOf(call(file,"getName")).contains("New Session"))SettingsRefreshProbe.edt(()->call(editors,"openFile",file,true));
    Thread.sleep(2000);
    StringBuilder launchReport=new StringBuilder();
    SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,launchReport,bridge);});
    final boolean[] visible={false};SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing()&&containsProfileButton(w))visible[0]=true;});
    ModelDefaultsProbe.check(!visible[0],"Profile selector leaks into the native Junie composer");
    selectNativeAgent("AI Multi");
    Thread.sleep(2000);
    StringBuilder selectedContext=new StringBuilder();
    SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,selectedContext,bridge);});
    Files.writeString(dir.resolve("selected-agent-context.txt"),selectedContext);
    SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing()&&containsProfileButton(w))visible[0]=true;});
    ModelDefaultsProbe.check(visible[0],"AI Multi composer omits profile selector");
    selectNativeAgent("Fixture Air");
    for(int i=0;i<150;i++) {
      visible[0]=false;
      SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing()){inspect(w,new StringBuilder(),bridge);if(containsProfileButton(w))visible[0]=true;}});
      if(!visible[0])break;
      Thread.sleep(100);
    }
    ModelDefaultsProbe.check(!visible[0],"Profile selector remains visible after switching to Codex");
    selectNativeAgent("AI Multi");
    Thread.sleep(2000);
    SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,new StringBuilder(),bridge);});
    Files.writeString(dir.resolve("new-session-contexts.txt"),launchReport);
    Object catalog=call(type("com.intellij.air.acp.api.AcpAgentsCatalog").getField("Companion").get(null),"getInstance");
    Object configLoader=type("com.intellij.air.acp.runtime.config.AcpConfigurationLoader").getField("INSTANCE").get(null);
    Object configPath=call(configLoader,"getConfigFilePath");
    Object configService=call(SettingsRefreshProbe.app,"getService",type("com.intellij.air.acp.runtime.config.AcpAgentsConfigService"));
    Files.writeString(dir.resolve("catalog-diagnostic.txt"),"path="+configPath+"\nloaded="+call(configLoader,"loadFromPath",configPath)+"\nservice="+call(call(configService,"getLocalAgentConfigsFlow"),"getValue")+"\ncatalog="+call(call(catalog,"getAgentsFlow"),"getValue"));
    captureAir(dir.resolve("new-session.png"));
    ModelDefaultsProbe.check(launchContext!=null,"No actual New Session toolbar context");
    verifyVisibleAccount("personal@example.test");
    Object actions=call(SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.ActionManager"),"getInstance");
    Object selector=call(actions,"getAction","dev.boyko.aiagentswitcher.AirProfileSelector");
    Class<?> dataContext=SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.DataContext"),eventType=SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.AnActionEvent");
    Object composerProfiles=Class.forName("dev.boyko.aiagentswitcher.air.AirComposerProfiles",true,loader).getField("INSTANCE").get(null);
    SettingsRefreshProbe.edt(()->{
      Object composer=call(composerProfiles,"composer",launchContext);
      ModelDefaultsProbe.check(composer!=null,"Native New Session profile binding is unavailable");
      ModelDefaultsProbe.check(call(composerProfiles,"selected",project,composer).equals("air-Personal"),"Initial launch selection ignores project Default Profile");
      Object snapshot=call(call(call(composer,"getDraft"),"getState"),"getValue");
      Files.writeString(dir.resolve("composer-before.txt"),String.valueOf(snapshot));
      if(Files.exists(dir.resolve("folded-agent"))) {
        Object profile=call(snapshot,"getProfile");
        ModelDefaultsProbe.check(call(profile,"getAgentId-").equals("codex"),"Folded Codex launch uses an absent dynamic catalog ID");
        Object target=call(type("com.intellij.air.shared.core.thread.AgentLaunchTargetIdKt"),"acpAgentLaunchTargetId","acp.registry.codex-acp");
        ModelDefaultsProbe.check(call(profile,"getLaunchTargetId-").equals(target),"Folded catalog identity changed the real ACP launch target");
      }
    });
    final java.awt.Component[] profileButton={null};
    SettingsRefreshProbe.edt(()->{
      for(java.awt.Window window:java.awt.Window.getWindows())if(window.isShowing())profileButton[0]=findProfileButton(window,profileButton[0]);
      ModelDefaultsProbe.check(profileButton[0]!=null,"No clickable native profile button");
      var input=new java.awt.event.MouseEvent(profileButton[0],java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,2,2,1,false);
      Object event=call(eventType,"createFromAnAction",selector,input,"Air.Prompt.Preferences",launchContext);
      call(selector,"actionPerformed",event);
    });
    Thread.sleep(300);
    ModelDefaultsProbe.check(Boolean.TRUE.equals(call(call(SettingsRefreshProbe.type("com.intellij.openapi.ui.popup.JBPopupFactory"),"getInstance"),"isPopupActive")),"Native profile button did not open its popup");
    var robot=new java.awt.Robot();robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);Thread.sleep(300);
    Method popup=selector.getClass().getDeclaredMethod("createPopupActionGroup",JComponent.class,dataContext);popup.setAccessible(true);
    int originalProfileY=profileButton[0].getLocationOnScreen().y;
    Files.writeString(dir.resolve("air-chat-trace.txt.slow"),"Delay the controlled backend to inspect switching feedback");
    SettingsRefreshProbe.edt(()->{
      Object menu=popup.invoke(selector,new JPanel(),launchContext);
      Object[] choices=(Object[])menu.getClass().getMethod("getChildren",eventType).invoke(menu,(Object)null);
      Object selected=null;for(Object choice:choices)if(String.valueOf(call(call(choice,"getTemplatePresentation"),"getText")).equals("Work"))selected=choice;
      ModelDefaultsProbe.check(selected!=null,"Actual New Session menu omits profile");
      Object event=call(eventType,"createFromAnAction",selected,null,"Air.Prompt.Preferences",launchContext);call(selected,"actionPerformed",event);
    });
    Thread.sleep(700);
    captureAir(dir.resolve("switching-profile.png"));
    SettingsRefreshProbe.edt(()->{
      StringBuilder after=new StringBuilder();for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,after,bridge);
      Files.writeString(dir.resolve("composer-after.txt"),after);
      Object event=call(eventType,"createFromAnAction",selector,null,"Air.Prompt.Preferences",launchContext);
      call(selector,"update",event);Object presentation=call(event,"getPresentation");
      ModelDefaultsProbe.check(String.valueOf(call(presentation,"getText")).equals("Work"),"Profile selection waits for a live session: "+call(presentation,"getText")+" / "+call(presentation,"getDescription"));
      ModelDefaultsProbe.check(Boolean.TRUE.equals(call(presentation,"isEnabled")),"Profile selection is disabled by native agent preparation");
      ModelDefaultsProbe.check(!call(presentation,"getIcon").getClass().getName().contains("AnimatedIcon"),"Profile selection still owns native session loading");
    });
    captureAir(dir.resolve("switching-profile.png"));
    Files.delete(dir.resolve("air-chat-trace.txt.slow"));
    for(int i=0;i<150;i++){
      Thread.sleep(100);showingBinding=null;SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,new StringBuilder(),bridge);});
      if(hasProfile(bridge,showingBinding,"air-Work"))break;
    }
    ModelDefaultsProbe.check(Arrays.equals(files,(Object[])call(editors,"getOpenFiles")),"Profile selection opened or replaced a native editor tab");
    Object created=showingBinding;ModelDefaultsProbe.check(created!=null,"Profile selector did not bind Air prepared session");
    awaitReady(bridge,created);
    verifyAccount(created,"work@example.test");
    ModelDefaultsProbe.check(((Number)call(call(call(bridge,"record",created),"getAsJsonArray","segments"),"size")).intValue()==1,"Native launch started another agent before switching profiles");
    for(int i=0;i<100&&call(bridge,"switching",created)!=null;i++)Thread.sleep(50);
    ModelDefaultsProbe.check(call(bridge,"switching",created)==null,"Profile loading state did not clear after completion");
    Object createdRuntime=call(created,"getRuntime");
    Object createdProjection=call(field(createdRuntime,"projectionHandle"),"getProjection");
    Files.writeString(dir.resolve("created-projection.txt"),createdProjection.toString());
    ModelDefaultsProbe.check(String.valueOf(call(call(createdProjection,"getThread"),"getStatus")).equals("WaitingForUser"),"Connected profile chat is stuck in Preparing");
    ModelDefaultsProbe.check(((Map<?,?>)call(createdProjection,"getConfigOptions")).containsKey("model"),"Connected profile chat lost agent model options");
    Thread.sleep(1500);
    captureAir(dir.resolve("before-bottom.png"));
    final boolean[] bottomProfile={false};
    SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())verifyBottomProfile(w,bridge,bottomProfile);});
    SettingsRefreshProbe.edt(()->{
      java.awt.Component current=null;for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())current=findProfileButton(w,current);
      ModelDefaultsProbe.check(current!=null&&Math.abs(current.getLocationOnScreen().y-originalProfileY)<=2,"Profile switch moved the native composer");
    });
    captureAir(dir.resolve("bottom-profile.png"));
    ModelDefaultsProbe.check(bottomProfile[0],"Acquired chat omits visible profile selector beside the agent selector");
    boolean[] nativeModel={false};
    SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing()&&containsText(w,"Model B"))nativeModel[0]=true;});
    ModelDefaultsProbe.check(nativeModel[0],"Acquired chat retains settings skeleton instead of native model selector");
    verifyVisibleAccount("work@example.test");
    ModelDefaultsProbe.check(call(bridge,"record",created).toString().contains("\"profile\":\"air-Work\""),"Air creator ignored explicit profile");
    Files.writeString(dir.resolve("created-binding.txt"),created.toString());
    chooseComposerProfile(selector,popup,eventType,dataContext,"Personal");
    for(int i=0;i<150;i++){
      Thread.sleep(100);showingBinding=null;SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,new StringBuilder(),bridge);});
      if(hasProfile(bridge,showingBinding,"air-Personal"))break;
    }
    ModelDefaultsProbe.check(showingBinding!=null&&call(bridge,"record",showingBinding).toString().contains("\"profile\":\"air-Personal\""),"New Air composer cannot select another launch profile");
    ModelDefaultsProbe.check(call(call(project,"getService",Class.forName("dev.boyko.aiagentswitcher.ProjectSelection",true,loader)),"getSelected").equals("air-Personal"),"New Air chat changed project default");
    Thread.sleep(3000);
    verifyAccount(showingBinding,"personal@example.test");
    verifyVisibleAccount("personal@example.test");
    captureAir(dir.resolve("created.png"));
    ModelDefaultsProbe.check(Arrays.asList((Object[])call(editors,"getOpenFiles")).contains(files[0]),"Profile choice closed the original draft tab");
    String personalPreparedId=String.valueOf(call(showingBinding,"getSessionId"));
    String unusedNativeId=String.valueOf(call(call(call(call(call(bridge,"record",created),"getAsJsonArray","segments"),"get",0),"getAsJsonObject"),"get","sessionId"));
    long closesBefore=Files.readAllLines(dir.resolve("air-chat-trace.txt.sessions")).stream().filter(x->x.contains("session/close")&&x.contains("\"sessionId\":"+unusedNativeId)).count();
    long preparedCount=Files.readAllLines(dir.resolve("air-chat-trace.txt")).stream().filter(x->x.equals("session/new")).count();
    chooseComposerProfile(selector,popup,eventType,dataContext,"Work");
    Object reusedWork=awaitComposerProfile(bridge,"air-Work");
    ModelDefaultsProbe.check(call(reusedWork,"getSessionId").equals(call(created,"getSessionId")),"Air recreated the previously prepared Work session");
    chooseComposerProfile(selector,popup,eventType,dataContext,"Personal");
    Object reusedPersonal=awaitComposerProfile(bridge,"air-Personal");
    ModelDefaultsProbe.check(call(reusedPersonal,"getSessionId").equals(personalPreparedId),"Air recreated the previously prepared Personal session");
    ModelDefaultsProbe.check(Files.readAllLines(dir.resolve("air-chat-trace.txt")).stream().filter(x->x.equals("session/new")).count()==preparedCount,"Air sent session/new on return to a prepared profile");
    // Send through Air's actual native preferences source, so native task
    // admission (and the lease handover) is exercised as in the composer.
    final Object[] source={null};SettingsRefreshProbe.edt(()->{
      Object composer=call(composerProfiles,"composer",launchContext);
      Object host=call(call(composer,"getPreferences"),"getPreferences");
      Object ready=call(call(host,"getPreferencesState"),"getValue");
      source[0]=field(call(ready,"getViewModel"),"source");
    });
    Object firstRequest=construct(type("com.intellij.air.shared.session.view.gui.ThreadGuiSendPromptRequest"),"First prepared prompt","First prepared prompt",UUID.randomUUID().toString(),null,List.of(),List.of(),List.of(),List.of(),List.of());
    Object admitted=suspend(source[0],"sendPrompt",firstRequest);
    ModelDefaultsProbe.check(Boolean.TRUE.equals(call(admitted,"getAccepted")),"Air refused the first native prompt");
    for(int i=0;i<150;i++){if(Files.readAllLines(dir.resolve("air-chat-trace.txt.sessions")).stream().filter(x->x.contains("session/close")&&x.contains("\"sessionId\":"+unusedNativeId)).count()>closesBefore)break;Thread.sleep(100);}
    ModelDefaultsProbe.check(Files.readAllLines(dir.resolve("air-chat-trace.txt.sessions")).stream().filter(x->x.contains("session/close")&&x.contains("\"sessionId\":"+unusedNativeId)).count()>closesBefore,"Air did not close unused Work after first prompt");
    Files.writeString(dir.resolve("air-prepared-result.txt"),"PASS: native composer preserved, same Personal/Work session IDs reused, no repeated session/new, native first prompt admitted, unused prepared Work session closed.\n");
    Files.writeString(dir.resolve("visible-result.txt"),"PASS: project default initializes composer selection; profile choice completes while native agent preparation is delayed and remains enabled; AI Multi agent/route IDs are preserved; native launches use the selected profile with exactly one segment, native model/default controls, unchanged tabs/composer and project default, no provider prompt, independent acquired chat.\n");
    if(Files.exists(dir.resolve("cache-audit"))) auditNativeCache(project,dir,bridge,selector,eventType);
    String workspace=cwd.toString().replace('\\','/');
    Object launchTarget=call(type("com.intellij.air.shared.core.thread.AgentLaunchTargetIdKt"),"acpAgentLaunchTargetId","acp.ai-multi");
    Object launch=construct(type("com.intellij.air.shared.prompt.AgentPromptLaunchProfile"),"AI Multi","acp.ai-multi",type("com.intellij.air.shared.core.thread.AgentThreadLaunchMode").getField("DEFAULT").get(null),launchTarget,"native",null,type("com.intellij.air.shared.prompt.AgentPromptGenerationPreferences").getConstructor().newInstance(),false,null,null,"",null);
    Object spec=construct(type("com.intellij.air.thread.view.AgentSessionViewOpenSpec"),
      construct(type("com.intellij.air.shared.session.AgentThreadViewSurfaceKey"),"air-visible-fixture"),
      construct(type("com.intellij.air.shared.core.identity.SessionWorkspaceId"),workspace),workspace,"acp.ai-multi:"+thread,launchTarget,thread,"Air profile verification",null,
      type("com.intellij.air.shared.core.AgentSessionActivity").getField("READY").get(null),null,null,null,launch,null,null,null,call(ref,"getLocalIdentity"),false,null,null,null);
    suspend(type("com.intellij.air.thread.view.AgentThreadViewEditorServiceKt"),"openThreadView",project,spec,type("com.intellij.air.shared.session.AgentThreadViewSurfacePresentationCommand").getField("OPEN_AND_FOCUS").get(null),false);
    Thread.sleep(5000);
    StringBuilder report=new StringBuilder();
    SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,report,bridge);});
    Files.writeString(dir.resolve("visible-contexts.txt"),report);
    java.awt.Rectangle bounds=new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
    captureAir(dir.resolve("visible.png"));
    Object manager=call(type("com.intellij.air.acp.AcpSessionManager").getField("Companion").get(null),"getInstance");
    StringBuilder runtimes=new StringBuilder("originalRef="+ref+" originalIdentity="+call(manager,"agentAuthIdentity",ref)+"\n");
    Object runtimeStore=field(manager,"runtimeStore");
    for(Object candidate:(List<?>)call(runtimeStore,"refsSnapshot")) {
      Object runtime=call(runtimeStore,"get",candidate);
      runtimes.append("ref=").append(candidate).append(" thread=").append(call(runtime,"getThreadId-")).append(" identity=").append(call(manager,"agentAuthIdentity",candidate)).append('\n');
    }
    Files.writeString(dir.resolve("account-runtimes.txt"),runtimes);
    diagnoseAcquiredAccount(manager,ref,dir);
    verifyVisibleAccount("work@example.test");
    Files.writeString(dir.resolve("account-result.txt"),"PASS: real Air account identity from agent's one-time post-initialize notification; native account control shows the selected composer account and retains the acquired chat's account independently of project default.\n");
  }
  static void diagnoseAcquiredAccount(Object manager,Object ref,Path dir)throws Exception {
    StringBuilder report=new StringBuilder();
    try {
      Object runtime=call(field(manager,"runtimeStore"),"get",ref);
      Object projection=call(field(runtime,"projectionHandle"),"getProjection"),binding=call(projection,"getRuntimeBinding");
      Object target=call(binding,"getLaunchTargetId-");
      Object adapters=type("com.intellij.air.backend.session.api.AgentSessionRuntimeAdapters").getField("INSTANCE").get(null);
      Object adapter=call(adapters,"resolvedOrNull-",target);
      report.append("runtimeBinding=").append(binding).append("\nadapter=").append(adapter).append('\n');
      report.append("adapterIdentity=").append(adapter==null?null:call(adapter,"agentAuthIdentity",ref)).append('\n');
      Object presentationFlow=call(manager,"presentationState",ref),presentation=presentationFlow==null?null:call(presentationFlow,"getValue");
      Object mapper=construct(type("com.intellij.air.backend.session.view.gui.mapping.SessionProjectionEventMapper"),ref);
      Object snapshot=call(mapper,"snapshot",projection,call(projection,"runtimeState"),presentation);
      Object metadata=call(call(snapshot,"getProjection"),"getMetadata");
      report.append("freshMappedMetadata=").append(metadata).append('\n');
    } catch(Throwable failure) {report.append("backendDiagnosticFailure=").append(failure).append('\n');}
    SettingsRefreshProbe.edt(()->{
      try {
        Object keys=type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys").getField("INSTANCE").get(null);
        Map<?,?> selectors=(Map<?,?>)call(call(keys,"getSELECTORS"),"getData",launchContext);
        for(var selected:selectors.entrySet()) {
          Object commands=field(call(selected.getValue(),"getState"),"commandService");
          if(commands==null)continue;
          Object lane=field(commands,"preferences"),host=call(lane,"getPreferences");
          Object preferences=call(call(host,"getPreferencesState"),"getValue");
          report.append("selector=").append(selected.getKey()).append("\npreferences=").append(preferences).append('\n');
          Object model=call(preferences,"getViewModel");
          report.append("viewModelIdentity=").append(call(call(model,"getAgentAuthIdentityState"),"getValue")).append('\n');
          Object source=field(model,"source");
          report.append("source=").append(source).append("\nsourceMetadata=").append(call(call(source,"getMetadataState"),"getValue")).append('\n');
          Object aiSource=call(lane,"getAiSource");
          report.append("aiSourceIdentity=").append(call(field(aiSource,"agentAuthIdentityState"),"getValue")).append("\naiSourceState=").append(call(call(aiSource,"getAiSourceState"),"getValue")).append('\n');
          break;
        }
      } catch(Throwable failure) {report.append("uiDiagnosticFailure=").append(failure).append('\n');}
    });
    Files.writeString(dir.resolve("account-metadata-diagnostic.txt"),report);
  }
  static boolean hasProfile(Object bridge,Object binding,String profile) {
    if(binding==null)return false;
    try{return call(bridge,"record",binding).toString().contains("\"profile\":\""+profile+"\"");}catch(Exception unavailable){return false;}
  }
  static void verifyAccount(Object binding,String email)throws Exception {
    Object manager=call(binding,"getManager"),ref=call(binding,"getRef");
    Object identity=call(manager,"agentAuthIdentity",ref);
    ModelDefaultsProbe.check(identity!=null&&email.equals(identity.getClass().getField("email").get(identity)),"Native Air account identity missing or belongs to another profile: "+identity+"; expected "+email);
  }
  static void verifyVisibleAccount(String email)throws Exception {
    final boolean[] found={false};StringBuilder report=new StringBuilder();
    for(int i=0;i<150&&!found[0];i++){
      report.setLength(0);SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspectAccount(w,email,found,report);});
      if(!found[0])Thread.sleep(100);
    }
    Files.writeString(ClientPreferencesProbe.dir.resolve("account-presentation.txt"),report,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    ModelDefaultsProbe.check(found[0],"Native account control/tooltip does not expose "+email+": "+report);
  }
  static Object awaitComposerProfile(Object bridge,String profile)throws Exception {
    for(int i=0;i<150;i++){
      Thread.sleep(100);showingBinding=null;SettingsRefreshProbe.edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w.isShowing())inspect(w,new StringBuilder(),bridge);});
      if(hasProfile(bridge,showingBinding,profile)){awaitReady(bridge,showingBinding);return showingBinding;}
    }
    throw new AssertionError("Native composer did not bind "+profile);
  }
  static void inspectAccount(java.awt.Component c,String email,boolean[] found,StringBuilder report)throws Exception {
    if(c.isShowing()&&SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.impl.ActionButton").isInstance(c)) {
      Object action=call(c,"getAction");
      String actionName=action.getClass().getName();
      if(actionName.endsWith("AirPromptAiSourceAction")||actionName.endsWith("AirAccountAction")) {
        Object p=call(c,"getPresentation");String painted=String.valueOf(call(c,"getText"));
        String text="painted="+painted+" / presentation="+call(p,"getText")+" / "+call(p,"getDescription");
        Object keys=type("com.intellij.air.frontend.prompt.composer.actions.AirPromptActionPresentationKeys").getField("INSTANCE").get(null);
        Object tooltip=call(p,"getClientProperty",call(keys,"getTOOLTIP"));
        // A tooltip alone does not meet the inline account-caption contract.
        text+=" / native tooltip: "+(tooltip==null?null:call(tooltip,"getTitle"));
        report.append(text).append(" action=").append(actionName).append(" bounds=").append(c.getBounds()).append('\n');
        String prefix=painted.replace("…","");
        if(String.valueOf(call(p,"getText")).contains(email)&&!prefix.isBlank()&&email.startsWith(prefix))found[0]=true;
      }
    }
    if(c instanceof java.awt.Container parent)for(java.awt.Component child:parent.getComponents())inspectAccount(child,email,found,report);
  }
  static void inspect(java.awt.Component c,StringBuilder report,Object bridge)throws Exception {
    if(c.getClass().getName().contains("AirPromptSelectorToolbar")) {
      Object context=call(SettingsRefreshProbe.type("com.intellij.ide.DataManager"),"getInstance");context=call(context,"getDataContext",c);
      if(c.isShowing()&&String.valueOf(call(c,"getPlace")).equals("Air.Prompt.Preferences")){launchContext=context;if(containsProfileButton(c))verifyProfileOrder((java.awt.Container)c);}
      Object bound=call(bridge,"binding",context);if(c.isShowing()&&bound!=null)showingBinding=bound;
      report.append(c.getClass().getName()).append(" showing=").append(c.isShowing()).append(" binding=").append(call(bridge,"binding",context)).append('\n');
      Object key=type("com.intellij.air.frontend.session.view.api.AgentSessionViewTabContextKt").getField("AGENT_SESSION_VIEW_TAB_CONTEXT").get(null);
      report.append("tab=").append(call(key,"getData",context)).append('\n');
      Object keys=type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys").getField("INSTANCE").get(null);
      report.append("selectors=").append(call(call(keys,"getSELECTORS"),"getData",context)).append('\n');
    }
    if(c instanceof java.awt.Container container)for(java.awt.Component child:container.getComponents())inspect(child,report,bridge);
  }
  static boolean containsText(java.awt.Component c,String text) {
    if(c.isShowing()&&c instanceof javax.swing.AbstractButton b&&b.getText()!=null&&b.getText().contains(text))return true;
    if(c.isShowing())try{if(SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.impl.ActionButton").isInstance(c)&&String.valueOf(call(call(c,"getPresentation"),"getText")).contains(text))return true;}catch(Exception e){throw new RuntimeException(e);}
    if(c instanceof java.awt.Container parent)for(java.awt.Component child:parent.getComponents())if(containsText(child,text))return true;
    return false;
  }
  static boolean containsProfileButton(java.awt.Component c) {
    if(c.isShowing()&&c.getClass().getName().equals("dev.boyko.aiagentswitcher.air.AirProfileButton"))return true;
    if(c instanceof java.awt.Container parent)for(java.awt.Component child:parent.getComponents())if(containsProfileButton(child))return true;
    return false;
  }
  static java.awt.Component findProfileButton(java.awt.Component c,java.awt.Component previous) {
    if(c.isShowing()&&c.getClass().getName().equals("dev.boyko.aiagentswitcher.air.AirProfileButton"))return c;
    if(c instanceof java.awt.Container parent)for(java.awt.Component child:parent.getComponents())previous=findProfileButton(child,previous);
    return previous;
  }
  static void verifyBottomProfile(java.awt.Component c,Object bridge,boolean[] found)throws Exception {
    if(c.isShowing()&&c.getClass().getName().contains("AirPromptSelectorToolbar")) {
      Object context=call(call(SettingsRefreshProbe.type("com.intellij.ide.DataManager"),"getInstance"),"getDataContext",c);
      if(call(bridge,"binding",context)!=null) {
        boolean profile=containsProfileButton(c);
        String place=String.valueOf(call(c,"getPlace"));
        if(place.equals("Air.Prompt.Preferences")&&profile){found[0]=true;verifyProfileOrder((java.awt.Container)c);}
        if(place.equals("Air.Prompt.Launch"))ModelDefaultsProbe.check(!profile,"Profile selector is still in the upper launch lane");
      }
    }
    if(c instanceof java.awt.Container parent)for(java.awt.Component child:parent.getComponents())verifyBottomProfile(child,bridge,found);
  }
  static void verifyProfileOrder(java.awt.Container toolbar)throws Exception {
    java.awt.Component agent=null,profile=null;Object previous=null,first=null; StringBuilder diagnostic=new StringBuilder();
    Object manager=call(SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.ActionManager"),"getInstance");
    for(java.awt.Component child:toolbar.getComponents())if(child.isShowing()){
      if(!SettingsRefreshProbe.type("com.intellij.openapi.actionSystem.impl.ActionButton").isInstance(child))continue;
      Object action=call(child,"getAction");
      if(first==null)first=action;
      diagnostic.append(child.getClass().getName()).append(" action=").append(action).append(" type=").append(action.getClass().getName()).append(" bounds=").append(child.getBounds()).append("\n");Files.writeString(ClientPreferencesProbe.dir.resolve("toolbar-actions.txt"),diagnostic);
      if(child.getClass().getName().equals("dev.boyko.aiagentswitcher.air.AirProfileButton")){
        profile=child;if(agent!=null)ModelDefaultsProbe.check(previous==call(manager,"getAction",call(Enum.valueOf((Class)type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorId"),"Agent"),"getActionId")),"Profile is not immediately after the agent selector");
      }
      if(action==call(manager,"getAction",call(Enum.valueOf((Class)type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorId"),"Agent"),"getActionId")))agent=child;
      previous=action;
    }
    ModelDefaultsProbe.check(profile!=null,"Missing profile button");
    if(agent==null)ModelDefaultsProbe.check(first==call(manager,"getAction","dev.boyko.aiagentswitcher.AirProfileSelector"),"Acquired-chat profile follows native model/settings controls");
    if(agent!=null)ModelDefaultsProbe.check(profile.getX()>=agent.getX()+agent.getWidth(),"Profile geometry does not follow agent");
    Object service=call(SettingsRefreshProbe.app,"getService",type("com.intellij.air.frontend.launch.AgentLaunchTargetPresentationService"));
    Object target=call(type("com.intellij.air.shared.core.thread.AgentLaunchTargetIdKt"),"acpAgentLaunchTargetId","acp.ai-multi");
    Object icon=call(service,"resolveIcon",target,null,false,null);
    Object expected=Class.forName("dev.boyko.aiagentswitcher.MultiIcons",true,loader).getField("Agent").get(null);
    ModelDefaultsProbe.check(icon==expected,"Air resolver does not use the AI Multi icon");
    Object rowIcons=type("com.intellij.air.frontend.session.toolwindow.ui.AgentThreadRowIconProvider").getConstructor().newInstance();
    Object ready=type("com.intellij.air.shared.core.AgentSessionActivity").getField("READY").get(null);
    for(String id:List.of("acp.dynamic.6163702e61692d6d756c7469","acp.ai-multi")) {
      Object row=call(rowIcons,"icon-IHzyBEk",id,ready);
      ModelDefaultsProbe.check(row==expected,"Session list omits AI Multi icon for "+id+": "+row);
    }
  }
  static void awaitReady(Object bridge,Object binding)throws Exception {
    for(int i=0;i<100;i++){if(Boolean.TRUE.equals(call(bridge,"ready",binding)))return;Thread.sleep(100);}
    throw new AssertionError("Native Air session never became ready");
  }
  static void selectNativeAgent(String name)throws Exception {
    SettingsRefreshProbe.edt(()->{
      Object keys=type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys").getField("INSTANCE").get(null);
      Map<?,?> selectors=(Map<?,?>)call(call(keys,"getSELECTORS"),"getData",launchContext);
      Object agent=selectors.entrySet().stream().filter(e->String.valueOf(e.getKey()).equals("Agent")).findFirst().orElseThrow().getValue();
      Object popup=call(agent,"createPopupForTest");
      if(popup==null)throw new AssertionError("Native agent popup is empty");
      List<?> rows=(List<?>)call(popup,"rowTexts"),ids=(List<?>)call(popup,"rowIds");
      ModelDefaultsProbe.check(rows.stream().filter("AI Multi"::equals).count()==1,"Legacy presentation alias added another launchable agent");
      Files.writeString(ClientPreferencesProbe.dir.resolve("agent-rows-"+name.replace(' ','-')+".txt"),"rows="+rows+"\nids="+ids);
      int index=rows.indexOf(name);
      ModelDefaultsProbe.check(index>=0&&Boolean.TRUE.equals(call(popup,"choose",ids.get(index))),"No native agent choice "+name+": "+rows);
    });
  }
  /** Read native warm-start state with the controlled fixture; never seed a custom cache or use production accounts. */
  static void auditNativeCache(Object project,Path dir,Object bridge,Object selector,Class<?> eventType)throws Exception {
    selectNativeAgent("Junie");Thread.sleep(700);
    Path trace=dir.resolve("air-chat-trace.txt");
    long before=Files.readAllLines(trace).stream().filter("session/new"::equals).count();
    selectNativeAgent("AI Multi");Thread.sleep(1800);
    StringBuilder report=new StringBuilder("Native Air warm-start audit; controlled backend only.\n");
    Object keys=type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys").getField("INSTANCE").get(null);
    Map<?,?> selectors=(Map<?,?>)call(call(keys,"getSELECTORS"),"getData",launchContext);
    Object agent=selectors.entrySet().stream().filter(e->e.getKey().toString().equals("Agent")).findFirst().orElseThrow().getValue();
    Object commands=field(call(agent,"getState"),"commandService"),lane=field(commands,"preferences");
    Object host=call(lane,"getPreferences"),state=call(call(host,"getPreferencesState"),"getValue");
    report.append("preferencesState=").append(state.getClass().getName()).append('\n');
    report.append("nativeCache=").append(field(host,"metadataCache").getClass().getName()).append('\n');
    Object resolved=call(field(host,"resolvedContextState"),"getValue");
    if(resolved!=null){
      Object key=call(type("com.intellij.air.frontend.session.preferences.AirSessionPreferencesMetadataCacheKt"),"metadataCacheKey",call(resolved,"getKey"));
      Object metadata=call(field(host,"metadataCache"),"get",key);
      report.append("nativeCacheHit=").append(metadata!=null).append('\n');
      report.append("deferChildStartWhenCached=").append(call(call(resolved,"getContext"),"getDeferChildStartWhenCached")).append('\n');
      report.append("childStartRequested=").append(call(field(host,"childStartRequestedState"),"getValue")).append('\n');
      if(metadata!=null){
        Object child=call(field(host,"childFactory"),"createFromCache",field(commands,"scope"),resolved,metadata);
        report.append("nativeCachedChildAccepted=").append(child!=null).append('\n');
        report.append("cachedConfigurableProvidersPresent=").append(call(metadata,"getConfigurableProviders")!=null).append('\n');
      }
    }
    report.append("newSessionRequestsDuringReturn=").append(Files.readAllLines(trace).stream().filter("session/new"::equals).count()-before).append('\n');
    report.append("pluginLiveBinding=").append(call(bridge,"binding",launchContext)!=null).append('\n');
    final Object[] presentation={null};
    SettingsRefreshProbe.edt(()->{
      Object event=call(eventType,"createFromAnAction",selector,null,"Air.Prompt.Preferences",launchContext);call(selector,"update",event);presentation[0]=call(event,"getPresentation");
    });
    report.append("profileSelectorEnabled=").append(call(presentation[0],"isEnabled")).append('\n');
    report.append("profileSelectorText=").append(call(presentation[0],"getText")).append('\n');
    report.append("defaultProfile=").append(call(call(project,"getService",Class.forName("dev.boyko.aiagentswitcher.ProjectSelection",true,loader)),"getSelected")).append('\n');
    Files.writeString(dir.resolve("cache-audit.txt"),report);
    captureAir(dir.resolve("native-warm-cache.png"));
  }
  static void chooseComposerProfile(Object selector,Method popup,Class<?> eventType,Class<?> dataContext,String name)throws Exception{
    SettingsRefreshProbe.edt(()->{
      Object menu=popup.invoke(selector,new JPanel(),launchContext);
      Object[] choices=(Object[])menu.getClass().getMethod("getChildren",eventType).invoke(menu,(Object)null);
      Object selected=null;for(Object choice:choices)if(String.valueOf(call(call(choice,"getTemplatePresentation"),"getText")).equals(name))selected=choice;
      ModelDefaultsProbe.check(selected!=null,"Native composer menu omits "+name);
      call(selected,"actionPerformed",call(eventType,"createFromAnAction",selected,null,"Air.Prompt.Preferences",launchContext));
    });
  }
  static void captureAir(Path path)throws Exception {
    SettingsRefreshProbe.edt(()->{
      java.awt.Window window=Arrays.stream(java.awt.Window.getWindows()).filter(w->w.isShowing()&&w instanceof java.awt.Frame).max(Comparator.comparingInt(w->w.getWidth()*w.getHeight())).orElseThrow();
      var screenshot=new java.awt.image.BufferedImage(window.getWidth(),window.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
      var graphics=screenshot.createGraphics();window.paintAll(graphics);graphics.dispose();javax.imageio.ImageIO.write(screenshot,"png",path.toFile());
    });
  }

}
