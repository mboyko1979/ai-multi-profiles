import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Sandbox-only proof of live Settings warnings with absent ACP recommendations. */
public class DefaultsWarningProbe extends ConcreteDefaultsProbe {
 public static void premain(String arg, Instrumentation instrumentation) throws Exception {
  dir=Path.of(arg).toAbsolutePath();
  if(!Path.of(System.getProperty("aiaps.home")).toAbsolutePath().normalize().equals(dir.resolve("home")))throw new IllegalStateException("Sandbox only");
  SettingsRefreshProbe.inst=instrumentation;
  System.setProperty("idea.trust.all.projects","true");
  new Thread(()->{try {
   for(int i=0;i<100;i++) {
    Thread.sleep(1000);app=call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
    if(app==null)continue;
    Object[] projects=(Object[])call(call(SettingsRefreshProbe.type("com.intellij.openapi.project.ProjectManager"),"getInstance"),"getOpenProjects");
    if(projects.length>0){project=projects[0];break;}
   }
   check(project!=null,"No sandbox project");
   Object id=call(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
   loader=(ClassLoader)call(call(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",id),"getPluginClassLoader");
   for(String host:List.of("assistant","air")) verify(host);
   Files.writeString(dir.resolve("result.txt"),"PASS: Assistant/Air Settings warnings clear immediately on Medium, ignore excluded models after refresh, return after reset and clear after correction.");
   System.exit(0);
  }catch(Throwable failure){try{java.io.StringWriter out=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(out));Files.writeString(dir.resolve("error.txt"),out.toString());}catch(Exception ignored){}System.exit(1);}},"defaults-warning-probe").start();
 }
 static boolean warning(JComponent page){return components(page).stream().anyMatch(c->c instanceof JTextArea text && text.isVisible() && text.getText().contains("requires a value"));}
 @SuppressWarnings({"rawtypes","unchecked"}) static void medium(JComponent page){
  JComboBox box=(JComboBox)components(page).stream().filter(c->c instanceof JComboBox<?> b && String.valueOf(b.getSelectedItem()).equals("Select a value")).findFirst().orElseThrow();
  for(int i=0;i<box.getItemCount();i++)if(String.valueOf(box.getItemAt(i)).equals("Medium")){box.setSelectedIndex(i);return;}
  throw new AssertionError("No Medium choice");
 }
 static void verify(String host)throws Exception {
  Object entry=type("dev.boyko.aiagentswitcher.AgentProfiles$Entry").getConstructor().newInstance();
  call(entry,"setAgentId","fixture");call(entry,"setId","warning-"+host);
  String json="""
   {"observedAt":1,"warnings":[],"options":[{"id":"model","operation":"config","category":"model","name":"Model","type":"select","currentValue":"a","options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]},{"id":"effort","operation":"config","category":"thought_level","name":"Reasoning","type":"select","currentValue":"high","options":[{"value":"medium","name":"Medium"},{"value":"high","name":"High"}]}]}
   """;
  Object gson=type("com.google.gson.Gson").getConstructor().newInstance();
  Object caps=call(single("ProfileConfiguration"),"parseCapabilities",call(gson,"fromJson",json,type("com.google.gson.JsonObject")));
  Object defaults=call(single("ProfileModelDefaults"),"refresh",entry,host,caps);
  call(models(defaults).get(1),"setEnabled",false);
  var constructor=type("dev.boyko.aiagentswitcher.ProfilePresetPanel").getDeclaredConstructors()[0];constructor.setAccessible(true);
  SettingsRefreshProbe.edt(()->{
   Object panel=constructor.newInstance(project);call(panel,"show",entry,null);
   JComponent page=(JComponent)call(panel,"clientPage",host);
   check(warning(page),host+": missing warning absent");medium(page);check(!warning(page),host+": stale warning after Medium");
   call(single("ProfileModelDefaults"),"refresh",entry,host,caps);call(panel,"show",entry,null);
   check(!warning(page),host+": refresh warns about excluded model");
   call(link(page,"Reset defaults for this model"),"doClick");
   check(warning(page),host+": reset failed to restore warning");medium(page);check(!warning(page),host+": corrected warning remains");
   call(panel,"dispose");
  });
 }
}
