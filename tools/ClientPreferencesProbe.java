import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native stores and cancellation lifecycle, synthetic provider in isolated runIde only. */
public class ClientPreferencesProbe {
 static Path dir;static Object app,project,service;static ClassLoader loader;
 static Object call(Object o,String name,Object...a)throws Exception{return SettingsRefreshProbe.call(o,name,a);}
 static Class<?> type(String n)throws Exception{return Class.forName(n,true,loader);}
 static Object singleton(String n)throws Exception{return type("dev.boyko.aiagentswitcher."+n).getField("INSTANCE").get(null);}
 static Object field(Object o,String n)throws Exception{return ChatStartupProbe.field(o,n);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Object json(String value)throws Exception{return call(type("com.google.gson.JsonParser"),"parseString",value);}
 static Object exchange(String request)throws Exception{
  Method m=Arrays.stream(service.getClass().getDeclaredMethods()).filter(x->x.getName().startsWith("exchange")).findFirst().orElseThrow();m.setAccessible(true);return m.invoke(service,json(request));
 }
 static Object suspend(Object target,String name,Object...a)throws Exception{
  CompletableFuture<Object> done=new CompletableFuture<>();Class<?> continuation=type("kotlin.coroutines.Continuation");
  Object dispatcher=call(type("kotlinx.coroutines.Dispatchers"),"getDefault");
  Object cont=Proxy.newProxyInstance(loader,new Class<?>[]{continuation},(proxy,m,args)->m.getName().equals("getContext")?dispatcher:m.getName().equals("resumeWith")?done.complete(args[0]):null);
  Object[] supplied=Arrays.copyOf(a,a.length+1);supplied[a.length]=cont;
  Method m=Arrays.stream(target.getClass().getMethods()).filter(x->x.getName().startsWith(name)&&x.getParameterCount()==supplied.length).findFirst().orElseThrow();m.setAccessible(true);
  Object result=m.invoke(target,supplied);if(result!=null&&!result.toString().equals("COROUTINE_SUSPENDED"))return result;
  Object value=done.get(25,TimeUnit.SECONDS);call(type("kotlin.ResultKt"),"throwOnFailure",value);return value;
 }
 public static void premain(String arg,Instrumentation inst)throws Exception{
  dir=Path.of(arg).toAbsolutePath();SettingsRefreshProbe.inst=inst;ChatStartupSupport.inst=inst;ChatStartupSupport.dir=dir;
  if(!Path.of(System.getProperty("aiaps.home")).toAbsolutePath().normalize().equals(dir.resolve("home")))throw new IllegalStateException("Sandbox only");
  System.setProperty("idea.trust.all.projects","true");new Thread(()->{try{
   for(int i=0;i<100;i++){Thread.sleep(1000);try{project=ChatStartupSupport.project();if(project!=null)break;}catch(Exception ignored){}}
   check(project!=null,"No sandbox project");loader=SettingsRefreshProbe.type("dev.boyko.aiagentswitcher.AgentProfiles").getClassLoader();app=call(type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
   service=call(app,"getService",type("dev.boyko.aiagentswitcher.ClientPreferences"));call(service,"start");
   installedLaunchRefresh();nativeStores();if(!Files.exists(dir.resolve("air-only")))nativeChat();else Thread.sleep(15000);nativeAir();Files.writeString(dir.resolve("result.txt"),"PASS: native client preference verification. See stores-result.txt, air-result.txt and optional Assistant/steering artifacts.\n");System.exit(0);
  }catch(Throwable e){try{var out=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(out));Files.writeString(dir.resolve("error.txt"),out.toString());}catch(Exception ignored){}System.exit(1);}},"client-preferences-probe").start();
 }
 static final String OPTIONS="[{\"id\":\"model\",\"operation\":\"config\",\"category\":\"model\",\"type\":\"select\"},{\"id\":\"effort\",\"operation\":\"config\",\"category\":\"thought_level\",\"type\":\"select\"},{\"id\":\"mode\",\"operation\":\"mode\",\"category\":\"mode\",\"type\":\"select\"}]";
 static final String PLAN_OPTION="{\"id\":\"collaboration_mode\",\"category\":\"collaboration_mode\",\"name\":\"Mode\",\"operation\":\"config\",\"type\":\"select\",\"currentValue\":\"default\",\"options\":[{\"value\":\"default\",\"name\":\"Default\"},{\"value\":\"plan\",\"name\":\"Plan\"}]}";
 static String request(String host,String profile,String agent,String op,String setting){return "{\"host\":\""+host+"\",\"profile\":\""+profile+"\",\"agentId\":\""+agent+"\",\"operation\":\""+op+"\",\"options\":"+OPTIONS.substring(0,OPTIONS.length()-1)+","+PLAN_OPTION+"]"+(setting==null?"":",\"setting\":"+setting)+"}";}
 static void nativeStores()throws Exception{
  Object bridgeForPlan=singleton("NativeRecoveryContinuation");Class<?> discoveryForPlan=(Class<?>)AirProfileProbe.call(bridgeForPlan,"type","com.intellij.air.acp.AcpGenerationSettingsKt","com.intellij.air");
  Class<?> optionForPlan=Class.forName("com.agentclientprotocol.model.SessionConfigOption",true,discoveryForPlan.getClassLoader());Object planSerializer=call(optionForPlan.getField("Companion").get(null),"serializer");
  Class<?> planJsonType=Class.forName("kotlinx.serialization.json.Json",true,optionForPlan.getClassLoader());Object planCodec=planJsonType.getField("Default").get(null);
  Object planOption=call(planCodec,"decodeFromString",planSerializer,PLAN_OPTION.replace(",\"operation\":\"config\"",""));
  Object discoveredPlan=call(discoveryForPlan,"discoverPlanModeConfig",List.of(planOption),List.of(),null);Files.writeString(dir.resolve("native-plan-discovery.txt"),String.valueOf(discoveredPlan));
  for(String host:List.of("assistant","air")){
   check(exchange(request(host,"personal","fixture","read",null)).toString().contains("\"settings\":[]"),"Unexpected initial preferences");
   for(String[] s:List.of(new String[]{"model","b","config"},new String[]{"effort","high","config"},new String[]{"mode","agent","mode"},new String[]{"collaboration_mode","plan","config"}))
    exchange(request(host,"personal","fixture","write","{\"id\":\""+s[0]+"\",\"value\":\""+s[1]+"\",\"operation\":\""+s[2]+"\"}"));
   String saved=exchange(request(host,"personal","fixture","read",null)).toString();check(saved.contains("\"value\":\"b\"")&&saved.contains("\"value\":\"high\"")&&saved.contains("\"value\":\"agent\"")&&(host.equals("air")?!saved.contains("collaboration_mode"):saved.contains("\"value\":\"plan\"")),host+" native round trip: "+saved);
   check(exchange(request(host,"work","fixture","read",null)).toString().contains("\"settings\":[]"),"Profiles share preferences");
   check(exchange(request(host,"personal","other","read",null)).toString().contains("\"settings\":[]"),"Agents share preferences");
   Files.writeString(dir.resolve(host+"-store.txt"),saved);
  }
  Object bridge=singleton("NativeRecoveryContinuation"),storeType=AirProfileProbe.call(bridge,"type","com.intellij.air.frontend.launch.AgentSessionLastUsedStoreImpl","com.intellij.air");
  Object airStore=call(app,"getService",storeType),state=call(airStore,"getState");Class<?> stateType=state.getClass();
  Object serializer=call(stateType.getField("Companion").get(null),"serializer");Class<?> jsonType=Class.forName("kotlinx.serialization.json.Json",true,stateType.getClassLoader());Object codec=jsonType.getField("Default").get(null);
  String encoded=(String)call(codec,"encodeToString",serializer,state);Object restored=call(codec,"decodeFromString",serializer,encoded);call(airStore,"loadState",restored);
  check(exchange(request("air","personal","fixture","read",null)).toString().contains("\"value\":\"high\""),"Air discarded preference identity during persistence round trip");Files.writeString(dir.resolve("air-native-state.json"),encoded);
  call(app,"saveSettings");Files.writeString(dir.resolve("stores-result.txt"),"PASS: actual native stores read/write under stable profile+agent keys, independent clients/profiles/agents.\n");
 }
 static void nativeChat()throws Exception{
  Files.copy(Path.of(System.getProperty("aiaps.project.root", System.getProperty("user.dir")), "tools", "preferences-fixture.js"),dir.resolve("backend.js"),StandardCopyOption.REPLACE_EXISTING);
  ChatStartupSupport.run("seed");
  Object profiles=call(app,"getService",type("dev.boyko.aiagentswitcher.AgentProfiles"));List<?> entries=(List<?>)call(profiles,"all");
  exchange(request("assistant","codex","fixture","write","{\"id\":\"model\",\"operation\":\"config\",\"value\":\"b\"}"));
  for(Object entry:entries){call(entry,"setAgentId","fixture");call(entry,"setAdvancedOverrides",true);}
  call(profiles,"replace",entries);call(singleton("GenericAutoRegistration"),"assignNow",project,"codex");call(singleton("GenericAutoRegistration"),"ensureAsync",project);
  for(int i=0;i<200&&!Files.exists(dir.resolve("home/.jetbrains/acp.json"));i++)Thread.sleep(100);
  check(Files.exists(dir.resolve("home/.jetbrains/acp.json")),"Sandbox registration missing");
  Object config=call(type("com.intellij.ml.llm.agents.acp.runtime.config.AcpConfigurationLoader").getField("INSTANCE").get(null),"loadFromPath",dir.resolve("home/.jetbrains/acp.json"));
  call(call(app,"getService",type("com.intellij.ml.llm.agents.acp.runtime.config.AcpAgentsConfigService")),"setLocalConfigForTesting",config);
  Thread.sleep(5000);
  SettingsRefreshProbe.await(()->ChatStartupSupport.run("open"));for(int i=0;i<150&&ChatStartupSupport.chat==null;i++)Thread.sleep(100);Object chat=ChatStartupSupport.chat;check(chat!=null,"No native chat");
  Object agent=null;for(int i=0;i<150;i++){agent=call(chat,"getAgentSession");if(agent!=null&&((java.util.concurrent.atomic.AtomicReference<?>)field(field(agent,"lifecycleManager"),"clientSessionRef")).get()!=null)break;Thread.sleep(100);}
  check(agent!=null,"No native agent");
  chat=nativeLaunchSelection(chat);agent=call(chat,"getAgentSession");Object nativeAgent=agent;
  sendInput(chat,"hold");
  for(int i=0;i<150;i++){if(Files.exists(dir.resolve("backend-trace.jsonl"))&&Files.readString(dir.resolve("backend-trace.jsonl")).contains("session/prompt"))break;Thread.sleep(100);}
  check(Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().anyMatch(x->x.contains("session/close")&&x.contains("\"profile\":\"grok\"")),"First prompt did not close unused Grok session");
  check(((Number)call(call(call(singleton("ChatProfiles"),"conversationRecord",call(chat,"getUid")),"getAsJsonArray","segments"),"size")).intValue()==1,"Unused prepared segments survived the first prompt");
  check(call(nativeAgent,"isTaskActive").equals(true),"No active task before cancellation");suspend(nativeAgent,"cancel");
  Object active=field(nativeAgent,"activeTask");Files.writeString(dir.resolve("after-cancel.txt"),"activeTask="+active+"\n");
  suspend(nativeAgent,"onMessageSent","after cancel",List.of());
  for(int i=0;i<150;i++){Thread.sleep(100);if(Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().filter(x->x.contains("session/prompt")||x.contains("_session/steering")).count()>=2&&!call(nativeAgent,"isTaskActive").equals(true))break;}
  if(call(nativeAgent,"isTaskActive").equals(true))Files.writeString(dir.resolve("hang.txt"),"activeTask="+field(nativeAgent,"activeTask")+"\n"+Files.readString(dir.resolve("backend-trace.jsonl")));
  check(call(nativeAgent,"isTaskActive").equals(false),"Native task stuck after completed response");
  check(exchange(request("assistant","codex","fixture","read",null)).toString().contains("\"value\":\"b\""),"Acquisition or before-prompt setup overwrote the native preference");
  Object filterSession=call(type("dev.boyko.aiagentswitcher.compat.ChatHandoffClient").getConstructor(Object.class).newInstance(chat),"nativeSessionOrNull");
  liveModelFilters("assistant","codex",()->call(call(filterSession,"getConfigOptions"),"getValue"),dir.resolve("backend-trace.jsonl"));
  nativeSteeringRepro(chat,nativeAgent,"routed");
  // The same native fixture without AI Multi's router isolates the host/adapter contract.
  Path direct=dir.resolve("direct-fixture.js"),directTrace=dir.resolve("direct-trace.jsonl");
  String traceJson=type("com.google.gson.Gson").getConstructor().newInstance().getClass().getMethod("toJson",Object.class).invoke(type("com.google.gson.Gson").getConstructor().newInstance(),directTrace.toString()).toString();
  String backendJson=type("com.google.gson.Gson").getConstructor().newInstance().getClass().getMethod("toJson",Object.class).invoke(type("com.google.gson.Gson").getConstructor().newInstance(),dir.resolve("backend.js").toString()).toString();
  Files.writeString(direct,"process.env.TRACE="+traceJson+";require("+backendJson+");");
  Object gson=type("com.google.gson.Gson").getConstructor().newInstance();String fileJson=(String)call(gson,"toJson",direct.toString());
  Path configPath=dir.resolve("direct-acp.json");Files.writeString(configPath,"{\"agent_servers\":{\"AI Multi\":{\"command\":\"C:/Program Files/nodejs/node.exe\",\"args\":["+fileJson+"]}}}");
  Object directConfig=call(type("com.intellij.ml.llm.agents.acp.runtime.config.AcpConfigurationLoader").getField("INSTANCE").get(null),"loadFromPath",configPath);
  call(call(app,"getService",type("com.intellij.ml.llm.agents.acp.runtime.config.AcpAgentsConfigService")),"setLocalConfigForTesting",directConfig);Thread.sleep(3000);
  ChatStartupSupport.chat=null;SettingsRefreshProbe.await(()->ChatStartupSupport.run("open"));for(int i=0;i<150&&ChatStartupSupport.chat==null;i++)Thread.sleep(100);
  Object directChat=ChatStartupSupport.chat;Thread.sleep(2000);Object directAgent=call(directChat,"getAgentSession");nativeSteeringRepro(directChat,directAgent,"direct");
 }
 static Object nativeLaunchSelection(Object original)throws Exception{
  Object current=original,selection=call(project,"getService",type("dev.boyko.aiagentswitcher.ProjectSelection"));
  Object focused=call(project,"getService",type("com.intellij.ml.llm.core.chat.session.FocusedChatSessionHost"));
  Object switching=type("dev.boyko.aiagentswitcher.compat.CurrentChatProfileSwitch").getField("INSTANCE").get(null);
  Object handoff=type("dev.boyko.aiagentswitcher.compat.ChatHandoffClient").getConstructor(Object.class).newInstance(original);
  Object codexSession=call(handoff,"nativeSessionOrNull"),grokSession=null;
  long creationsBefore=Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().filter(x->x.contains("session/new")).count();
  Thread.sleep(1000); // Let the native initial draft restoration finish before typing.
  final Object[] inputDocument={null};SettingsRefreshProbe.await(()->{
    Object input=call(call(original,"getAssociatedInput"),"getValue");check(input!=null,"Native input is not attached yet");
    inputDocument[0]=call(input,"getDocument");call(input,"setText","Unsent draft remains here");
    check(call(inputDocument[0],"getText").equals("Unsent draft remains here"),"Native input did not accept the draft");
  });
  Files.writeString(dir.resolve("draft-binding.txt"),"before="+call(inputDocument[0],"getText")+"; document="+System.identityHashCode(inputDocument[0])+"\n");
  for(String target:List.of("grok","codex","grok","codex")){
    String previousUid=String.valueOf(call(current,"getUid"));
    suspend(switching,"choose",current,target);
    current=call(focused,"getFocusedChatSession");
    Object actualDocument=call(call(call(original,"getAssociatedInput"),"getValue"),"getDocument");
    Files.writeString(dir.resolve("draft-binding.txt"),target+"="+call(actualDocument,"getText")+"; document="+System.identityHashCode(actualDocument)+"\n",StandardOpenOption.APPEND);
    check(call(actualDocument,"getText").equals("Unsent draft remains here"),"Profile selection discarded the native draft text");
    check(current==original&&String.valueOf(call(current,"getUid")).equals(previousUid),"Empty-chat selection replaced the native chat");
    String uid=String.valueOf(call(current,"getUid"));Object record=null;
    for(int i=0;i<200;i++){
      record=call(singleton("ChatProfiles"),"conversationRecord",uid);
      if(record!=null&&record.toString().contains("\"profile\":\""+target+"\""))break;
      Thread.sleep(100);
    }
    check(record!=null&&record.toString().contains("\"profile\":\""+target+"\""),"Native chat did not start with selected profile "+target+": "+record);
    Object segments=call(record,"getAsJsonArray","segments");check(((Number)call(segments,"size")).intValue()==2,"Empty selection recreated a prepared provider");
    Object sdk=call(handoff,"nativeSessionOrNull");
    if(target.equals("codex"))check(sdk==codexSession,"Codex did not retain its native SDK session handle");
    else {if(grokSession==null)grokSession=sdk;check(sdk==grokSession,"Grok did not retain its native SDK session handle");}
    check(call(sdk,"getConfigOptionsSupported").equals(target.equals("codex")),"Config capability belongs to the wrong profile");
    check(call(sdk,"getModelsSupported").equals(target.equals("grok")),"Legacy model capability belongs to the wrong profile");
    check(call(call(current,"getAgentSession"),"getSupportsSteering").equals(target.equals("codex")),"Steering capability belongs to the wrong profile");
    check(call(selection,"getSelected").equals("codex"),"Native launch changed project Default Profile");
    check(!Files.readString(dir.resolve("backend-trace.jsonl")).contains("session/prompt"),"Selecting a launch profile sent a provider prompt");
  }
  check(call(singleton("ChatProfiles"),"conversationRecord",call(original,"getUid")).toString().contains("\"profile\":\"codex\""),"Launch selection changed original chat's committed profile");
  ChatStartupSupport.chat=current;
  check(Files.readAllLines(dir.resolve("backend-trace.jsonl")).stream().filter(x->x.contains("session/new")).count()==creationsBefore+1,"Return to a prepared profile sent another session/new");
  check(call(call(call(call(original,"getAssociatedInput"),"getValue"),"getDocument"),"getText").equals("Unsent draft remains here"),"Profile selection discarded the draft");
  SettingsRefreshProbe.edt(()->call(call(call(original,"getAssociatedInput"),"getValue"),"setText",""));
  Files.writeString(dir.resolve("assistant-launch-result.txt"),"PASS: same native chat and draft, four profile selections, identical native SDK handles on return, two backend sessions only, real per-agent config/model/steering capabilities, no provider prompt, unchanged project default.\n");
  return current;
 }
 static void installedLaunchRefresh()throws Exception{
  Path config=dir.resolve("home/.jetbrains/acp.json");
  for(int i=0;i<100&&!Files.exists(config);i++)Thread.sleep(100);
  String original=Files.readString(config);
  Object profiles=call(app,"getService",type("dev.boyko.aiagentswitcher.AgentProfiles"));
  Object saved=call(profiles,"all");
  try{
   Object root=json(original),servers=call(root,"getAsJsonObject","agent_servers");
   call(servers,"add","Launch refresh fixture",json("{\"command\":\"fixture-v2-do-not-run\",\"args\":[\"--new\"],\"env\":{\"FIXTURE_VERSION\":\"2\"}}"));
   Files.writeString(config,root.toString());
   Object installed=type("dev.boyko.aiagentswitcher.compat.InstalledAgents").getField("INSTANCE").get(null);
   Object choice=((List<?>)call(installed,"all")).stream().filter(x->{try{return call(x,"getName").equals("Launch refresh fixture");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();
   Object p=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
   call(p,"setId","launch-refresh");call(p,"setName","Launch refresh");call(p,"setAgentId",call(choice,"getId"));call(p,"setCommand","fixture-v1-removed");
   call(profiles,"replace",List.of(p));call(singleton("GenericAutoRegistration"),"syncRoutesNow",project);
   String routes=Files.readString(dir.resolve("home/.jetbrains/ai-agent-project-switcher/routes.json"));
   check(routes.contains("fixture-v2-do-not-run")&&routes.contains("--new")&&routes.contains("FIXTURE_VERSION"),"Routes retained stale native launch");
   check(call(((List<?>)call(profiles,"all")).get(0),"getCommand").equals("fixture-v1-removed"),"Refresh mutated stored profile settings");
   call(p,"setAdvancedOverrides",true);call(profiles,"replace",List.of(p));call(singleton("GenericAutoRegistration"),"syncRoutesNow",project);
   check(Files.readString(dir.resolve("home/.jetbrains/ai-agent-project-switcher/routes.json")).contains("fixture-v1-removed"),"Advanced launch was overridden");
   Files.writeString(dir.resolve("installed-launch-result.txt"),"PASS: fresh native launch replaces removed snapshot; args/environment retained; advanced overrides and stored settings preserved. No fixture process launched.\n");
  }finally{Files.writeString(config,original);call(profiles,"replace",saved);call(singleton("GenericAutoRegistration"),"syncRoutesNow",project);}
 }
 static void nativeSteeringRepro(Object chat,Object agent,String name)throws Exception{
  Path trace=dir.resolve(name.equals("direct")?"direct-trace.jsonl":"backend-trace.jsonl");long before=Files.exists(trace)?Files.readAllLines(trace).stream().filter(x->x.contains("session/prompt")).count():0;
  sendInput(chat,"hold");for(int i=0;i<150;i++){if(Files.exists(trace)&&Files.readAllLines(trace).stream().filter(x->x.contains("session/prompt")).count()>before)break;Thread.sleep(100);}
  check(call(agent,"isTaskActive").equals(true),"No active task for steering repro");suspend(agent,"onMessageSent","follow up at turn boundary",List.of());Thread.sleep(2000);
  check(Files.readString(trace).contains("_session/steering"),"Native client did not select steering");
  boolean stuck=call(agent,"isTaskActive").equals(true);Files.writeString(dir.resolve(name+"-steering-repro.txt"),"nativeTaskStillActive="+stuck+"\n"+Files.readString(trace));
  check(stuck,"Expected pinned-host startedNewTurn completion issue was not reproduced");suspend(agent,"cancel");
  for(int i=0;i<100&&call(agent,"isTaskActive").equals(true);i++)Thread.sleep(100);
 }
 static void nativeAir()throws Exception{
  call(singleton("GenericAutoRegistration"),"ensureAsync",project);for(int i=0;i<150&&!Files.exists(dir.resolve("home/.jetbrains/ai-agent-project-switcher/router.js"));i++)Thread.sleep(100);
  String boundAgent=Files.exists(dir.resolve("folded-agent"))?"acp.registry.codex-acp":"acp.fixture-air";
  String req=request("air","air-Personal",boundAgent,"write","{\"id\":\"effort\",\"operation\":\"config\",\"value\":\"high\"}").replace("effort","reasoning");exchange(req);
  exchange(request("air","air-Work",boundAgent,"write","{\"id\":\"model\",\"operation\":\"config\",\"value\":\"a\"}"));
  exchange(request("air","air-Work",boundAgent,"write","{\"id\":\"effort\",\"operation\":\"config\",\"value\":\"low\"}").replace("effort","reasoning"));
  SettingsRefreshProbe.app=app;System.setProperty("aiaps.verifyNativePreferences","true");AirProfileProbe.verify(project,loader,dir);
  check(exchange(request("air","air-Personal",boundAgent,"read",null).replace("effort","reasoning")).toString().contains("\"value\":\"low\""),"Air explicit selection was not persisted to its native store");
  check(exchange(request("air","air-Work",boundAgent,"read",null).replace("effort","reasoning")).toString().contains("\"value\":\"low\""),"Air profile defaults overwrote remembered preferences");
 }
 interface OptionsRead {Object get()throws Exception;}
 static List<String> modelIds(Object options)throws Exception {
  for(Object option:(List<?>)options)if(String.valueOf(AirProfileProbe.call(option,"getId-")).equals("model")){
   List<String> ids=new ArrayList<>();for(Object item:(List<?>)call(call(option,"getOptions"),"getOptions"))ids.add(String.valueOf(AirProfileProbe.call(item,"getValue-")));return ids;
  }throw new AssertionError("No native model option: "+options);
 }
 static Map<String,String> selectedValues(Object options)throws Exception {
  Map<String,String> values=new LinkedHashMap<>();for(Object option:(List<?>)options)values.put(String.valueOf(AirProfileProbe.call(option,"getId-")),String.valueOf(AirProfileProbe.call(option,"getCurrentValue")));return values;
 }
 static void liveModelFilters(String host,String profileId,OptionsRead read,Path trace)throws Exception {
  Object profiles=call(app,"getService",type("dev.boyko.aiagentswitcher.AgentProfiles"));List<?> saved=(List<?>)call(profiles,"all");
  Object before=read.get();List<String> initial=modelIds(before);check(initial.containsAll(List.of("a","b","c")),"Fixture needs three native models: "+initial);
  Object option=((List<?>)before).getFirst();String current=String.valueOf(AirProfileProbe.call(option,"getCurrentValue-"));String allowed=current.equals("a")?"b":"a";
  String traceBefore=Files.readString(trace);Map<String,String> valuesBefore=selectedValues(before);StringBuilder report=new StringBuilder("initial="+initial+"; current="+current+"\n");
  try {
   for(boolean excluded:List.of(true,false,true)){
    List<?> entries=(List<?>)call(profiles,"all");Object p=entries.stream().filter(e->{try{return call(e,"getId").equals(profileId);}catch(Exception failure){throw new RuntimeException(failure);}}).findFirst().orElseThrow();
    String preset="{\"host\":\""+host+"\",\"agentId\":\""+call(p,"getAgentId")+"\",\"defaultModelId\":\""+allowed+"\",\"modelOptionId\":\"model\",\"modelOperation\":\"config\",\"models\":[{\"modelId\":\""+current+"\",\"enabled\":false},{\"modelId\":\""+allowed+"\",\"enabled\":true},{\"modelId\":\"c\",\"enabled\":"+!excluded+"}]}";
    Object gson=type("com.google.gson.Gson").getConstructor().newInstance();Object defaults=call(gson,"fromJson",preset,type("dev.boyko.aiagentswitcher.AgentProfiles$ClientDefaults"));
    @SuppressWarnings("unchecked") List<Object> all=new ArrayList<>((List<Object>)call(p,"getClientDefaults"));all.removeIf(d->{try{return call(d,"getHost").equals(host);}catch(Exception failure){throw new RuntimeException(failure);}});all.add(defaults);call(p,"setClientDefaults",all);
    call(profiles,"replace",entries);call(singleton("GenericAutoRegistration"),"assignNow",project,profileId);
    List<String> expected=excluded?List.of(current,allowed):List.of(current,allowed,"c");List<String> actual=List.of();
    for(int i=0;i<70;i++){actual=modelIds(read.get());if(new HashSet<>(actual).equals(new HashSet<>(expected)))break;Thread.sleep(100);}
    report.append("excluded=").append(excluded).append("; expected=").append(expected).append("; actual=").append(actual).append('\n');Files.writeString(dir.resolve(host+"-live-filters.txt"),report);
    check(new HashSet<>(actual).equals(new HashSet<>(expected)),host+" live model filter did not update: "+report);
    Object updated=((List<?>)read.get()).getFirst();check(String.valueOf(AirProfileProbe.call(updated,"getCurrentValue-")).equals(current),"Filter changed accepted model");
    check(selectedValues(read.get()).equals(valuesBefore),"Filter changed accepted session parameters");
    check(Files.readString(trace).equals(traceBefore),"Editing filters contacted the backend: "+Files.readString(trace).substring(traceBefore.length()));
   }
  }finally{call(profiles,"replace",saved);call(singleton("GenericAutoRegistration"),"assignNow",project,profileId);}
  Files.writeString(dir.resolve(host+"-live-filters.txt"),report+"PASS: current retained, exclusion/reinclusion reactive; no backend request or session replacement.\n");
 }
 static void sendInput(Object chat,String text)throws Exception{SettingsRefreshProbe.await(()->{Object input=call(call(chat,"getAssociatedInput"),"getValue");check(input!=null,"Native input is not attached");call(input,"setText",text);call(input,"invokeSendButtonAction",false,ChatStartupSupport.callback(v->{}));});}
}
