import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Real native Air installer, served only by an isolated loopback fixture. */
class NativeAgentUpdatesProbe {
 static void isolateStorage()throws Exception{
  Path root=ClientPreferencesProbe.dir.resolve("native-registry");Files.createDirectories(root);
  // Both pinned hosts default to a shared ACP store, even in runIde. Use only
  // their TestOnly path overrides here, before any synthetic registry mutation.
  for(String host:List.of("air","assistant")){
   ClassLoader loader=host.equals("air")?air("runtime.registry.AcpPaths").getClassLoader():type("com.intellij.ml.llm.agents.acp.api.AcpAgentId").getClassLoader();
   String prefix=host.equals("air")?"com.intellij.air.acp.runtime.registry.":"com.intellij.ml.llm.agents.acp.runtime.registry.";
   Class<?> paths=Class.forName(prefix+"AcpPaths",true,loader);Object companion=paths.getField("Companion").get(null);
   AirProfileProbe.call(companion,"setTestBaseDirOverride",root.resolve("binaries"));
   AirProfileProbe.call(companion,"setTestDataDirOverride",root.resolve("data"));
   Object state=call(ClientPreferencesProbe.app,"getService",Class.forName(prefix+"AcpAgentInstallationState",true,loader));
   AirProfileProbe.call(state,"resetForTesting");
  }
  Files.writeString(ClientPreferencesProbe.dir.resolve("native-registry-isolation.txt"),"PASS: both native ACP metadata and binary roots redirected to sandbox before installer fixtures.\n");
 }
 static Object call(Object o,String n,Object...a)throws Exception{return ClientPreferencesProbe.call(o,n,a);}
 static Object susp(Object o,String n,Object...a)throws Exception{return ClientPreferencesProbe.suspend(o,n,a);}
 static Class<?> type(String n)throws Exception{return ClientPreferencesProbe.type(n);}
 static void check(boolean ok,String m){ClientPreferencesProbe.check(ok,m);}
 static AutoCloseable prepareFoldedFixture()throws Exception{
  Object app=ClientPreferencesProbe.app,paths=call(app,"getService",air("runtime.registry.AcpPaths"));
  Path root=(Path)call(paths,"getBaseDirOnHost");check(root.toAbsolutePath().startsWith(ClientPreferencesProbe.dir),"Folded fixture escaped sandbox");
  Path binary=root.resolve("codex-acp/999.0.0/fixture.exe");Files.createDirectories(binary.getParent());Files.writeString(binary,"Inert fixture; never execute.");
  Files.writeString(binary.getParent().resolve(".installed"),"./fixture.exe");
  Object agent=decode(air("runtime.registry.ExternalAgent"),"{\"id\":\"codex-acp\",\"name\":\"Codex fixture\",\"version\":\"999.0.0\",\"description\":\"Sandbox folded route\",\"distribution\":{\"binary\":{\"windows-x86_64\":{\"archive\":\"http://127.0.0.1:1/unused.zip\",\"cmd\":\"./fixture.exe\"}}}}");
  Object id=call(agent,"getId"),state=call(app,"getService",air("runtime.registry.AcpAgentInstallationState"));call(state,"markInstalled",agent,false);
  call(call(app,"getService",air("AcpAgentDownloadConsentService")),"accept",id);
  // The overlay resolves distribution metadata from the registry getter as well
  // as installed.json. Keep both controlled; otherwise it resolves the real npx
  // package and starts an unrelated Node download in an otherwise empty sandbox.
  Object manager=call(app,"getService",air("runtime.registry.AcpRegistryManager"));java.lang.reflect.Field field=manager.getClass().getDeclaredField("registryStatusFlow");field.setAccessible(true);Object original=field.get(manager);
  Object registry=air("runtime.registry.AcpExternalRegistry").getConstructor(String.class,List.class).newInstance("1",List.of(agent));
  Object ready=air("runtime.registry.AcpRegistryStatus$Ready").getConstructor(air("runtime.registry.AcpExternalRegistry")).newInstance(registry);
  field.set(manager,call(type("kotlinx.coroutines.flow.StateFlowKt"),"MutableStateFlow",ready));
  return ()->{field.set(manager,original);call(state,"uninstall",id);};
 }
 static void verifyTerms(Object chat,Object manager)throws Exception{
  String prefix="com.intellij.ml.llm.agents.acp.";
  Object state=call(ClientPreferencesProbe.app,"getService",type(prefix+"runtime.registry.AcpAgentInstallationState"));
  Object agent=decode(type(prefix+"runtime.registry.ExternalAgent"),"{\"id\":\"fixture\",\"name\":\"Bound terms fixture\",\"version\":\"1\",\"description\":\"Sandbox terms\",\"distribution\":{}}");
  Object id=call(agent,"getId");call(state,"markInstalled",agent,false);
  // Terms are a native provider extension, not a serialized registry field.
  agent=call(state,"getInstalledAgent",id);
  Object revised=type(prefix+"runtime.registry.TermsOfService").getConstructor(String.class,String.class).newInstance("Controlled revised terms","review-v2");
  java.lang.reflect.Field pending=agent.getClass().getDeclaredField("termsOfService$delegate");pending.setAccessible(true);pending.set(agent,call(type("kotlin.LazyKt"),"lazyOf",revised));
  java.lang.reflect.Field chooserField=type(prefix+"client.AcpSessionLifecycleManager").getDeclaredField("chatOptionChooser");chooserField.setAccessible(true);Object original=chooserField.get(manager);
  var events=new java.util.concurrent.atomic.AtomicInteger();ClassLoader loader=ClientPreferencesProbe.loader;
  Object unit=type("kotlin.Unit").getField("INSTANCE").get(null);
  Object sink=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{type("kotlinx.coroutines.channels.SendChannel")},(p,m,a)->{if(m.getName().equals("send")){events.incrementAndGet();return unit;}if(m.getName().equals("isClosedForSend"))return false;return null;});
  Object cancelled=type(prefix+"client.AcpUserResponse$TurnCancelled").getField("INSTANCE").get(null);
  Object responses=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{type("kotlinx.coroutines.flow.SharedFlow")},(p,m,a)->{if(m.getName().equals("getReplayCache"))return List.of();if(m.getName().equals("collect"))try{return call(a[0],"emit",cancelled,a[1]);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}return null;});
  Object chooser=type(prefix+"client.AcpChatOptionChooser").getConstructors()[0].newInstance(sink,responses);
  try{
   chooserField.set(manager,chooser);
   Object terms=call(ClientPreferencesProbe.app,"getService",type(prefix+"registry.AcpTermsOfService"));
   check(call(terms,"getPendingTerms",agent)!=null,"Synthetic revised terms did not become pending");
   Object updates=type("dev.boyko.aiagentswitcher.compat.ProfileAgentUpdates").getField("INSTANCE").get(null);
   check(Boolean.FALSE.equals(susp(updates,"ensureTerms",chat,manager)),"Bound native terms cancellation did not decline prompt admission");
   check(events.get()>0,"Bound terms never reached the real native chooser");
   check(call(terms,"getPendingTerms",agent)!=null,"Cancelled terms were marked accepted");
   Files.writeString(ClientPreferencesProbe.dir.resolve("native-bound-terms.txt"),"PASS: revised terms for the bound registry agent reach the real native terms service/chooser; native cancellation declines admission and leaves terms pending. Controlled channel response, no real terms accepted.\n");
  }finally{chooserField.set(manager,original);call(state,"uninstall",id);}
 }
 static Object decode(Class<?> type,String json)throws Exception{
  Object serializer=call(type.getField("Companion").get(null),"serializer");
  Class<?> codec=Class.forName("kotlinx.serialization.json.Json",true,type.getClassLoader());
  return call(codec.getField("Default").get(null),"decodeFromString",serializer,json);
 }
 static Class<?> air(String suffix)throws Exception{
  return (Class<?>)AirProfileProbe.call(ClientPreferencesProbe.singleton("NativeRecoveryContinuation"),"type","com.intellij.air.acp."+suffix,"com.intellij.air");
 }
 static void verify()throws Exception{
  Path dir=ClientPreferencesProbe.dir;
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("fixture.exe"));zip.write("Installer fixture; never execute this file.".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
  byte[] archive=bytes.toByteArray();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
  try(ServerSocket server=new ServerSocket(0,10,InetAddress.getLoopbackAddress())){
   var downloads=new java.util.concurrent.atomic.AtomicInteger();
   Thread serving=new Thread(()->{while(!server.isClosed())try(Socket socket=server.accept()){
    socket.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
    String first=in.readLine();for(String line;(line=in.readLine())!=null&&!line.isEmpty();){}
    downloads.incrementAndGet();OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: "+archive.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));if(first!=null&&!first.startsWith("HEAD"))out.write(archive);out.flush();
   }catch(IOException e){if(!server.isClosed())throw new UncheckedIOException(e);}},"native-update-fixture-http");serving.setDaemon(true);serving.start();
   Object app=ClientPreferencesProbe.app;
   Object registry=call(app,"getService",air("runtime.registry.AcpRegistryManager"));
   Object state=call(app,"getService",air("runtime.registry.AcpAgentInstallationState"));
   Object consent=call(app,"getService",air("AcpAgentDownloadConsentService"));
   // Start the real background observer on the original registry flow before
   // replacing the manager's getter with our non-publishing fixture flow.
   call(app,"getService",air("AcpAgentAutoUpdater"));Thread.sleep(500);
   java.lang.reflect.Field registryField=registry.getClass().getDeclaredField("registryStatusFlow");registryField.setAccessible(true);
   Object saved=registryField.get(registry);List<Object> agents=new ArrayList<>(),ids=new ArrayList<>();
   Class<?> agentType=air("runtime.registry.ExternalAgent");
   try{
    for(String raw:List.of("review-codex","review-grok","review-unrelated")){
     String distribution="{\"binary\":{\"windows-x86_64\":{\"archive\":\"http://127.0.0.1:"+server.getLocalPort()+"/agent.zip\",\"sha256\":\""+hash+"\",\"cmd\":\"./fixture.exe\"}}}";
     String spec="{\"id\":\""+raw+"\",\"name\":\""+raw+"\",\"version\":\"1.0.0\",\"description\":\"Isolated fixture\",\"distribution\":"+distribution+"}";
     Object old=decode(agentType,spec),next=decode(agentType,spec.replace("1.0.0","2.0.0")),id=call(old,"getId");
     call(state,"markInstalled",old,false);agents.add(next);ids.add(id);
    }
    Object controlled=air("runtime.registry.AcpExternalRegistry").getConstructor(String.class,List.class).newInstance("1",agents);
    // Do not emit the fixture registry globally: its background observer would
    // independently update every fixture. Supply the native manager's current
    // registry value without notifying observers, then exercise our scoped sweep.
    Object ready=air("runtime.registry.AcpRegistryStatus$Ready").getConstructor(air("runtime.registry.AcpExternalRegistry")).newInstance(controlled);
    Object controlledFlow=call(type("kotlinx.coroutines.flow.StateFlowKt"),"MutableStateFlow",ready);
    registryField.set(registry,controlledFlow);
    Object updater=type("dev.boyko.aiagentswitcher.air.AirProfileAgentUpdates").getField("INSTANCE").get(null);
    for(int i=0;i<2;i++){
     Object id=ids.get(i),p=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
     call(p,"setId","update-"+i);call(p,"setAgentId",call(id,"getFullId"));call(p,"setAdvancedOverrides",false);
     // Explicit synthetic consent stands in for the native user's prior choice.
     call(consent,"accept",id);
     registryField.set(registry,controlledFlow);
     susp(updater,"beforeLaunch",p);
     for(int wait=0;wait<150&&!"2.0.0".equals(call(state,"getInstalledVersion",id));wait++)Thread.sleep(100);
     check("2.0.0".equals(call(state,"getInstalledVersion",id)),"Air native updater did not install bound distribution: "+id);
     check(call(state,"getLastUsedAt",id)!=null,"Bound registry usage was not recorded");
    }
    check(call(state,"getInstalledVersion",ids.get(2)).equals("1.0.0"),"Profile update installed an unrelated agent");
    check(downloads.get()>=2,"Native installer did not download both controlled distributions");
    Files.writeString(dir.resolve("air-native-updates.txt"),"PASS: two vendor IDs updated 1.0.0 -> 2.0.0 through Air's real native updater, coordinator, loopback ZIP download, hash validation and installer; native usage recorded, unrelated distribution unchanged. Fixture executables never launched.\n");
   }finally{
    for(Object id:ids)call(state,"uninstall",id);
    registryField.set(registry,saved);
   }
  }
 }
}
