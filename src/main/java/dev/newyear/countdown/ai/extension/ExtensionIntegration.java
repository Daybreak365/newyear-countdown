package dev.newyear.countdown.ai.extension;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.*;
import dev.newyear.countdown.ai.script.api.McUtil;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Enabled only by JVM properties in the disposable, operator-owned test runner. */
public final class ExtensionIntegration {
    private ExtensionIntegration() {}
    public static boolean requested() { return System.getProperty("wishai.integration.jar") != null; }
    public static void run(WishEngine engine) {
        JsonObject report = new JsonObject(), assertions = new JsonObject();
        Path artifact = Path.of(System.getProperty("wishai.integration.jar"));
        String scriptId = "s-native-integration";
        FakePlayer player = null;
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, NativeExtension.class.getClassLoader())) {
            report.addProperty("sha256", NativeExtensions.hash(artifact));
            String name = System.getProperty("wishai.integration.class");
            try (NativeExtension module = (NativeExtension) Class.forName(name, true, loader).getConstructor().newInstance()) {
                var level = engine.server().overworld();
                level.setChunkForced(0, 0, true);
                level.getChunk(0, 0);
                player = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(scriptId.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "NativeTest"));
                player.snapTo(0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0.5);
                engine.addTestPlayer(player);
                engine.ui().setTestSink(player.getUUID(), ignored -> {});
                ScriptRecord rec = new ScriptRecord(); rec.id=scriptId;rec.name="Native integration";rec.owner=player.getUUID().toString();rec.ownerName="NativeTest";rec.dimension=McUtil.dimId(level);rec.anchor=new double[]{player.getX(),player.getY(),player.getZ()};
                rec.code="";engine.state().scripts.put(rec.id,rec);
                var activation=engine.scripts().activate(rec,false);
                assertions.addProperty("script_activation", activation.ok());
                if(!activation.ok())throw new IllegalStateException(activation.error());
                var operations=module.operations();
                assertions.addProperty("operation_manifest", operations!=null&&!operations.isEmpty()&&operations.size()<=32);
                collect(assertions,"unit/",module.selfTest());
                ScriptInstance inst=engine.scripts().instance(rec.id);
                collect(assertions,"world/",module.integrationTest(new ExtensionContext(engine,inst,inst.in)));
                engine.scripts().stop(rec.id,ScriptRecord.State.STOPPED,"integration teardown",true);
                assertions.addProperty("script_teardown",engine.scripts().instance(rec.id)==null);
                report.addProperty("passed",assertions.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));
            }
        } catch (Throwable e) {
            report.addProperty("passed",false);report.addProperty("error",e.getClass().getSimpleName()+": "+e.getMessage());
        } finally {
            if(engine.scripts().instance(scriptId)!=null)engine.scripts().stop(scriptId,ScriptRecord.State.STOPPED,"test cleanup",true);
            if(player!=null)engine.removeTestPlayer(player.getUUID());
            report.add("assertions",assertions);
            try { Files.writeString(Path.of(System.getProperty("wishai.integration.report")),report.toString()); }
            catch(Exception e){dev.newyear.countdown.NewYearCountdown.LOGGER.error("Cannot write native test report",e);}
            engine.server().halt(false);
        }
    }
    private static void collect(JsonObject target,String prefix,Map<String,Boolean> results) {
        if(results==null||results.isEmpty()||results.size()>128)throw new IllegalStateException("Tests need 1..128 assertions");
        results.forEach((name,pass)->target.addProperty(prefix+name,Boolean.TRUE.equals(pass)));
    }
}
