import java.nio.file.*;
import java.util.*;

/** Uses the installed IntelliJ Platform's native startup installer serialization. */
public class PreparePluginDeployment {
  public static void main(String[] args) throws Exception {
    Path queue=Path.of(args[0]), output=Path.of(args[1]), archive=Path.of(args[2]);
    Path plugins=Path.of(args[3]).toAbsolutePath().normalize(), target=plugins.resolve("ai-multi-profiles");
    if(!target.getParent().equals(plugins))throw new IllegalStateException("Invalid plugin target");
    if(Files.isSymbolicLink(plugins)||Files.isSymbolicLink(target))throw new IllegalStateException("Redirected plugins directory");
    Class<?> manager=Class.forName("com.intellij.ide.startup.StartupActionScriptManager");
    List<?> previous=(List<?>)manager.getMethod("loadActionScript",Path.class).invoke(null,queue);
    boolean migrateLegacy=Arrays.asList(args).contains("--migrate-legacy");
    Path legacy=plugins.resolve("AIMulti");
    if(Files.isSymbolicLink(legacy))throw new IllegalStateException("Redirected legacy plugin directory");
    Path superseded=args.length>4 && !args[4].equals("--migrate-legacy")?Path.of(args[4]).toAbsolutePath().normalize():null;
    if(superseded!=null && (!superseded.getParent().equals(queue.toAbsolutePath().normalize().getParent()) ||
        !superseded.getFileName().toString().matches("ai-multi-profiles-\\d+\\.\\d+\\.\\d+-dev\\.\\d+-deploy\\.zip")))
      throw new IllegalStateException("Invalid superseded development archive");
    List<Object> commands=new ArrayList<>();int removedDelete=0,removedUnzip=0;
    for(Object command:previous){
      String type=command.getClass().getSimpleName();
      if(migrateLegacy && ((type.equals("DeleteCommand") || type.equals("UnzipCommand")) && fieldPath(command,"mySource").equals(legacy) ||
          command.toString().contains(legacy.toString())))
        throw new IllegalStateException("An update of the legacy plugin is queued; refusing migration");
      if(superseded!=null && type.equals("DeleteCommand") && fieldPath(command,"mySource").equals(target)){removedDelete++;continue;}
      if(superseded!=null && type.equals("UnzipCommand") && fieldPath(command,"mySource").equals(superseded)){
        if(!fieldPath(command,"myDestination").equals(plugins) || field(command,"myFilenameFilter")!=null)
          throw new IllegalStateException("Unexpected pending AI Multi installation");
        removedUnzip++;continue;
      }
      if(command.toString().contains(target.toString()))throw new IllegalStateException("An unrecognized update of AI Multi is queued; refusing replacement");
      commands.add(command);
    }
    if(removedDelete!=removedUnzip || removedDelete>1)throw new IllegalStateException("Pending AI Multi update is not one complete installation pair");
    if(migrateLegacy)commands.add(Class.forName(manager.getName()+"$DeleteCommand").getConstructor(Path.class).newInstance(legacy));
    commands.add(Class.forName(manager.getName()+"$DeleteCommand").getConstructor(Path.class).newInstance(target));
    commands.add(Class.forName(manager.getName()+"$UnzipCommand").getConstructor(Path.class,Path.class).newInstance(archive,plugins));
    manager.getMethod("saveActionScript",List.class,Path.class).invoke(null,commands,output);
    List<?> verified=(List<?>)manager.getMethod("loadActionScript",Path.class).invoke(null,output);
    if(verified.size()!=commands.size())throw new IllegalStateException("Installer queue serialization failed");
    System.out.println("PASS: native installer queue verified; unrelated updates preserved");
  }
  private static Object field(Object command,String name)throws Exception{
    var field=command.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(command);
  }
  private static Path fieldPath(Object command,String name)throws Exception{return Path.of((String)field(command,name)).toAbsolutePath().normalize();}
}
