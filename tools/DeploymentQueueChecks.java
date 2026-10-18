import java.nio.file.*;
import java.util.*;

/** Checks native queue serialization only; installer commands are never executed. */
public class DeploymentQueueChecks {
  public static void main(String[] args)throws Exception{
    Path root=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(root);
    Path plugins=root.resolve("plugins"),queue=root.resolve("action.script"),draft=root.resolve("draft.script");
    Path oldArchive=root.resolve("ai-multi-profiles-0.2.0-dev.15-deploy.zip"),archive=root.resolve("ai-multi-profiles-0.2.0-dev.16-deploy.zip");
    Files.createDirectories(plugins);
    Class<?> manager=Class.forName("com.intellij.ide.startup.StartupActionScriptManager");
    Class<?> delete=Class.forName(manager.getName()+"$DeleteCommand"),unzip=Class.forName(manager.getName()+"$UnzipCommand");
    Object other=delete.getConstructor(Path.class).newInstance(plugins.resolve("Unrelated"));
    Object remove=delete.getConstructor(Path.class).newInstance(plugins.resolve("ai-multi-profiles"));
    Object install=unzip.getConstructor(Path.class,Path.class).newInstance(oldArchive,plugins);
    var save=manager.getMethod("saveActionScript",List.class,Path.class);var load=manager.getMethod("loadActionScript",Path.class);
    save.invoke(null,List.of(other,remove,install),queue);byte[] before=Files.readAllBytes(queue);
    PreparePluginDeployment.main(new String[]{queue.toString(),draft.toString(),archive.toString(),plugins.toString(),oldArchive.toString()});
    List<?> after=(List<?>)load.invoke(null,draft);
    if(after.size()!=3 || !after.get(0).toString().equals(other.toString()) || !after.get(2).toString().contains(archive.toString()))throw new AssertionError("Replacement must preserve unrelated commands and stage latest version");
    if(!Arrays.equals(before,Files.readAllBytes(queue)))throw new AssertionError("Preparation must not modify the original queue");
    save.invoke(null,List.of(other,remove),queue);
    try{PreparePluginDeployment.main(new String[]{queue.toString(),draft.toString(),archive.toString(),plugins.toString(),oldArchive.toString()});throw new AssertionError("Incomplete pair must be rejected");}catch(IllegalStateException expected){}
    save.invoke(null,List.of(other),queue);
    PreparePluginDeployment.main(new String[]{queue.toString(),draft.toString(),archive.toString(),plugins.toString()});
    if(((List<?>)load.invoke(null,draft)).size()!=3)throw new AssertionError("Ordinary installation must preserve unrelated commands");
    PreparePluginDeployment.main(new String[]{queue.toString(),draft.toString(),archive.toString(),plugins.toString(),"--migrate-legacy"});
    List<?> migration=(List<?>)load.invoke(null,draft);
    if(migration.size()!=4 || !migration.get(0).toString().equals(other.toString()) ||
        !migration.get(1).toString().contains(plugins.resolve("AIMulti").toString()) ||
        !migration.get(3).toString().contains(archive.toString()))throw new AssertionError("Migration must remove the old identity before installing the new one");
    Object legacyRemove=delete.getConstructor(Path.class).newInstance(plugins.resolve("AIMulti"));
    save.invoke(null,List.of(other,legacyRemove),queue);
    byte[] legacyBefore=Files.readAllBytes(queue);
    try{PreparePluginDeployment.main(new String[]{queue.toString(),draft.toString(),archive.toString(),plugins.toString(),"--migrate-legacy"});throw new AssertionError("A queued legacy update must be rejected");}catch(IllegalStateException expected){}
    if(!Arrays.equals(legacyBefore,Files.readAllBytes(queue)))throw new AssertionError("Rejected migration must preserve the original queue");
    System.out.println("PASS: update replacement, unrelated commands, incomplete-pair rejection, ordinary installation and explicit legacy migration; no installer action executed");
  }
}
