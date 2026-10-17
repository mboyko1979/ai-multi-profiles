package dev.boyko.aiagentswitcher;
import java.util.*;
import static dev.boyko.aiagentswitcher.ProfilePresetsChecks.*;

/** Verify persisted model policies and refresh reconciliation independently of provider accounts. */
public final class ConcreteDefaultsChecks {
  static Object caps(String host,String ids,String initial,String effortValues) throws Exception {
    String choices=Arrays.stream(ids.split(",")).map(x->"{\"value\":\""+x+"\",\"name\":\""+x.toUpperCase()+"\"}").reduce((a,b)->a+","+b).orElse("");
    String effort=Arrays.stream(effortValues.split(",")).map(x->"{\"value\":\""+x+"\",\"name\":\""+x+"\"}").reduce((a,b)->a+","+b).orElse("");
    String option="{\"id\":\"effort\",\"operation\":\"config\",\"name\":\"Reasoning\",\"category\":\"thought_level\",\"type\":\"select\",\"currentValue\":\"high\",\"modelDefault\":\"low\",\"options\":["+effort+"]}";
    String models=Arrays.stream(ids.split(",")).map(x->"{\"modelId\":\""+x+"\",\"options\":["+option+"],\"warnings\":[]}").reduce((a,b)->a+","+b).orElse("");
    String payload="{\"host\":\""+host+"\",\"observedAt\":1,\"warnings\":[],\"options\":[{\"id\":\"model\",\"operation\":\"config\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\",\"currentValue\":\""+initial+"\",\"options\":["+choices+"]},"+option+"],\"models\":["+models+"]}";
    Object parser=Class.forName("com.google.gson.JsonParser");return call(singleton("ProfileConfiguration"),"parseCapabilities",call(call(parser,"parseString",payload),"getAsJsonObject"));
  }
  @SuppressWarnings("unchecked") static List<Object> models(Object d)throws Exception{return (List<Object>)call(d,"getModels");}
  static Object setting(Object m)throws Exception{return ((List<?>)call(m,"getSettings")).getFirst();}
  static List<String> ids(Object d)throws Exception{List<String> result=new ArrayList<>();for(Object m:models(d))result.add((String)call(m,"getModelId"));return result;}
  public static void main(String[] args)throws Exception {
    Class<?> entry=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$Entry");Object p=entry.getConstructor().newInstance();call(p,"setAgentId","fixture");call(p,"setId","test");
    Object modelDefaults=singleton("ProfileModelDefaults");
    // Missing recommendations must not hide the catalog or inherit session effort.
    for(String host:List.of("assistant","air")) {
      Object unknownProfile=entry.getConstructor().newInstance();call(unknownProfile,"setAgentId","arbitrary-provider");
      Object gson=Class.forName("com.google.gson.Gson").getConstructor().newInstance();
      Object template=caps(host,"a,b","a","low,high");
      Object metadata=call(call(gson,"toJsonTree",template),"getAsJsonObject");
      call(call(call(call(metadata,"getAsJsonArray","options"),"get",1),"getAsJsonObject"),"remove","modelDefault");
      Object first=call(call(call(metadata,"getAsJsonArray","models"),"get",0),"getAsJsonObject");
      call(call(call(call(first,"getAsJsonArray","options"),"get",0),"getAsJsonObject"),"remove","modelDefault");
      Object unknown=call(gson,"fromJson",metadata,template.getClass());
      Object loaded=call(modelDefaults,"refresh",unknownProfile,host,unknown);
      check(ids(loaded).equals(List.of("a","b")),"Missing recommendation hid catalog");
      check(((List<?>)call(models(loaded).getFirst(),"getSettings")).isEmpty(),"Unknown default inherited session value");
      check(call(setting(models(loaded).get(1)),"getValue").equals("low"),"Confirmed recommendation lost");
      check(!((List<?>)call(modelDefaults,"problems",loaded,"")).isEmpty(),"Missing choice allowed Apply");
      call(models(loaded).getFirst(),"setSettings",new ArrayList<>((List<?>)call(models(loaded).get(1),"getSettings")));
      check(((List<?>)call(modelDefaults,"problems",loaded,"")).isEmpty(),"Explicit supported choice rejected");
      Object refreshed=call(modelDefaults,"refresh",unknownProfile,host,unknown);
      check(call(setting(models(refreshed).getFirst()),"getValue").equals("low"),"Refresh erased explicit choice");
      call(modelDefaults,"resetModel",unknownProfile,refreshed,models(refreshed).getFirst());
      check(((List<?>)call(models(refreshed).getFirst(),"getSettings")).isEmpty(),"Reset invented default");
      // Reported case: all models lack recommendations; only the default stays
      // enabled and the user explicitly selects Medium before Refresh Models.
      Object grokTemplate=caps(host,"a,b","a","low,medium,high");
      Object grokMetadata=call(call(gson,"toJsonTree",grokTemplate),"getAsJsonObject");
      call(call(call(call(grokMetadata,"getAsJsonArray","options"),"get",1),"getAsJsonObject"),"remove","modelDefault");
      for(int index=0;index<2;index++) {
        Object model=call(call(call(grokMetadata,"getAsJsonArray","models"),"get",index),"getAsJsonObject");
        call(call(call(call(model,"getAsJsonArray","options"),"get",0),"getAsJsonObject"),"remove","modelDefault");
      }
      Object noRecommendations=call(gson,"fromJson",grokMetadata,grokTemplate.getClass());
      Object draft=entry.getConstructor().newInstance();call(draft,"setAgentId","fixture");
      Object values=call(modelDefaults,"refresh",draft,host,noRecommendations);
      check(((List<?>)call(modelDefaults,"missingValues",values,"")).size()==2,"Missing enabled models not identified");
      Class<?> settingClass=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$SessionSetting");
      Object medium=settingClass.getConstructor(String.class,String.class,String.class,String.class,String.class)
          .newInstance("fixture","config","effort","medium",host);
      call(models(values).getFirst(),"setSettings",new ArrayList<>(List.of(medium)));
      call(models(values).get(1),"setEnabled",false);
      check(((List<?>)call(modelDefaults,"missingValues",values,"")).isEmpty(),"Disabled model caused missing-default warning");
      values=call(modelDefaults,"refresh",draft,host,noRecommendations);
      check(call(setting(models(values).getFirst()),"getValue").equals("medium"),"Refresh lost manual Medium");
      check(((List<?>)call(modelDefaults,"missingValues",values,"")).isEmpty(),"Refresh warned about disabled model");
      check(((List<?>)call(modelDefaults,"problems",values,"")).isEmpty(),"Manual Medium blocked Apply after refresh");
      call(models(values).get(1),"setEnabled",true);
      check(((List<?>)call(modelDefaults,"missingValues",values,"")).size()==1,"Re-enabled model did not require a value");
      call(models(values).get(1),"setEnabled",false);
      call(modelDefaults,"resetModel",draft,values,models(values).getFirst());
      check(((List<?>)call(modelDefaults,"missingValues",values,"")).size()==1,"Reset did not restore missing-value warning");
    }
    check(call(modelDefaults,"get",p,"assistant")==null,"New profile has defaults");
    Object d=call(modelDefaults,"refresh",p,"assistant",caps("assistant","a,b","a","low,high"));
    check(call(d,"getDefaultModelId").equals("a"),"Load ignored agent default model");
    for(Object m:models(d))check(call(setting(m),"getValue").equals("low"),"Used inherited current value instead of model recommendation");
    call(modelDefaults,"refresh",p,"air",caps("air","a,b","b","low,high"));
    Object b=models(d).get(1);call(setting(b),"setValue","high");call(d,"setDefaultModelId","b");call(models(d).getFirst(),"setEnabled",false);Collections.swap(models(d),0,1);
    Object snapshot=call(p,"snapshot");Object copied=call(modelDefaults,"get",snapshot,"assistant");call(setting(models(copied).getFirst()),"setValue","low");
    check(call(setting(b),"getValue").equals("high"),"Draft alias changed original model settings");
    Class<?> serializer=Class.forName("com.intellij.util.xmlb.XmlSerializer");Object roundtrip=call(serializer,"deserialize",call(serializer,"serialize",p),entry);
    Object restored=call(modelDefaults,"get",roundtrip,"assistant");check(ids(restored).equals(List.of("b","a")),"XML lost order");check(call(setting(models(restored).getFirst()),"getValue").equals("high"),"XML lost per-model value");
    d=call(modelDefaults,"refresh",p,"assistant",caps("assistant","a,c,d,b","a","low,high"));
    check(ids(d).equals(List.of("c","d","b","a")),"New models not prepended or previous order changed");
    check(call(models(d).getFirst(),"getEnabled").equals(true),"New model disabled");check(call(models(d).get(3),"getEnabled").equals(false),"Refresh lost disabled model");check(call(d,"getDefaultModelId").equals("b"),"Refresh reset available default");check(call(setting(models(d).get(2)),"getValue").equals("high"),"Refresh reset explicit value");
    call(modelDefaults,"resetList",d);
    check(ids(d).equals(List.of("a","c","d","b")),"List reset did not restore catalog order");
    check(models(d).stream().allMatch(m->{try{return call(m,"getEnabled").equals(true);}catch(Exception e){throw new RuntimeException(e);}}),"List reset did not clear exclusions");
    check(call(d,"getDefaultModelId").equals("a"),"List reset did not restore default model");
    check(call(setting(models(d).get(3)),"getValue").equals("high"),"List reset changed model parameters");
    check(call(call(modelDefaults,"get",p,"air"),"getDefaultModelId").equals("b"),"List reset changed other client");
    d=call(modelDefaults,"refresh",p,"assistant",caps("assistant","a,c,d,b","a","low"));
    check(!((List<?>)call(modelDefaults,"problems",d,"")).isEmpty(),"Deprecated value allowed to save");
    call(modelDefaults,"resetModel",p,d,models(d).get(3));check(call(setting(models(d).get(3)),"getValue").equals("low"),"Reset model failed");
    d=call(modelDefaults,"refresh",p,"assistant",caps("assistant","a,c,d","a","low"));
    check(call(d,"getDefaultModelId").equals("a"),"Removed default did not reset to agent default");check(call(models(d).stream().filter(m->{try{return call(m,"getModelId").equals("a");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow(),"getEnabled").equals(true),"Reset default remained disabled");
    call(modelDefaults,"remove",p,"assistant");check(call(modelDefaults,"get",p,"assistant")==null,"Remove resurrected defaults");check(call(modelDefaults,"get",p,"air")!=null,"Remove changed other client");
    // Older installations stored false as a shared Fast mode default. Removing one
    // client's defaults must also clear this legacy source across Apply/XML reload.
    Object legacy=entry.getConstructor().newInstance();call(legacy,"setAgentId","fixture");
    Class<?> settingType=Class.forName("dev.boyko.aiagentswitcher.AgentProfiles$SessionSetting");
    Object obsolete=settingType.getConstructor(String.class,String.class,String.class,String.class,String.class)
        .newInstance("fixture","config","fast-mode","false","");
    call(legacy,"setAgentDefaults",new ArrayList<>(List.of(obsolete)));
    call(modelDefaults,"get",legacy,"assistant");call(modelDefaults,"get",legacy,"air");
    Object beforeRemoval=call(legacy,"snapshot");
    call(modelDefaults,"remove",legacy,"assistant");
    check((Boolean)call(modelDefaults,"changed",legacy,beforeRemoval,"assistant"),"Removal not detected as an edit");
    check(!(Boolean)call(modelDefaults,"changed",legacy,beforeRemoval,"air"),"Unchanged Air defaults block Assistant removal");
    Object airDefaults=call(modelDefaults,"get",legacy,"air");
    call(airDefaults,"setValidationJson","{}");
    check(!(Boolean)call(modelDefaults,"changed",legacy,beforeRemoval,"air"),"Validation observation counted as an edit");
    Object editedAir=call(legacy,"snapshot");
    call(setting(models(call(modelDefaults,"get",editedAir,"air")).getFirst()),"setValue","another-invalid-value");
    check((Boolean)call(modelDefaults,"changed",editedAir,beforeRemoval,"air"),"Edited invalid defaults escaped Apply validation");
    Object afterApply=call(serializer,"deserialize",call(serializer,"serialize",call(legacy,"snapshot")),entry);
    check(call(modelDefaults,"get",afterApply,"assistant")==null,"Legacy false resurrected removed Assistant defaults after Apply");
    check(call(modelDefaults,"get",afterApply,"air")!=null,"Removing legacy Assistant defaults erased Air");
    call(modelDefaults,"remove",afterApply,"air");
    check(call(modelDefaults,"get",afterApply,"assistant")==null && call(modelDefaults,"get",afterApply,"air")==null,"Legacy removal did not persist for both clients");
    System.out.println("PASS: concrete defaults XML/deep copies, model recommendations, prepend new models, preserved order/disabled/values, deprecated validation, resets and independent removal");
  }
}
