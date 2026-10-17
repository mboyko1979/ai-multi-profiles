import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Native toolbar regression checks in runIde, with synthetic controls only. */
public class ToolbarRefreshProbe {
  static Path dir;
  static ClassLoader loader;
  static Object call(Object o,String name,Object... args)throws Exception {return SettingsRefreshProbe.call(o,name,args);}
  static Class<?> type(String name)throws Exception {return Class.forName(name,true,loader);}
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static Object construct(String name,Object... args)throws Exception {
    for(Constructor<?> c:type(name).getDeclaredConstructors())if(c.getParameterCount()==args.length){c.setAccessible(true);return c.newInstance(args);}
    throw new NoSuchMethodException(name);
  }
  static Object flow(Object value)throws Exception {return call(type("kotlinx.coroutines.flow.StateFlowKt"),"MutableStateFlow",value);}
  static void set(Object o,String name,Object value)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
  static Object allocate(String name)throws Exception {
    Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
    Object result=((sun.misc.Unsafe)f.get(null)).allocateInstance(type(name));
    Field placement=type(CONTROL).getDeclaredField("placement");placement.setAccessible(true);
    placement.set(result,type("com.intellij.ml.llm.core.chat.ui.chat.input.toggle.AiChatToolbarPlacement").getField("INPUT").get(null));
    return result;
  }
  public static void premain(String arg,Instrumentation inst)throws Exception {
    dir=Path.of(arg).toAbsolutePath();SettingsRefreshProbe.inst=inst;
    if(!Path.of(System.getProperty("aiaps.home")).toAbsolutePath().normalize().equals(dir.resolve("home")))throw new IllegalStateException("Sandbox only");
    System.setProperty("idea.trust.all.projects","true");
    new Thread(()->{
      try {
        for(int i=0;i<100;i++){
          Thread.sleep(1000);
          SettingsRefreshProbe.app=call(SettingsRefreshProbe.type("com.intellij.openapi.application.ApplicationManager"),"getApplication");
          if(SettingsRefreshProbe.app==null)continue;
          Object id=call(SettingsRefreshProbe.type("com.intellij.openapi.extensions.PluginId"),"getId","dev.boyko.ai-multi-profiles");
          Object plugin=call(SettingsRefreshProbe.type("com.intellij.ide.plugins.PluginManagerCore"),"getPlugin",id);
          if(plugin==null)continue;
          loader=(ClassLoader)call(plugin,"getPluginClassLoader");if(loader!=null)break;
        }
        check(loader!=null,"Plugin did not load");
        SettingsRefreshProbe.edt(()->verify());
        Files.writeString(dir.resolve("result.txt"),"PASS: no duplicate loader when config replaces cached legacy model or mode; stable slots through catalog gaps; model, selector and boolean progress use native input/bottom placement, disabled descriptors, distinct animated frames and stable IDs; original controls restored on completion/failure; another chat unaffected. No provider or primary IDE accessed.\n");
        Runtime.getRuntime().halt(0);
      }catch(Throwable e){try{var out=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(out));Files.writeString(dir.resolve("error.txt"),out.toString());}catch(Exception ignored){}Runtime.getRuntime().halt(1);}
    },"toolbar-refresh-probe").start();
  }
  static final String CONTROL="com.intellij.ml.llm.core.chat.ui.chat.input.toggle.AiChatToolbarControl";
  static final String ACP="com.intellij.ml.llm.agents.acp.client.settings.";
  static final String PRESENTATION="dev.boyko.aiagentswitcher.compat.ProfileToolbarIsolation$RefreshPresentation";
  static List<?> render(Object p,List<?> controls,int frame,boolean suppressed)throws Exception {return (List<?>)call(p,"render",controls,frame,suppressed);}
  static void verify()throws Exception {
    Object model=allocate(ACP+"AcpModelToggleProvider$getControl$1");set(model,"isVisible",flow(false));
    Object mode=allocate(ACP+"AcpModeToggleProvider$getControl$1");set(mode,"isVisible",flow(true));
    Object selected=construct(CONTROL+"$Selector$Option","Auto review","Mode",null);set(mode,"selectedOption",flow(selected));
    Object reasoning=allocate(ACP+"AcpConfigOptionToggleProvider$createSelectorForOption$1");set(reasoning,"isVisible",flow(true));
    set(reasoning,"selectedOption",flow(construct(CONTROL+"$Selector$Option","High","Reasoning effort",null)));
    Object toggle=allocate(ACP+"AcpConfigOptionToggleProvider$createToggleForBooleanOption$1");set(toggle,"isVisible",flow(true));
    Object option=construct("com.agentclientprotocol.model.SessionConfigOption$BooleanOption","fast","Fast mode","Fast mode",null,true,null);
    Object state=construct(ACP+"AcpConfigOptionsManager$AgentConfigState",List.of(option),Map.of());
    set(toggle,"$agentStateFlow",flow(state));set(toggle,"$optionId","fast");set(toggle,"this$0",construct(ACP+"AcpConfigOptionToggleProvider"));

    // Bootstrap can publish legacy controls before config options arrive. Once
    // replaced, that cached slot must not return for any spinner frame.
    for(Object legacy:List.of(model,mode)){
      Object p=construct(PRESENTATION);
      check(render(p,List.of(legacy),-1,false).getFirst()==legacy,"Idle control changed");
      check(render(p,List.of(),0,false).size()==1,"Catalog gap lost progress");
      for(int frame=0;frame<3;frame++)check(render(p,List.of(legacy),frame,true).isEmpty(),"Suppressed legacy loader reappeared");
      check(render(p,List.of(),1,false).isEmpty(),"Suppression retained stale cache");
    }
    Object p=construct(PRESENTATION),other=construct(PRESENTATION);
    List<?> originals=List.of(model,mode,reasoning,toggle);
    render(p,originals,-1,false);
    List<?> first=render(p,originals,0,false),second=render(p,List.of(),1,false);
    check(first.size()==4 && second.size()==4,"Refresh changed slot count");
    check(render(other,originals,-1,false).equals(originals),"Other chat changed");
    Object scope=call(type("kotlinx.coroutines.CoroutineScopeKt"),"MainScope");
    Class<?> vm=type("com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantNewInputToolbarViewModel");
    Method bottom=Arrays.stream(vm.getDeclaredMethods()).filter(m->m.getName().equals("createBottomControlDescriptors$lambda$0")).findFirst().orElseThrow();bottom.setAccessible(true);
    Method input=Arrays.stream(vm.getDeclaredMethods()).filter(m->m.getName().equals("createInputTogglesAndSelectorsDescriptors$lambda$0")).findFirst().orElseThrow();input.setAccessible(true);
    Object factory=type("com.intellij.ml.llm.core.chat.ui.chat.input.AiChatInputToolbarFactory").getField("INSTANCE").get(null);
    for(int i=0;i<4;i++){
      Object a=first.get(i),b=second.get(i),nativeControl=originals.get(i);
      boolean isBottom=i==0 || Boolean.TRUE.equals(bottom.invoke(null,nativeControl));
      check(Boolean.valueOf(isBottom).equals(bottom.invoke(null,a)),"Bottom placement changed");
      check(Boolean.valueOf(!isBottom).equals(input.invoke(null,a)),"Input placement changed");
      Object previous=null;
      for(Object progress:List.of(a,b)){
        Object predicate=Proxy.newProxyInstance(type("kotlin.jvm.functions.Function1").getClassLoader(),new Class[]{type("kotlin.jvm.functions.Function1")},(o,m,args)->m.getName().equals("invoke")?true:null);
        Object allocator=construct("com.intellij.ml.llm.core.chat.ui.chat.input.AiChatInputToolbarFactory$UniqueIdAllocator");
        List<?> descriptors=(List<?>)call(factory,"buildControlDescriptors",List.of(progress),scope,allocator,predicate);
        check(descriptors.size()==1,"Progress disappeared");Object descriptor=descriptors.getFirst();
        check(Boolean.FALSE.equals(call(descriptor,"getEnabled")),"Parameter editable during refresh");
        String expected=List.of("Refreshing models…","Auto review…","High…","Fast mode…").get(i);
        check(expected.equals(call(descriptor,"getText")),"Wrong progress caption");check(call(descriptor,"getIcon")!=null,"No spinner");
        if(previous!=null){check(call(previous,"getId").equals(call(descriptor,"getId")),"Unstable descriptor ID");check(call(previous,"getIcon")!=call(descriptor,"getIcon"),"Spinner did not animate");}
        previous=descriptor;
      }
    }
    check(render(p,originals,-1,false).equals(originals),"Completion/failure did not restore controls");
    check(render(p,List.of(),-1,false).isEmpty(),"Completed refresh retained removed controls");
    call(type("kotlinx.coroutines.CoroutineScopeKt"),"cancel",scope,null);
  }
}
