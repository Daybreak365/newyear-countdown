package dev.newyear.countdown.ai.extension;

import com.google.gson.*;
import dev.newyear.countdown.ai.*;
import dev.newyear.countdown.ai.script.*;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.*;
import dev.newyear.countdown.ai.script.lang.Values.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

/** Draft -> compile -> operator-requested isolated tests -> exact-hash approval -> load/stage. */
public final class NativeExtensions implements AutoCloseable {
    public static final class Draft {
        public String id,owner,name,className,source,description,hash="",status="DRAFT",message="",approvedBy="";
        public boolean restartRequired;
        public JsonObject resources=new JsonObject(),testReport=new JsonObject();
        public long created;
    }
    private record Loaded(NativeExtension module,URLClassLoader loader) {}
    private final WishEngine eng;
    private final Path root;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"WishAI-Extensions");t.setDaemon(true);return t;});
    private final Map<String,Loaded> loaded=new LinkedHashMap<>();
    private final Map<String,List<Runnable>> cleanups=new HashMap<>();
    private final Set<String> busy=ConcurrentHashMap.newKeySet();
    private volatile Process process;
    public NativeExtensions(WishEngine eng,Path root){this.eng=eng;this.root=root.toAbsolutePath().normalize();}
    public Draft draft(String owner,String name,String className,String source,String description,JsonObject resources) {
        if(owner==null)throw new IllegalArgumentException("An owner is required");
        if(!className.matches("wishai\\.generated\\.[A-Za-z_$][A-Za-z0-9_$]*"))throw new IllegalArgumentException("Class must be wishai.generated.YourClass");
        if(source.length()>64000||resources.toString().length()>32000||resources.size()>16)throw new IllegalArgumentException("Extension source/resources too large");
        if(eng.state().extensions.values().stream().filter(d->owner.equals(d.owner)).count()>=16)throw new IllegalArgumentException("At most 16 native drafts per player");
        if(busy.size()>=4)throw new IllegalArgumentException("Extension compile queue is full");
        Draft d=new Draft();d.id="ext-"+UUID.randomUUID().toString().substring(0,12);d.owner=owner;d.name=McUtil.clean(name,60);d.className=className;d.source=source;d.description=McUtil.clean(description,600);d.resources=resources.deepCopy();d.restartRequired=resources.has("fabric.mod.json");d.created=System.currentTimeMillis();
        for(String resource:resources.keySet())safeResource(resource);
        eng.state().extensions.put(d.id,d);submit(d,"COMPILING",()->compile(d));return d;
    }
    public JsonObject status(String owner,String id){
        Draft d=eng.state().extensions.get(id);if(d==null||owner!=null&&!owner.equals(d.owner))throw new IllegalArgumentException("Unknown extension");return new Gson().toJsonTree(d).getAsJsonObject();
    }
    public static void safeResource(String name){if(!name.matches("[a-zA-Z0-9_./-]+\\.(json|txt|properties)")||name.contains("..")||name.startsWith("/")||name.startsWith("META-INF/"))throw new IllegalArgumentException("Invalid resource path: "+name);}
    private Path directory(Draft d){if(!d.id.matches("ext-[a-f0-9-]{12}"))throw new IllegalArgumentException("Invalid extension id");return root.resolve(d.id);}
    private void submit(Draft d,String state,java.util.concurrent.Callable<String> work){
        if(!busy.add(d.id))throw new IllegalArgumentException("Extension job already running");d.status=state;d.message="";eng.writeAsync();
        worker.submit(()->{String result,message="";try{result=work.call();}catch(Exception ex){result="FAILED";message=ex.getClass().getSimpleName()+": "+ex.getMessage();}finally{busy.remove(d.id);}String end=result,note=message;
            if(eng.server().isRunning())eng.server().execute(()->{d.status=end;d.message=McUtil.clean(note,2000);eng.writeAsync();eng.notifyAdmins("확장 "+d.id+": "+end+(note.isEmpty()?"":" — "+McUtil.clean(note,180)));});});
    }
    private String compile(Draft d)throws Exception{
        Path dir=directory(d);Files.createDirectories(dir);Path source=dir.resolve(d.className.substring(d.className.lastIndexOf('.')+1)+".java");Files.writeString(source,d.source,StandardCharsets.UTF_8);Path classes=dir.resolve("classes");Files.createDirectories(classes);
        List<String> args=List.of("-proc:none","-encoding","UTF-8","--release","25","-classpath",classpath(),"-d",classes.toString(),source.toString());
        Path argfile=dir.resolve("compile.args");Files.writeString(argfile,args.stream().map(NativeExtensions::quoteArg).collect(java.util.stream.Collectors.joining("\n")));
        run(List.of(javaTool("javac"),"-J-Xmx256m","@"+argfile),dir,60,"compile.log");
        Path jar=dir.resolve("extension.jar");try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar))){
            try(var files=Files.walk(classes)){for(Path p:files.filter(Files::isRegularFile).sorted().toList()){String name=classes.relativize(p).toString().replace('\\','/');out.putNextEntry(new JarEntry(name));Files.copy(p,out);out.closeEntry();}}
            for(var entry:d.resources.entrySet()){out.putNextEntry(new JarEntry(entry.getKey()));out.write(entry.getValue().getAsString().getBytes(StandardCharsets.UTF_8));out.closeEntry();}
        }
        d.hash=hash(jar);return "COMPILED";
    }
    public void test(String id){
        Draft d=require(id);if(!Set.of("COMPILED","TESTED","FAILED","NEEDS_INTEGRATION_TEST").contains(d.status)||d.hash.isEmpty())throw new IllegalArgumentException("Compile the extension first");verifyHash(d);
        List<String> configured=AiConfig.get().extensionTestCommand;
        if(configured==null||configured.isEmpty()){d.status="NEEDS_INTEGRATION_TEST";d.message="Configure an OS-isolated extensionTestCommand; generated code has not been executed.";eng.writeAsync();return;}
        List<String> runner=List.copyOf(configured);
        submit(d,"TESTING",()->{
            Path dir=directory(d),jar=dir.resolve("extension.jar");
            Path integration=dir.resolve("integration-report.json");Files.deleteIfExists(integration);
            List<String> command=new ArrayList<>();for(String part:runner)command.add(part.replace("{jar}",jar.toString()).replace("{report}",integration.toString()).replace("{hash}",d.hash).replace("{class}",d.className).replace("{restart}",Boolean.toString(d.restartRequired)));
            run(command,dir,180,"integration.log");if(Files.size(integration)>131072)throw new IOException("Integration report too large");JsonObject result=JsonParser.parseString(Files.readString(integration)).getAsJsonObject();
            validateReport(result,d.hash);
            d.testReport.add("integration",result);verifyHash(d);return "TESTED";
        });
    }
    public void approve(String id,String expectedHash,String operator){Draft d=require(id);if(!d.status.equals("TESTED")||!d.hash.equals(expectedHash))throw new IllegalArgumentException("A tested artifact and its exact SHA-256 are required");verifyHash(d);d.approvedBy=operator;
        if(d.restartRequired){d.status="READY_FOR_RESTART";eng.writeAsync();return;}
        load(d);eng.writeAsync();
    }
    public Path stage(String id){Draft d=require(id);if(!d.status.equals("READY_FOR_RESTART")||d.approvedBy.isEmpty())throw new IllegalArgumentException("Approve tested restart-required module first");verifyHash(d);
        try{Path target=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods").resolve("wishai-"+d.id+".jar");Files.createDirectories(target.getParent());Files.copy(directory(d).resolve("extension.jar"),target);d.status="STAGED_RESTART";eng.writeAsync();return target;}catch(IOException e){throw new IllegalStateException(e);}}
    private void load(Draft d){
        URLClassLoader loader=null;NativeExtension module=null;try{verifyHash(d);loader=new URLClassLoader(new URL[]{directory(d).resolve("extension.jar").toUri().toURL()},NativeExtension.class.getClassLoader());module=(NativeExtension)Class.forName(d.className,true,loader).getConstructor().newInstance();
            var operations=module.operations();if(operations==null||operations.isEmpty()||operations.size()>32||operations.keySet().stream().anyMatch(k->!k.matches("[a-zA-Z0-9_]{1,48}")))throw new IllegalStateException("Invalid operation manifest");loaded.put(d.id,new Loaded(module,loader));d.status=d.restartRequired?"INSTALLED_RESTART":"INSTALLED";d.message="";
        }catch(Exception e){if(module!=null)try{module.close();}catch(Exception ignored){}if(loader!=null)try{loader.close();}catch(IOException ignored){}d.status="FAILED";throw new IllegalStateException("Native extension could not load: "+e.getMessage(),e);}}
    public void restore(){
        for(Draft d:eng.state().extensions.values()){
            if(Set.of("COMPILING","TESTING").contains(d.status)){d.status="FAILED";d.message="Server stopped during job";continue;}
            if(!eng.aiEnabled()||loaded.containsKey(d.id)||d.approvedBy.isEmpty()||!Set.of("INSTALLED","INSTALLED_RESTART","STAGED_RESTART").contains(d.status))continue;
            try{
                if(d.restartRequired){String modId=JsonParser.parseString(d.resources.get("fabric.mod.json").getAsString()).getAsJsonObject().get("id").getAsString();if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modId)){d.message="Restart with the approved mod installed";continue;}}
                load(d);
            }catch(RuntimeException e){d.message=e.getMessage();}
        }
    }
    public void disable(String id){
        Draft d=require(id);if(busy.contains(id))throw new IllegalStateException("Wait for the extension job to finish");
        if(d.restartRequired&&Set.of("STAGED_RESTART","INSTALLED_RESTART").contains(d.status)){
            Path staged=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods").resolve("wishai-"+d.id+".jar");
            try{if(Files.exists(staged)){if(!hash(staged).equals(d.hash))throw new IllegalStateException("Staged artifact changed; inspect manually");Files.delete(staged);}}
            catch(IOException ex){throw new IllegalStateException("Cannot remove staged artifact",ex);}
            for(ScriptRecord r:new ArrayList<>(eng.state().scripts.values()))if(RuntimeState.section(r,"extensions").has(id)&&(r.state.live()||r.state==ScriptRecord.State.SUSPENDED))eng.scripts().stop(r.id,ScriptRecord.State.STOPPED,"native extension removal",false);
            Loaded old=loaded.remove(id);if(old!=null){try{old.module.close();}catch(Exception ignored){}try{old.loader.close();}catch(IOException ignored){}}
            d.status="REMOVAL_RESTART";d.message="Restart all affected clients and server to finish removal";eng.writeAsync();return;
        }
        for(ScriptRecord r:new ArrayList<>(eng.state().scripts.values()))if(RuntimeState.section(r,"extensions").has(id)&&(r.state.live()||r.state==ScriptRecord.State.SUSPENDED))eng.scripts().stop(r.id,ScriptRecord.State.STOPPED,"native extension disabled",false);
        Loaded old=loaded.remove(id);if(old!=null){try{old.module.close();}catch(Exception ignored){}try{old.loader.close();}catch(IOException ignored){}}
        d.status="DISABLED";eng.writeAsync();
    }
    public void onScriptStop(String script,Runnable cleanup){List<Runnable> list=cleanups.computeIfAbsent(script,k->new ArrayList<>());if(list.size()>=128)throw new IllegalStateException("Native cleanup limit");list.add(cleanup);}
    public void cleanup(String script){List<Runnable> list=cleanups.remove(script);if(list!=null)for(int i=list.size()-1;i>=0;i--)try{list.get(i).run();}catch(RuntimeException e){dev.newyear.countdown.NewYearCountdown.LOGGER.warn("[WishAI] native cleanup failed",e);}}
    public static ApiObject api(ScriptInstance inst){return new ApiObject("extensions").fn("list","() → installed module operation documentation",(in,s,a)->{JsObject out=new JsObject();for(var entry:WishEngine.get().extensions().loaded.entrySet())out.put(entry.getKey(),Stdlib.fromJson(new Gson().toJsonTree(entry.getValue().module.operations()),in,null,0));return out;})
        .fn("call","(installedId,operation,args) → JSON result. Native module must have passed tests and exact-hash operator approval.",(in,s,a)->{
            NativeExtensions manager=WishEngine.get().extensions();
            String id=Args.str(in,a,0,"id"),operation=Args.str(in,a,1,"operation");
            Loaded module=manager.loaded.get(id);if(module==null)throw in.error("Extension is not installed: "+id);
            if(!module.module.operations().containsKey(operation))throw in.error("Unknown native operation");
            JsonObject arguments=Stdlib.toJson(Args.opts(a,2)).getAsJsonObject();if(arguments.toString().length()>16000)throw in.error("Extension arguments too large");
            JsonObject pins=RuntimeState.section(inst.rec,"extensions");String hash=manager.require(id).hash;
            if(pins.has(id)&&!pins.get(id).getAsString().equals(hash))throw in.error("Native dependency changed; validate this script again");
            JsonObject result;
            if(inst.dry){result=module.module.preview(operation,arguments);inst.effect("native "+id+"."+operation+": "+result);}
            else{pins.addProperty(id,hash);result=module.module.invoke(operation,new ExtensionContext(manager.eng,inst,in),arguments);}
            if(result==null||result.toString().length()>32000)throw in.error("Invalid native result");return Stdlib.fromJson(result,in,null,0);
        });}
    private Draft require(String id){Draft d=eng.state().extensions.get(id);if(d==null)throw new IllegalArgumentException("Unknown extension: "+id);return d;}
    private void verifyHash(Draft d){try{if(!hash(directory(d).resolve("extension.jar")).equals(d.hash))throw new IllegalStateException("Artifact hash changed; recompile and test");}catch(IOException e){throw new IllegalStateException(e);}}
    public static void validateReport(JsonObject report,String hash){
        if(!report.has("passed")||!report.get("passed").isJsonPrimitive()||!report.getAsJsonPrimitive("passed").isBoolean()||!report.get("passed").getAsBoolean()||!report.has("sha256")||!report.get("sha256").getAsString().equals(hash)||!report.has("assertions")||!report.get("assertions").isJsonObject()||report.getAsJsonObject("assertions").size()==0||report.getAsJsonObject("assertions").entrySet().stream().anyMatch(e->!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isBoolean()||!e.getValue().getAsBoolean()))throw new IllegalStateException("Integration report failed or is for another artifact");
    }
    public static String hash(Path file)throws IOException{if(Files.size(file)>16*1024*1024)throw new IOException("Native artifact exceeds 16 MiB");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String quoteArg(String value){return "\""+value.replace('\\','/').replace("\"","\\\"")+"\"";}
    private static String javaTool(String name){return Path.of(System.getProperty("java.home"),"bin",name+(System.getProperty("os.name").startsWith("Windows")?".exe":"")).toString();}
    private static String classpath(){Set<String> paths=new LinkedHashSet<>(Arrays.asList(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        try{for(Path p:net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getClassPath())paths.add(p.toAbsolutePath().toString());}catch(RuntimeException ignored){}
        for(var mod:net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods())for(Path root:mod.getRootPaths())try{
            URI uri=root.toUri();if("jar".equals(uri.getScheme())){String nested=uri.getRawSchemeSpecificPart();int end=nested.indexOf("!/");if(end>=0)paths.add(Path.of(URI.create(nested.substring(0,end))).toString());}
            else if("file".equals(uri.getScheme()))paths.add(root.toAbsolutePath().toString());
        }catch(RuntimeException ignored){}
        for(Class<?> type:List.of(NativeExtension.class,JsonObject.class,net.minecraft.server.MinecraftServer.class))try{paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());}catch(Exception ignored){}
        return String.join(File.pathSeparator,paths);}
    private void run(List<String> command,Path dir,int timeout,String log)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);Map<String,String> old=new HashMap<>(builder.environment());builder.environment().clear();for(String key:List.of("SystemRoot","WINDIR","PATH","JAVA_HOME","LANG","DOCKER_HOST","DOCKER_CONTEXT","DOCKER_CONFIG"))if(old.containsKey(key))builder.environment().put(key,old.get(key));builder.environment().put("TEMP",dir.toString());builder.environment().put("TMP",dir.toString());
        Process child=builder.start();process=child;
        Thread drain=Thread.ofVirtual().start(()->{try(var input=child.getInputStream();var output=Files.newOutputStream(dir.resolve(log))){byte[] buf=new byte[8192];int count,total=0;while((count=input.read(buf))!=-1){int keep=Math.min(count,Math.max(0,262144-total));if(keep>0)output.write(buf,0,keep);total+=keep;}}catch(IOException ignored){}});
        try{if(!child.waitFor(timeout,TimeUnit.SECONDS))throw new TimeoutException("Extension process exceeded "+timeout+" seconds");drain.join(2000);if(child.exitValue()!=0){String output=Files.readString(dir.resolve(log));throw new IOException("Process failed: "+output.substring(0,Math.min(3000,output.length())));}}finally{child.descendants().forEach(ProcessHandle::destroyForcibly);if(child.isAlive())child.destroyForcibly();process=null;}}
    public void unloadModules(){for(String script:new ArrayList<>(cleanups.keySet()))cleanup(script);for(Loaded module:loaded.values()){try{module.module.close();}catch(Exception ignored){}try{module.loader.close();}catch(IOException ignored){}}loaded.clear();}
    @Override public void close(){worker.shutdownNow();Process p=process;if(p!=null){p.descendants().forEach(ProcessHandle::destroyForcibly);p.destroyForcibly();}unloadModules();}
}
