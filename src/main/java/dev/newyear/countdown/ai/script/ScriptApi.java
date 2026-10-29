package dev.newyear.countdown.ai.script;

import com.mojang.math.Transformation;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.api.ApiObject;
import dev.newyear.countdown.ai.script.api.Args;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.api.PlayerRef;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.Callable;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.StatusEntry;
import dev.newyear.countdown.ai.wish.Wish;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static dev.newyear.countdown.ai.script.lang.Values.UNDEFINED;

/**
 * 스크립트 전역 API. 각 스크립트 인스턴스마다 새로 만들어 그 인스턴스에 묶는다.
 * 여기 정의된 이름이 곧 AI 가 쓸 수 있는 "Minecraft 프로그래밍 환경"이다.
 */
public final class ScriptApi {
    private ScriptApi() {}

    /** 정적 검사기용: 전역 이름과 그 멤버. */
    public static final Map<String, List<String>> HOST_MEMBERS = new LinkedHashMap<>();
    public static final List<String> GLOBAL_FUNCTIONS = List.of("on", "once", "off", "after", "every", "cancel", "timer",
            "cancelTimer", "emit", "region", "removeRegion", "now", "tick", "random", "randomInt", "chance", "pick", "shuffle",
            "pos", "offset", "distance", "seconds", "minutes", "log");
    public static final List<String> GLOBAL_OBJECTS = List.of("world", "players", "entities", "fx", "ui", "quests", "memory",
            "wish", "script", "ai", "state", "owner");

    public static java.util.Set<String> globalNames() {
        java.util.Set<String> s = new java.util.HashSet<>(GLOBAL_FUNCTIONS);
        s.addAll(GLOBAL_OBJECTS);
        return s;
    }

    private static MinecraftServer server() {
        WishEngine e = WishEngine.get();
        if (e == null) throw new ScriptError(ScriptError.Kind.RUNTIME, "Wish engine is not running", 0, 0);
        return e.server();
    }

    private static ScriptError deny(String msg) {
        return new ScriptError(ScriptError.Kind.PERMISSION, msg, 0, 0);
    }

    private static ServerLevel levelOf(ScriptInstance inst, JsObject opts) {
        String dim = Args.optStr(opts, "dimension", inst.rec.dimension);
        ServerLevel l = McUtil.level(server(), dim);
        if (l == null) throw deny("Unknown dimension " + dim);
        return l;
    }

    private static ServerPlayer playerArg(Interpreter in, Object v) {
        if (v instanceof PlayerRef r) {
            ServerPlayer p = r.live();
            if (p == null) throw in.error(r.name + " is offline");
            return p;
        }
        if (v instanceof String s) {
            ServerPlayer p = server().getPlayerList().getPlayerByName(s);
            if (p == null) throw in.error("No online player named " + s);
            return p;
        }
        throw in.error("Expected a player (got " + Interpreter.typeName(v) + ")");
    }

    /** 플레이어 하나 또는 'all' (전체). 오프라인이면 빈 목록. */
    private static List<ServerPlayer> targets(Interpreter in, Object v) {
        List<ServerPlayer> out = new ArrayList<>();
        if ("all".equals(v)) {
            out.addAll(server().getPlayerList().getPlayers());
        } else if (v instanceof JsArray arr) {
            for (Object o : arr.list) if (o instanceof PlayerRef r && r.live() != null) out.add(r.live());
        } else if (v instanceof PlayerRef r) {
            if (r.live() != null) out.add(r.live());
        } else {
            out.add(playerArg(in, v));
        }
        return out;
    }

    // =====================================================================================

    public static void install(ScriptInstance inst, Wish wish) {
        Interpreter in = inst.in;
        ScriptRecord rec = inst.rec;

        // ---------------- 소유자·상태
        PlayerRef ownerRef = rec.owner == null ? null : new PlayerRef(UUID.fromString(rec.owner), rec.ownerName == null ? "?" : rec.ownerName);
        in.define("owner", ownerRef);
        in.define("state", inst.state);

        // ---------------- 이벤트
        in.define("on", new NativeFn("on", (i, s, a) -> subscribe(inst, i, a, false)));
        in.define("once", new NativeFn("once", (i, s, a) -> subscribe(inst, i, a, true)));
        in.define("off", new NativeFn("off", (i, s, a) -> {
            int id = (int) Args.num(a, 0, -1);
            for (List<ScriptInstance.Listener> l : inst.listeners.values()) l.removeIf(x -> x.id() == id);
            return UNDEFINED;
        }));
        in.define("emit", new NativeFn("emit", (i, s, a) -> {
            String name = MemoryText.limit(Args.str(i, a, 0, "event name"), 64);
            JsObject ev = new JsObject();
            ev.put("name", name);
            ev.put("data", Args.get(a, 1));
            ev.put("source", rec.id);
            if (inst.dry) { inst.effect("emit custom event " + name); return true; }
            WishEngine.get().scripts().queueCustom(ev);
            return true;
        }));

        // ---------------- 타이머
        in.define("after", new NativeFn("after", (i, s, a) -> addTimer(inst, i, Args.num(i, a, 0, "ticks"), Args.fn(i, a, 1, "callback"), false)));
        in.define("every", new NativeFn("every", (i, s, a) -> addTimer(inst, i, Args.num(i, a, 0, "ticks"), Args.fn(i, a, 1, "callback"), true)));
        in.define("cancel", new NativeFn("cancel", (i, s, a) -> {
            int id = (int) Args.num(a, 0, -1);
            for (ScriptInstance.Timer t : inst.timers) if (t.id == id) t.cancelled = true;
            return UNDEFINED;
        }));
        in.define("timer", new NativeFn("timer", (i, s, a) -> {
            String name = MemoryText.limit(Args.str(i, a, 0, "timer name"), 48);
            long ticks = (long) Math.max(1, Math.min(20L * 60 * 60 * 24 * 7, Args.num(i, a, 1, "ticks")));
            rec.timers.removeIf(t -> t.name.equals(name));
            if (rec.timers.size() >= 32) throw new ScriptError(ScriptError.Kind.BUDGET, "Too many named timers (max 32)", 0, 0);
            ScriptRecord.NamedTimer t = new ScriptRecord.NamedTimer();
            t.name = name;
            t.fireTick = WishEngine.get().tick() + ticks;
            Object data = Args.get(a, 2);
            if (data instanceof JsObject jo) t.data = dev.newyear.countdown.ai.script.lang.Stdlib.toJson(jo).getAsJsonObject();
            rec.timers.add(t);
            if (inst.dry) inst.effect("named timer '" + name + "' in " + ticks + " ticks");
            return name;
        }));
        in.define("cancelTimer", new NativeFn("cancelTimer", (i, s, a) -> rec.timers.removeIf(t -> t.name.equals(Args.str(a, 0, "")))));

        // ---------------- 영역
        in.define("region", new NativeFn("region", (i, s, a) -> {
            String name = MemoryText.limit(Args.str(i, a, 0, "region name"), 48);
            double[] c = Args.pos(i, Args.get(a, 1), "region center");
            double r = Math.max(1, Math.min(64, Args.num(i, a, 2, "radius")));
            JsObject o = Args.opts(a, 3);
            if (inst.regions.size() >= 16 && !inst.regions.containsKey(name)) throw new ScriptError(ScriptError.Kind.BUDGET, "Too many regions (max 16)", 0, 0);
            String dim = Args.optStr(o, "dimension", rec.dimension);
            inst.regions.put(name, new ScriptInstance.Region(name, dim, c[0], c[1], c[2], r, Math.max(1, Math.min(128, Args.optNum(o, "height", 8)))));
            return name;
        }));
        in.define("removeRegion", new NativeFn("removeRegion", (i, s, a) -> inst.regions.remove(Args.str(a, 0, "")) != null));

        // ---------------- 유틸
        in.define("now", new NativeFn("now", (i, s, a) -> (double) System.currentTimeMillis()));
        in.define("tick", new NativeFn("tick", (i, s, a) -> (double) WishEngine.get().tick()));
        in.define("random", new NativeFn("random", (i, s, a) -> {
            double lo = Args.num(a, 0, 0), hi = Args.num(a, 1, a.isEmpty() ? 1 : lo + 1);
            if (a.size() == 1) { hi = lo; lo = 0; }
            return lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
        }));
        in.define("randomInt", new NativeFn("randomInt", (i, s, a) -> {
            long lo = (long) Args.num(i, a, 0, "min"), hi = (long) Args.num(i, a, 1, "max");
            if (hi < lo) { long t = lo; lo = hi; hi = t; }
            return (double) (lo + ThreadLocalRandom.current().nextLong(hi - lo + 1));
        }));
        in.define("chance", new NativeFn("chance", (i, s, a) -> ThreadLocalRandom.current().nextDouble() < Args.num(a, 0, 0.5)));
        in.define("pick", new NativeFn("pick", (i, s, a) -> {
            if (Args.get(a, 0) instanceof JsArray arr && !arr.list.isEmpty()) return arr.list.get(ThreadLocalRandom.current().nextInt(arr.list.size()));
            return UNDEFINED;
        }));
        in.define("shuffle", new NativeFn("shuffle", (i, s, a) -> {
            if (Args.get(a, 0) instanceof JsArray arr) { java.util.Collections.shuffle(arr.list); return arr; }
            return UNDEFINED;
        }));
        in.define("pos", new NativeFn("pos", (i, s, a) -> Args.posObj(Args.num(i, a, 0, "x"), Args.num(i, a, 1, "y"), Args.num(i, a, 2, "z"))));
        in.define("offset", new NativeFn("offset", (i, s, a) -> {
            double[] p = Args.pos(i, Args.get(a, 0), "position");
            return Args.posObj(p[0] + Args.num(a, 1, 0), p[1] + Args.num(a, 2, 0), p[2] + Args.num(a, 3, 0));
        }));
        in.define("distance", new NativeFn("distance", (i, s, a) -> {
            double[] p = Args.pos(i, Args.get(a, 0), "a"), q = Args.pos(i, Args.get(a, 1), "b");
            double dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }));
        in.define("seconds", new NativeFn("seconds", (i, s, a) -> Args.num(a, 0, 1) * 20));
        in.define("minutes", new NativeFn("minutes", (i, s, a) -> Args.num(a, 0, 1) * 1200));
        in.define("log", in.getGlobal("print"));

        // ---------------- 객체들
        define(in, buildWorld(inst));
        define(in, buildPlayers(inst));
        define(in, buildEntities(inst));
        define(in, buildFx(inst));
        define(in, buildUi(inst));
        define(in, buildQuests(inst));
        define(in, buildMemory(inst));
        define(in, buildWish(inst, wish));
        define(in, buildScript(inst));
        define(in, buildAi(inst));
    }

    private static void define(Interpreter in, ApiObject o) {
        String name = o.typeName();
        in.define(name, o);
        HOST_MEMBERS.putIfAbsent(name, new ArrayList<>(o.memberNames()));
        DOCS.putIfAbsent(name, o.docs());
    }

    /** API 문서 (get_script_api 도구가 보여 준다). */
    public static final Map<String, Map<String, String>> DOCS = new LinkedHashMap<>();

    // =====================================================================================
    // 이벤트·타이머

    private static Object subscribe(ScriptInstance inst, Interpreter in, List<Object> a, boolean once) {
        String ev = Args.str(in, a, 0, "event name");
        if (!EventTypes.ALL.containsKey(ev)) {
            throw in.error("Unknown event '" + ev + "'. Events: " + String.join(", ", EventTypes.ALL.keySet()));
        }
        Callable fn;
        JsObject filter = null;
        if (Args.get(a, 1) instanceof Callable c) {
            fn = c;
            if (Args.get(a, 2) instanceof JsObject f) filter = f;
        } else if (Args.get(a, 1) instanceof JsObject f && Args.get(a, 2) instanceof Callable c) {
            filter = f;
            fn = c;
        } else {
            throw in.error("on(event, handler, filter?) needs a function handler");
        }
        if (inst.listenerCount() >= AiConfig.get().scriptMaxListeners) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Too many event listeners (max " + AiConfig.get().scriptMaxListeners + ")", 0, 0);
        }
        int id = ++inst.seq;
        inst.listeners.computeIfAbsent(ev, k -> new ArrayList<>()).add(new ScriptInstance.Listener(id, ev, fn, filter, once));
        if (!inst.rec.subscriptions.contains(ev)) inst.rec.subscriptions.add(ev);
        return (double) id;
    }

    private static Object addTimer(ScriptInstance inst, Interpreter in, double ticks, Callable fn, boolean repeat) {
        long t = (long) Math.max(repeat ? 2 : 1, Math.min(20L * 60 * 60 * 6, ticks));
        if (inst.activeTimers() >= AiConfig.get().scriptMaxTimers) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Too many active timers (max " + AiConfig.get().scriptMaxTimers + ")", 0, 0);
        }
        inst.timers.removeIf(x -> x.cancelled);
        ScriptInstance.Timer timer = new ScriptInstance.Timer();
        timer.id = ++inst.seq;
        timer.due = (WishEngine.get() == null ? 0 : WishEngine.get().tick()) + t;
        timer.interval = repeat ? t : 0;
        timer.fn = fn;
        inst.timers.add(timer);
        return (double) timer.id;
    }

    // =====================================================================================
    // world

    private static ApiObject buildWorld(ScriptInstance inst) {
        ApiObject w = new ApiObject("world");
        w.fn("time", "() → time of day 0..23999 (0 = sunrise, 6000 = noon, 13000 = night)", (in, s, a) -> (double) (Math.floorMod(server().overworld().getOverworldClockTime(), 24000L)));
        w.fn("day", "() → number of in-game days passed", (in, s, a) -> (double) (server().overworld().getOverworldClockTime() / 24000L));
        w.fn("isNight", "() → boolean", (in, s, a) -> {
            long t = Math.floorMod(server().overworld().getOverworldClockTime(), 24000L);
            return t >= 13000 && t < 23000;
        });
        w.fn("isDay", "() → boolean", (in, s, a) -> {
            long t = Math.floorMod(server().overworld().getOverworldClockTime(), 24000L);
            return t < 13000 || t >= 23000;
        });
        w.fn("weather", "() → 'clear' | 'rain' | 'thunder'", (in, s, a) -> {
            ServerLevel l = server().overworld();
            return l.isThundering() ? "thunder" : l.isRaining() ? "rain" : "clear";
        });
        w.fn("setTime", "('day'|'noon'|'night'|'midnight') may need admin approval", (in, s, a) -> {
            String t = Args.str(in, a, 0, "time");
            if (!List.of("day", "noon", "night", "midnight").contains(t)) throw in.error("setTime accepts day, noon, night, midnight");
            if (inst.dry) { inst.effect("set time " + t); return true; }
            runCommand("time set " + t);
            return true;
        });
        w.fn("setWeather", "('clear'|'rain'|'thunder', seconds=300) may need admin approval", (in, s, a) -> {
            String t = Args.str(in, a, 0, "weather");
            if (!List.of("clear", "rain", "thunder").contains(t)) throw in.error("setWeather accepts clear, rain, thunder");
            int secs = (int) Math.max(10, Math.min(3600, Args.num(a, 1, 300)));
            if (inst.dry) { inst.effect("set weather " + t); return true; }
            runCommand("weather " + t + " " + secs + "s");
            return true;
        });
        w.fn("block", "(pos, opts?{dimension}) → block id like 'minecraft:stone'", (in, s, a) -> {
            ServerLevel l = levelOf(inst, Args.opts(a, 1));
            BlockPos p = McUtil.blockPos(Args.pos(in, Args.get(a, 0), "position"));
            if (!l.isLoaded(p)) return "unloaded";
            return McUtil.blockId(l.getBlockState(p));
        });
        w.fn("blockState", "(pos) → full state string like 'minecraft:oak_stairs[facing=east,…]'", (in, s, a) -> {
            ServerLevel l = levelOf(inst, Args.opts(a, 1));
            BlockPos p = McUtil.blockPos(Args.pos(in, Args.get(a, 0), "position"));
            return l.isLoaded(p) ? McUtil.blockString(l.getBlockState(p)) : "unloaded";
        });
        w.fn("isAir", "(pos) → boolean", (in, s, a) -> {
            ServerLevel l = levelOf(inst, Args.opts(a, 1));
            BlockPos p = McUtil.blockPos(Args.pos(in, Args.get(a, 0), "position"));
            return l.isLoaded(p) && l.getBlockState(p).isAir();
        });
        w.fn("surface", "(x, z) → y of the first free block above ground", (in, s, a) -> {
            ServerLevel l = levelOf(inst, Args.opts(a, 2));
            int x = (int) Math.floor(Args.num(in, a, 0, "x")), z = (int) Math.floor(Args.num(in, a, 1, "z"));
            return (double) l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        });
        w.fn("biome", "(pos) → biome id", (in, s, a) -> {
            ServerLevel l = levelOf(inst, Args.opts(a, 1));
            BlockPos p = McUtil.blockPos(Args.pos(in, Args.get(a, 0), "position"));
            return l.getBiome(p).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
        });
        w.fn("findBlocks", "(center, radius<=12, blockId, max=32) → [{x,y,z}] nearest first", (in, s, a) -> {
            ServerLevel l = levelOf(inst, Args.opts(a, 4));
            double[] c = Args.pos(in, Args.get(a, 0), "center");
            int r = (int) Math.max(1, Math.min(12, Args.num(in, a, 1, "radius")));
            String id = McUtil.parseId(in, Args.str(in, a, 2, "block id"), "block").toString();
            int max = (int) Math.max(1, Math.min(128, Args.num(a, 3, 32)));
            List<double[]> found = new ArrayList<>();
            BlockPos center = McUtil.blockPos(c);
            for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
                if (!l.isLoaded(p)) continue;
                if (McUtil.blockId(l.getBlockState(p)).equals(id)) found.add(new double[]{p.getX(), p.getY(), p.getZ()});
            }
            in.budget.allocate(found.size() + (long) (2 * r + 1) * (2 * r + 1) * (2 * r + 1) / 64);
            found.sort((x, y) -> Double.compare(d2(x, c), d2(y, c)));
            JsArray out = new JsArray();
            for (int k = 0; k < Math.min(max, found.size()); k++) out.list.add(Args.posObj(found.get(k)[0], found.get(k)[1], found.get(k)[2]));
            return out;
        });
        w.fn("setBlock", "(pos, blockId, opts?{temporary:false, seconds}) place a block in EMPTY space only (air/water/grass) or replace one this script placed. temporary blocks revert when the script ends (or after 'seconds')", (in, s, a) ->
                placeBlock(inst, in, Args.pos(in, Args.get(a, 0), "position"), Args.str(in, a, 1, "block id"), Args.opts(a, 2)));
        w.fn("fill", "(from, to, blockId, opts?) fill a box (max 512 blocks) — same rules as setBlock; occupied spots are skipped", (in, s, a) -> {
            double[] p1 = Args.pos(in, Args.get(a, 0), "from"), p2 = Args.pos(in, Args.get(a, 1), "to");
            String id = Args.str(in, a, 2, "block id");
            JsObject o = Args.opts(a, 3);
            BlockPos b1 = McUtil.blockPos(p1), b2 = McUtil.blockPos(p2);
            long vol = (long) (Math.abs(b1.getX() - b2.getX()) + 1) * (Math.abs(b1.getY() - b2.getY()) + 1) * (Math.abs(b1.getZ() - b2.getZ()) + 1);
            if (vol > 512) throw in.error("fill volume " + vol + " too large (max 512)");
            int placed = 0;
            for (BlockPos p : BlockPos.betweenClosed(b1, b2)) {
                try {
                    if (Interpreter.truthy(placeBlock(inst, in, new double[]{p.getX(), p.getY(), p.getZ()}, id, o))) placed++;
                } catch (ScriptError e) {
                    if (e.kind == ScriptError.Kind.PERMISSION && e.getMessage().startsWith("Block at")) continue;   // 차 있는 칸은 건너뜀
                    throw e;
                }
            }
            return (double) placed;
        });
        w.fn("structure", "(origin, blocks:[{x,y,z,block}] relative offsets, opts?{temporary, seconds}) build a small structure (max 512 blocks), skipping occupied spots", (in, s, a) -> {
            double[] o = Args.pos(in, Args.get(a, 0), "origin");
            if (!(Args.get(a, 1) instanceof JsArray arr)) throw in.error("structure needs an array of {x, y, z, block}");
            if (arr.list.size() > 512) throw in.error("structure too large (max 512 blocks)");
            JsObject opts = Args.opts(a, 2);
            int placed = 0;
            for (Object b : arr.list) {
                if (!(b instanceof JsObject bo)) continue;
                double[] p = {o[0] + Args.optNum(bo, "x", 0), o[1] + Args.optNum(bo, "y", 0), o[2] + Args.optNum(bo, "z", 0)};
                try {
                    if (Interpreter.truthy(placeBlock(inst, in, p, Args.optStr(bo, "block", "minecraft:stone"), opts))) placed++;
                } catch (ScriptError e) {
                    if (e.kind == ScriptError.Kind.PERMISSION && e.getMessage().startsWith("Block at")) continue;
                    throw e;
                }
            }
            return (double) placed;
        });
        w.fn("restoreBlocks", "() revert every block this script placed", (in, s, a) -> {
            if (inst.dry) { inst.effect("restore blocks"); return true; }
            WishEngine.get().scripts().revertBlocks(inst.rec, false);
            return true;
        });
        w.fn("isLoaded", "(pos) → boolean", (in, s, a) -> levelOf(inst, Args.opts(a, 1)).isLoaded(McUtil.blockPos(Args.pos(in, Args.get(a, 0), "position"))));
        w.fn("dimension", "() → this script's dimension id", (in, s, a) -> inst.rec.dimension);
        return w;
    }

    private static double d2(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static void runCommand(String cmd) {
        MinecraftServer srv = server();
        srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), cmd);
    }

    private static Object placeBlock(ScriptInstance inst, Interpreter in, double[] pos, String id, JsObject opts) {
        ServerLevel l = levelOf(inst, opts);
        BlockState st = McUtil.block(in, id);
        BlockPos p = McUtil.blockPos(pos);
        Policy.checkBlock(in, inst, l, p, st);
        if (inst.dry) { inst.effect("setBlock " + McUtil.blockId(st) + " at " + p.toShortString()); return true; }
        BlockState before = l.getBlockState(p);
        String dim = McUtil.dimId(l);
        ScriptRecord.BlockChange existing = null;
        for (ScriptRecord.BlockChange b : inst.rec.blocks) if (b.x == p.getX() && b.y == p.getY() && b.z == p.getZ() && dim.equals(b.dimension)) existing = b;
        boolean temp = Args.optBool(opts, "temporary", false) || Args.optNum(opts, "seconds", 0) > 0;
        if (existing == null) {
            ScriptRecord.BlockChange ch = new ScriptRecord.BlockChange();
            ch.dimension = dim;
            ch.x = p.getX();
            ch.y = p.getY();
            ch.z = p.getZ();
            ch.before = McUtil.blockString(before);
            ch.temporary = temp;
            existing = ch;
            inst.rec.blocks.add(ch);
        }
        existing.after = McUtil.blockString(st);
        if (temp) existing.temporary = true;
        boolean ok = l.setBlock(p, st, 3);
        double secs = Args.optNum(opts, "seconds", 0);
        if (secs > 0) {
            ScriptRecord.BlockChange ch = existing;
            WishEngine.get().scripts().scheduleRevert(inst.rec.id, ch, (long) (Math.min(3600, secs) * 20));
        }
        return ok;
    }

    // =====================================================================================
    // players / entities

    private static ApiObject buildPlayers(ScriptInstance inst) {
        ApiObject o = new ApiObject("players");
        o.fn("online", "() → [Player] everyone online", (in, s, a) -> {
            JsArray arr = new JsArray();
            for (ServerPlayer p : server().getPlayerList().getPlayers()) arr.list.add(PlayerRef.of(p));
            return arr;
        });
        o.fn("get", "(name|uuid) → Player or null (also works for offline players seen before)", (in, s, a) -> {
            String key = Args.str(in, a, 0, "name or uuid");
            ServerPlayer p = server().getPlayerList().getPlayerByName(key);
            if (p == null) {
                try { p = server().getPlayerList().getPlayer(UUID.fromString(key)); } catch (IllegalArgumentException ignored) { }
            }
            if (p != null) return PlayerRef.of(p);
            var prof = WishEngine.get().state().findPlayer(key);
            return prof == null ? null : new PlayerRef(UUID.fromString(prof.uuid), prof.name);
        });
        o.fn("near", "(pos, radius) → [Player] within radius, nearest first", (in, s, a) -> {
            double[] c = Args.pos(in, Args.get(a, 0), "center");
            double r = Math.max(0, Math.min(256, Args.num(in, a, 1, "radius")));
            List<ServerPlayer> ps = new ArrayList<>();
            ServerLevel l = levelOf(inst, Args.opts(a, 2));
            for (ServerPlayer p : l.players()) if (p.distanceToSqr(c[0], c[1], c[2]) <= r * r) ps.add(p);
            ps.sort((x, y) -> Double.compare(x.distanceToSqr(c[0], c[1], c[2]), y.distanceToSqr(c[0], c[1], c[2])));
            JsArray arr = new JsArray();
            for (ServerPlayer p : ps) arr.list.add(PlayerRef.of(p));
            return arr;
        });
        o.fn("count", "() → number online", (in, s, a) -> (double) server().getPlayerList().getPlayerCount());
        return o;
    }

    private static ApiObject buildEntities(ScriptInstance inst) {
        ApiObject o = new ApiObject("entities");
        o.fn("spawn", "(type, pos, opts?{name, nameVisible, glow, baby, noAi, silent, invulnerable, keep, tame: player, equip:{head:'carved_pumpkin'}}) → Entity. Entities are removed when the script ends unless keep:true", (in, s, a) -> {
            EntityType<?> type = McUtil.entityType(in, Args.str(in, a, 0, "entity type"));
            double[] p = Args.pos(in, Args.get(a, 1), "spawn position");
            JsObject opts = Args.opts(a, 2);
            Policy.checkSpawn(in, inst, type, p);
            if (inst.dry) {
                inst.dryEntitySeq++;
                inst.effect("spawn " + McUtil.entityId(type) + " at " + (int) p[0] + "," + (int) p[1] + "," + (int) p[2] + (opts.map.isEmpty() ? "" : " " + dev.newyear.countdown.ai.script.lang.Stdlib.debugString(opts, 1)));
                return EntityRef.fake(McUtil.entityId(type), p);
            }
            ServerLevel l = levelOf(inst, opts);
            if (!l.isLoaded(McUtil.blockPos(p))) throw deny("Spawn position is not loaded");
            Entity e = type.create(l, EntitySpawnReason.COMMAND);
            if (e == null) throw in.error("Cannot spawn " + McUtil.entityId(type));
            e.snapTo(p[0], p[1], p[2], ThreadLocalRandom.current().nextFloat() * 360f, 0f);
            applySpawnOpts(in, inst, e, opts);
            e.addTag("wishai");
            e.addTag("wishai_" + inst.rec.id);
            if (!l.addFreshEntity(e)) throw in.error("The world refused to spawn " + McUtil.entityId(type));
            inst.rec.entities.add(e.getUUID().toString());
            if (Args.optBool(opts, "keep", inst.rec.keepEntitiesDefault)) inst.rec.keptEntities.add(e.getUUID().toString());
            return EntityRef.of(e);
        });
        o.fn("display", "(kind 'text'|'block'|'item', pos, opts) floating visual: text{text, color, background, scale, billboard}, block{block, scale, rotation}, item{item, scale, spin}. Use for holograms, statues, procedural models", (in, s, a) -> {
            String kind = Args.str(in, a, 0, "kind");
            double[] p = Args.pos(in, Args.get(a, 1), "position");
            JsObject opts = Args.opts(a, 2);
            EntityType<?> type = switch (kind) {
                case "text" -> EntityTypes.TEXT_DISPLAY;
                case "block" -> EntityTypes.BLOCK_DISPLAY;
                case "item" -> EntityTypes.ITEM_DISPLAY;
                default -> throw in.error("display kind must be text, block or item");
            };
            Policy.checkRange(in, inst, p);
            int mine = inst.dry ? inst.dryEntitySeq : Policy.liveCount(inst);
            if (mine >= AiConfig.get().scriptMaxEntities * 2) throw deny("Too many displays for this script");
            if (inst.dry) {
                inst.dryEntitySeq++;
                inst.effect("display " + kind + " at " + (int) p[0] + "," + (int) p[1] + "," + (int) p[2]);
                return EntityRef.fake(McUtil.entityId(type), p);
            }
            ServerLevel l = levelOf(inst, opts);
            Entity e = type.create(l, EntitySpawnReason.COMMAND);
            if (!(e instanceof Display d)) throw in.error("Cannot create display");
            d.snapTo(p[0], p[1], p[2], (float) Args.optNum(opts, "yaw", 0), 0f);
            float scale = (float) Math.max(0.05, Math.min(8, Args.optNum(opts, "scale", 1)));
            float rot = (float) Math.toRadians(Args.optNum(opts, "rotation", 0));
            d.setTransformation(new Transformation(new Vector3f(kind.equals("block") ? -scale / 2 : 0, 0, kind.equals("block") ? -scale / 2 : 0),
                    new Quaternionf().rotateY(rot), new Vector3f(scale, scale, scale), null));
            String bb = Args.optStr(opts, "billboard", kind.equals("text") ? "center" : "fixed");
            d.setBillboardConstraints(switch (bb) {
                case "vertical" -> Display.BillboardConstraints.VERTICAL;
                case "horizontal" -> Display.BillboardConstraints.HORIZONTAL;
                case "center" -> Display.BillboardConstraints.CENTER;
                default -> Display.BillboardConstraints.FIXED;
            });
            if (Args.optBool(opts, "glow", false)) d.setGlowingTag(true);
            switch (d) {
                case Display.TextDisplay td -> {
                    td.setText(McUtil.text(Args.optStr(opts, "text", "✦"), Args.optStr(opts, "color", "#f3d98a")));
                    String bg = Args.optStr(opts, "background", null);
                    td.setBackgroundColor(bg == null ? 0x40000000 : (0xA0000000 | McUtil.color(bg, 0)));
                    td.setLineWidth(200);
                }
                case Display.BlockDisplay bd -> bd.setBlockState(McUtil.block(in, Args.optStr(opts, "block", "minecraft:glowstone")));
                case Display.ItemDisplay id -> id.setItemStack(McUtil.makeStack(in, Args.optStr(opts, "item", "minecraft:nether_star"), 1, new JsObject(), inst.rec.id));
                default -> { }
            }
            d.addTag("wishai");
            d.addTag("wishai_" + inst.rec.id);
            l.addFreshEntity(d);
            inst.rec.entities.add(d.getUUID().toString());
            if (Args.optBool(opts, "keep", false)) inst.rec.keptEntities.add(d.getUUID().toString());
            double secs = Args.optNum(opts, "seconds", 0);
            if (secs > 0) WishEngine.get().scripts().scheduleEntityRemoval(inst.rec.id, d.getUUID(), (long) (Math.min(3600, secs) * 20));
            return EntityRef.of(d);
        });
        o.fn("near", "(pos, radius<=64, type?) → [Entity] (not players), nearest first, max 64", (in, s, a) -> {
            double[] c = Args.pos(in, Args.get(a, 0), "center");
            double r = Math.max(0.5, Math.min(64, Args.num(in, a, 1, "radius")));
            String type = Args.present(a, 2) ? McUtil.parseId(in, Interpreter.toStr(a.get(2)), "entity type").toString() : null;
            ServerLevel l = levelOf(inst, Args.opts(a, 3));
            List<Entity> list = l.getEntities((Entity) null, new AABB(c[0] - r, c[1] - r, c[2] - r, c[0] + r, c[1] + r, c[2] + r),
                    e -> !(e instanceof Player) && e.isAlive() && e.distanceToSqr(c[0], c[1], c[2]) <= r * r && (type == null || McUtil.entityId(e.getType()).equals(type)));
            list.sort((x, y) -> Double.compare(x.distanceToSqr(c[0], c[1], c[2]), y.distanceToSqr(c[0], c[1], c[2])));
            JsArray arr = new JsArray();
            for (int k = 0; k < Math.min(64, list.size()); k++) arr.list.add(EntityRef.of(list.get(k)));
            in.budget.allocate(arr.list.size() + 1);
            return arr;
        });
        o.fn("get", "(uuid) → Entity or null if gone/unloaded", (in, s, a) -> {
            try {
                EntityRef r = new EntityRef(UUID.fromString(Args.str(in, a, 0, "uuid")), "?");
                Entity e = r.live();
                return e == null ? null : EntityRef.of(e);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        });
        o.fn("mine", "() → [Entity] alive entities this script spawned", (in, s, a) -> {
            JsArray arr = new JsArray();
            for (String u : inst.rec.entities) {
                Entity e = new EntityRef(UUID.fromString(u), "?").live();
                if (e != null && e.isAlive()) arr.list.add(EntityRef.of(e));
            }
            return arr;
        });
        return o;
    }

    private static void applySpawnOpts(Interpreter in, ScriptInstance inst, Entity e, JsObject opts) {
        String name = Args.optStr(opts, "name", null);
        if (name != null) {
            e.setCustomName(McUtil.text(name, Args.optStr(opts, "nameColor", null)));
            e.setCustomNameVisible(Args.optBool(opts, "nameVisible", true));
        }
        if (Args.optBool(opts, "glow", false)) e.setGlowingTag(true);
        if (Args.optBool(opts, "silent", false)) e.setSilent(true);
        if (Args.optBool(opts, "invulnerable", false)) e.setPermanentlyInvulnerable(true);
        if (e instanceof Mob m) {
            m.setPersistenceRequired();
            if (Args.optBool(opts, "noAi", false)) m.setNoAi(true);
            if (opts.get("equip") instanceof JsObject eq) {
                for (Map.Entry<String, Object> en : eq.map.entrySet()) {
                    net.minecraft.world.entity.EquipmentSlot slot = switch (en.getKey()) {
                        case "head" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                        case "chest" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                        case "legs" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                        case "feet" -> net.minecraft.world.entity.EquipmentSlot.FEET;
                        case "offhand" -> net.minecraft.world.entity.EquipmentSlot.OFFHAND;
                        default -> net.minecraft.world.entity.EquipmentSlot.MAINHAND;
                    };
                    m.setItemSlot(slot, McUtil.makeStack(in, Interpreter.toStr(en.getValue()), 1, new JsObject(), inst.rec.id));
                    m.setDropChance(slot, 0f);
                }
            }
        }
        if (e instanceof AgeableMob am && Args.optBool(opts, "baby", false)) am.setBaby(true);
        if (e instanceof TamableAnimal ta && opts.get("tame") instanceof PlayerRef pr && pr.live() != null) ta.tame(pr.live());
    }

    // =====================================================================================
    // fx

    private static ApiObject buildFx(ScriptInstance inst) {
        ApiObject o = new ApiObject("fx");
        o.fn("particles", "(type, pos, opts?{count=12, spread=0.4, speed=0.02, color, scale}) e.g. 'end_rod', 'heart', 'firework', 'happy_villager', 'note', 'glow', 'enchant', 'flame', 'cherry_leaves', 'snowflake', 'totem_of_undying', 'dust'", (in, s, a) -> {
            JsObject opts = Args.opts(a, 2);
            ParticleOptions po = McUtil.particle(in, Args.str(in, a, 0, "particle type"), opts);
            double[] p = Args.pos(in, Args.get(a, 1), "position");
            int count = (int) Math.max(1, Math.min(200, Args.optNum(opts, "count", 12)));
            double spread = Math.max(0, Math.min(8, Args.optNum(opts, "spread", 0.4)));
            if (inst.dry) { inst.effect("particles x" + count); return true; }
            levelOf(inst, opts).sendParticles(po, p[0], p[1], p[2], count, spread, spread, spread, Math.max(0, Math.min(2, Args.optNum(opts, "speed", 0.02))));
            return true;
        });
        o.fn("ring", "(center, radius, type, opts?{points=24, color, y}) particle circle", (in, s, a) -> shape(inst, in, a, "ring"));
        o.fn("sphere", "(center, radius, type, opts?{points=60, color})", (in, s, a) -> shape(inst, in, a, "sphere"));
        o.fn("spiral", "(center, radius, type, opts?{height=2, turns=3, points=48, color})", (in, s, a) -> shape(inst, in, a, "spiral"));
        o.fn("line", "(from, to, type, opts?{step=0.3, color})", (in, s, a) -> {
            double[] p = Args.pos(in, Args.get(a, 0), "from"), q = Args.pos(in, Args.get(a, 1), "to");
            JsObject opts = Args.opts(a, 3);
            ParticleOptions po = McUtil.particle(in, Args.str(in, a, 2, "particle type"), opts);
            double len = Math.sqrt(d2(p, q));
            if (len > 64) throw in.error("line too long (max 64)");
            double step = Math.max(0.1, Args.optNum(opts, "step", 0.3));
            int n = (int) Math.min(300, len / step) + 1;
            if (inst.dry) { inst.effect("particle line " + n); return true; }
            ServerLevel l = levelOf(inst, opts);
            for (int k = 0; k <= n; k++) {
                double t = n == 0 ? 0 : (double) k / n;
                l.sendParticles(po, p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t, 1, 0, 0, 0, 0);
            }
            return true;
        });
        o.fn("points", "(type, [pos…], opts?{color}) particles at many custom points (max 400) — draw any procedural pattern", (in, s, a) -> {
            JsObject opts = Args.opts(a, 2);
            ParticleOptions po = McUtil.particle(in, Args.str(in, a, 0, "particle type"), opts);
            if (!(Args.get(a, 1) instanceof JsArray arr)) throw in.error("points needs an array of positions");
            if (arr.list.size() > 400) throw in.error("too many points (max 400)");
            if (inst.dry) { inst.effect("particle points " + arr.list.size()); return true; }
            ServerLevel l = levelOf(inst, opts);
            for (Object v : arr.list) {
                double[] p = Args.pos(in, v, "point");
                l.sendParticles(po, p[0], p[1], p[2], 1, 0, 0, 0, 0);
            }
            return true;
        });
        o.fn("burst", "(pos, style 'stars'|'hearts'|'magic'|'snow'|'gold'|'soul'|'notes'|'confetti') ready-made burst", (in, s, a) -> {
            double[] p = Args.pos(in, Args.get(a, 0), "position");
            String style = Args.str(a, 1, "stars");
            if (inst.dry) { inst.effect("burst " + style); return true; }
            ServerLevel l = levelOf(inst, Args.opts(a, 2));
            Bursts.play(l, p, style);
            return true;
        });
        o.fn("sound", "(id, pos, volume=1, pitch=1) heard by nearby players, e.g. 'entity.experience_orb.pickup', 'block.amethyst_block.chime', 'entity.cat.ambient'", (in, s, a) -> {
            var ev = McUtil.sound(in, Args.str(in, a, 0, "sound id"));
            double[] p = Args.pos(in, Args.get(a, 1), "position");
            if (inst.dry) { inst.effect("sound " + BuiltInRegistries.SOUND_EVENT.getKey(ev)); return true; }
            levelOf(inst, Args.opts(a, 4)).playSound(null, p[0], p[1], p[2], ev, SoundSource.AMBIENT,
                    (float) Math.max(0, Math.min(3, Args.num(a, 2, 1))), (float) Math.max(0.5, Math.min(2, Args.num(a, 3, 1))));
            return true;
        });
        o.fn("firework", "(pos, opts?{colors:['#ff0000','gold'], fade:[], shape:'ball'|'large_ball'|'star'|'creeper'|'burst', trail, twinkle}) harmless firework burst", (in, s, a) -> {
            double[] p = Args.pos(in, Args.get(a, 0), "position");
            JsObject opts = Args.opts(a, 1);
            Policy.checkRange(in, inst, p);
            if (inst.dry) { inst.effect("firework at " + (int) p[0] + "," + (int) p[1] + "," + (int) p[2]); return true; }
            IntArrayList colors = new IntArrayList(), fade = new IntArrayList();
            if (opts.get("colors") instanceof JsArray ca) for (Object c : ca.list) if (colors.size() < 8) colors.add(McUtil.color(Interpreter.toStr(c), 0xFFD27A));
            if (colors.isEmpty()) colors.add(0xFFD27A);
            if (opts.get("fade") instanceof JsArray fa) for (Object c : fa.list) if (fade.size() < 8) fade.add(McUtil.color(Interpreter.toStr(c), 0xFFFFFF));
            FireworkExplosion.Shape shape = switch (Args.optStr(opts, "shape", "ball")) {
                case "large_ball" -> FireworkExplosion.Shape.LARGE_BALL;
                case "star" -> FireworkExplosion.Shape.STAR;
                case "creeper" -> FireworkExplosion.Shape.CREEPER;
                case "burst" -> FireworkExplosion.Shape.BURST;
                default -> FireworkExplosion.Shape.SMALL_BALL;
            };
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(new FireworkExplosion(shape, colors, fade,
                    Args.optBool(opts, "trail", false), Args.optBool(opts, "twinkle", false)))));
            ServerLevel l = levelOf(inst, opts);
            FireworkRocketEntity fw = new FireworkRocketEntity(l, p[0], p[1], p[2], rocket);
            fw.addTag(FX_TAG);
            fw.setDeltaMovement(0, 0.02, 0);
            l.addFreshEntity(fw);
            return true;
        });
        o.fn("lightning", "(pos) harmless visual lightning (no fire, no damage)", (in, s, a) -> {
            double[] p = Args.pos(in, Args.get(a, 0), "position");
            Policy.checkRange(in, inst, p);
            if (inst.dry) { inst.effect("visual lightning"); return true; }
            ServerLevel l = levelOf(inst, Args.opts(a, 1));
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(l, EntitySpawnReason.COMMAND);
            if (bolt == null) return false;
            bolt.setVisualOnly(true);
            bolt.snapTo(p[0], p[1], p[2]);
            l.addFreshEntity(bolt);
            return true;
        });
        o.fn("text", "(pos, text, opts?{seconds=5, color, scale, background}) floating text that disappears", (in, s, a) -> {
            JsObject opts = new JsObject(Args.opts(a, 2).map);
            opts.put("text", Args.str(in, a, 1, "text"));
            if (!opts.map.containsKey("seconds")) opts.put("seconds", 5.0);
            Object disp = buildEntitiesCached(inst).getMember(in, "display");
            return ((Callable) disp).call(in, UNDEFINED, java.util.Arrays.asList("text", Args.get(a, 0), opts));
        });
        return o;
    }

    /** 폭죽 피해를 막는 표식 (믹스인이 확인). */
    public static final String FX_TAG = "wishai_fx";

    private static final Map<ScriptInstance, ApiObject> ENT_CACHE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static ApiObject buildEntitiesCached(ScriptInstance inst) {
        Object e = inst.in.getGlobal("entities");
        if (e instanceof ApiObject ao) return ao;
        return ENT_CACHE.computeIfAbsent(inst, ScriptApi::buildEntities);
    }

    private static Object shape(ScriptInstance inst, Interpreter in, List<Object> a, String kind) {
        double[] c = Args.pos(in, Args.get(a, 0), "center");
        double r = Math.max(0.1, Math.min(16, Args.num(in, a, 1, "radius")));
        JsObject opts = Args.opts(a, 3);
        ParticleOptions po = McUtil.particle(in, Args.str(in, a, 2, "particle type"), opts);
        int points = (int) Math.max(4, Math.min(300, Args.optNum(opts, "points", kind.equals("sphere") ? 60 : kind.equals("spiral") ? 48 : 24)));
        if (inst.dry) { inst.effect(kind + " particles x" + points); return true; }
        ServerLevel l = levelOf(inst, opts);
        double y0 = c[1] + Args.optNum(opts, "y", 0);
        for (int k = 0; k < points; k++) {
            double x, y, z;
            switch (kind) {
                case "sphere" -> {
                    double phi = Math.acos(1 - 2 * (k + 0.5) / points), th = Math.PI * (1 + Math.sqrt(5)) * k;
                    x = c[0] + r * Math.cos(th) * Math.sin(phi);
                    y = c[1] + r * Math.cos(phi);
                    z = c[2] + r * Math.sin(th) * Math.sin(phi);
                }
                case "spiral" -> {
                    double t = (double) k / points;
                    double ang = t * Math.PI * 2 * Args.optNum(opts, "turns", 3);
                    x = c[0] + r * Math.cos(ang);
                    y = y0 + t * Args.optNum(opts, "height", 2);
                    z = c[2] + r * Math.sin(ang);
                }
                default -> {
                    double ang = Math.PI * 2 * k / points;
                    x = c[0] + r * Math.cos(ang);
                    y = y0;
                    z = c[2] + r * Math.sin(ang);
                }
            }
            l.sendParticles(po, x, y, z, 1, 0, 0, 0, 0);
        }
        return true;
    }

    // =====================================================================================
    // ui

    private static ApiObject buildUi(ScriptInstance inst) {
        ApiObject o = new ApiObject("ui");
        o.fn("notify", "(player|'all', text, opts?{title, kind:'info'|'success'|'warning'|'mystic', seconds=5}) toast notification in the wish UI style", (in, s, a) -> {
            String text = Args.str(in, a, 1, "text");
            JsObject opts = Args.opts(a, 2);
            if (inst.dry) { inst.effect("notify: " + text); return true; }
            for (ServerPlayer p : targets(in, Args.get(a, 0))) {
                WishEngine.get().ui().notify(p, Args.optStr(opts, "title", ""), text, Args.optStr(opts, "kind", "info"), (int) Args.optNum(opts, "seconds", 5));
            }
            return true;
        });
        o.fn("say", "(player, text, opts?{mood}) the wish spirit speaks in the dialogue window / chat", (in, s, a) -> {
            String text = Args.str(in, a, 1, "text");
            if (inst.dry) { inst.effect("spirit says: " + text); return true; }
            for (ServerPlayer p : targets(in, Args.get(a, 0))) WishEngine.get().ui().spiritLine(p, inst.rec.wishId, text, Args.optStr(Args.opts(a, 2), "mood", "neutral"));
            return true;
        });
        o.fn("choices", "(player, prompt, ['a','b',…] (2-6), callback(choiceIndex, choiceText, player), opts?{timeout:seconds, title}) → interaction id. callback gets (-1, null) on timeout", (in, s, a) -> {
            ServerPlayer p = playerArg(in, Args.get(a, 0));
            String prompt = Args.str(in, a, 1, "prompt");
            if (!(Args.get(a, 2) instanceof JsArray arr) || arr.list.size() < 2 || arr.list.size() > 6) throw in.error("choices needs 2-6 options");
            Callable cb = Args.fn(in, a, 3, "callback");
            JsObject opts = Args.opts(a, 4);
            List<String> options = new ArrayList<>();
            for (Object x : arr.list) options.add(MemoryText.limit(Interpreter.toStr(x), 80));
            if (inst.dry) { inst.effect("choices to " + p.getGameProfile().name() + ": " + prompt + " " + options); return "dry-choice"; }
            return WishEngine.get().ui().choices(p, Args.optStr(opts, "title", ""), prompt, options, (int) Args.optNum(opts, "timeout", 0), inst.rec.wishId,
                    WishEngine.get().scripts().callbackResponder(inst.rec.id, cb, true));
        });
        o.fn("input", "(player, prompt, callback(text|null, player), opts?{timeout:seconds, placeholder, title}) text box; null on timeout", (in, s, a) -> {
            ServerPlayer p = playerArg(in, Args.get(a, 0));
            String prompt = Args.str(in, a, 1, "prompt");
            Callable cb = Args.fn(in, a, 2, "callback");
            JsObject opts = Args.opts(a, 3);
            if (inst.dry) { inst.effect("input to " + p.getGameProfile().name() + ": " + prompt); return "dry-input"; }
            return WishEngine.get().ui().input(p, Args.optStr(opts, "title", ""), prompt, Args.optStr(opts, "placeholder", ""), (int) Args.optNum(opts, "timeout", 0), inst.rec.wishId,
                    WishEngine.get().scripts().callbackResponder(inst.rec.id, cb, false));
        });
        o.fn("consent", "(player, text, callback(agreed:boolean)) ask another player to agree; once agreed, this wish may affect them (take items, teleport…)", (in, s, a) -> {
            ServerPlayer p = playerArg(in, Args.get(a, 0));
            String text = Args.str(in, a, 1, "text");
            Callable cb = Args.fn(in, a, 2, "callback");
            if (inst.dry) { inst.effect("consent request to " + p.getGameProfile().name()); return "dry-consent"; }
            return WishEngine.get().ui().consent(p, inst.rec.wishId, text, WishEngine.get().scripts().consentResponder(inst.rec.id, cb));
        });
        o.fn("cinematic", "(player|'all', opts{title, subtitle, style:'wish'|'dawn'|'mystic'|'celebrate'|'ominous', seconds=4}) letterboxed cinematic title card", (in, s, a) -> {
            JsObject opts = Args.opts(a, 1);
            if (inst.dry) { inst.effect("cinematic " + Args.optStr(opts, "title", "")); return true; }
            for (ServerPlayer p : targets(in, Args.get(a, 0))) {
                WishEngine.get().ui().cinematic(p, Args.optStr(opts, "title", ""), Args.optStr(opts, "subtitle", ""), Args.optStr(opts, "style", "wish"), (int) Args.optNum(opts, "seconds", 4));
            }
            return true;
        });
        o.fn("status", "(player, id, opts{label, desc, kind:'ability'|'curse'|'blessing'|'info', seconds (0=until script ends), counter:'3/7', icon:{pixels:['..#..',…], palette:{'#':'#ffcc00'}}}) show/update a HUD status line", (in, s, a) -> {
            ServerPlayer p = playerArg(in, Args.get(a, 0));
            String id = MemoryText.limit(Args.str(in, a, 1, "status id"), 40);
            JsObject opts = Args.opts(a, 2);
            if (inst.dry) { inst.effect("HUD status '" + Args.optStr(opts, "label", id) + "' for " + p.getGameProfile().name()); return true; }
            StatusEntry st = new StatusEntry();
            st.id = inst.rec.id + ":" + id;
            st.owner = p.getUUID().toString();
            st.label = MemoryText.limit(Args.optStr(opts, "label", id), 40);
            st.description = MemoryText.limit(Args.optStr(opts, "desc", ""), 120);
            st.kind = Args.optStr(opts, "kind", "ability");
            st.counter = MemoryText.limit(Args.optStr(opts, "counter", ""), 16);
            st.scriptId = inst.rec.id;
            st.wishId = inst.rec.wishId;
            double secs = Args.optNum(opts, "seconds", 0);
            st.startTick = WishEngine.get().tick();
            st.expiresTick = secs > 0 ? st.startTick + (long) (secs * 20) : (inst.rec.durationTicks > 0 ? inst.rec.expiresTick : 0);
            if (opts.get("icon") instanceof JsObject icon) Icons.apply(st, icon);
            WishEngine.get().ui().setStatus(st);
            return st.id;
        });
        o.fn("clearStatus", "(player, id)", (in, s, a) -> {
            if (inst.dry) { inst.effect("clear status"); return true; }
            WishEngine.get().ui().clearStatus(inst.rec.id + ":" + Args.str(in, a, 1, "status id"));
            return true;
        });
        o.fn("timer", "(player, id, seconds, label) HUD countdown (display only; use after() for logic)", (in, s, a) -> {
            ServerPlayer p = playerArg(in, Args.get(a, 0));
            String id = MemoryText.limit(Args.str(in, a, 1, "timer id"), 40);
            double secs = Math.max(1, Math.min(7200, Args.num(in, a, 2, "seconds")));
            if (inst.dry) { inst.effect("HUD timer " + secs + "s"); return true; }
            StatusEntry st = new StatusEntry();
            st.id = inst.rec.id + ":" + id;
            st.owner = p.getUUID().toString();
            st.label = MemoryText.limit(Args.str(a, 3, "남은 시간"), 40);
            st.kind = "timer";
            st.scriptId = inst.rec.id;
            st.wishId = inst.rec.wishId;
            st.startTick = WishEngine.get().tick();
            st.expiresTick = st.startTick + (long) (secs * 20);
            WishEngine.get().ui().setStatus(st);
            return st.id;
        });
        return o;
    }

    // =====================================================================================
    // quests

    private static ApiObject buildQuests(ScriptInstance inst) {
        ApiObject o = new ApiObject("quests");
        o.fn("create", "({title, description, conditions, reward, failure, objectives:[{id, text, target=1, optional}], timeLimit:seconds, participants:[Player], priority}) → Quest. Shows the quest panel/HUD. Update with q.add(objId)", (in, s, a) -> {
            if (!(Args.get(a, 0) instanceof JsObject spec)) throw in.error("quests.create needs an object");
            Quest q = QuestSpecs.fromScript(in, spec, inst);
            if (inst.dry) { inst.effect("create quest '" + q.title + "' with " + q.objectives.size() + " objectives"); return new QuestRef(q.id, true, q); }
            WishEngine.get().quests().create(q);
            return new QuestRef(q.id, false, null);
        });
        o.fn("get", "(id) → Quest or null", (in, s, a) -> {
            String id = Args.str(in, a, 0, "quest id");
            if (WishEngine.get().state().quests.containsKey(id)) return new QuestRef(id, false, null);
            return null;
        });
        o.fn("active", "(player?) → [Quest] active quests (of that player)", (in, s, a) -> {
            JsArray arr = new JsArray();
            String who = Args.get(a, 0) instanceof PlayerRef pr ? pr.id.toString() : null;
            for (Quest q : WishEngine.get().state().quests.values()) {
                if (q.state != Quest.State.ACTIVE) continue;
                if (who != null && !q.involves(who)) continue;
                arr.list.add(new QuestRef(q.id, false, null));
            }
            return arr;
        });
        return o;
    }

    // =====================================================================================
    // memory / wish / script / ai

    private static ApiObject buildMemory(ScriptInstance inst) {
        ApiObject o = new ApiObject("memory");
        o.fn("remember", "(text, opts?{importance 1-10, scope:'player'|'world', kind, tags:[...], thread}) store a memory the AI will recall later (max 30 per script)", (in, s, a) -> {
            String text = MemoryText.limit(Args.str(in, a, 0, "text"), 400);
            JsObject opts = Args.opts(a, 1);
            if (++inst.memoryWrites > 30) throw new ScriptError(ScriptError.Kind.BUDGET, "memory.remember limit reached for this script", 0, 0);
            if (inst.dry) { inst.effect("remember: " + text); return true; }
            MemoryRecord r = new MemoryRecord();
            r.text = text;
            r.kind = Args.optStr(opts, "kind", "event");
            r.importance = (int) Args.optNum(opts, "importance", 5);
            r.scope = "world".equals(Args.optStr(opts, "scope", "player")) || inst.rec.owner == null ? MemoryRecord.Scope.WORLD : MemoryRecord.Scope.PLAYER;
            r.owner = inst.rec.owner;
            r.ownerName = inst.rec.ownerName;
            r.thread = Args.optStr(opts, "thread", null);
            r.scriptId = inst.rec.id;
            r.wishId = inst.rec.wishId;
            if (opts.get("tags") instanceof JsArray tags) for (Object t : tags.list) if (r.tags.size() < 8) r.tags.add(MemoryText.limit(Interpreter.toStr(t), 32));
            if (opts.get("entity") instanceof EntityRef er) r.entityUuid = er.id.toString();
            if (opts.get("pos") != null && opts.get("pos") != UNDEFINED) {
                double[] p = Args.pos(in, opts.get("pos"), "pos");
                r.pos = new int[]{(int) p[0], (int) p[1], (int) p[2]};
                r.dimension = inst.rec.dimension;
            }
            WishEngine.get().rememberFromScript(r);
            return true;
        });
        o.fn("recall", "(query, max=5) → [{text, kind, importance, when}] memories visible to this script's owner", (in, s, a) -> {
            String q = Args.str(in, a, 0, "query");
            int max = (int) Math.max(1, Math.min(10, Args.num(a, 1, 5)));
            JsArray arr = new JsArray();
            for (var h : WishEngine.get().memory().search(q, inst.rec.owner, max, null, true, System.currentTimeMillis())) {
                JsObject m = new JsObject();
                m.put("text", h.record().text).put("kind", h.record().kind).put("importance", (double) h.record().importance).put("when", (double) h.record().created);
                arr.list.add(m);
            }
            return arr;
        });
        return o;
    }

    private static ApiObject buildWish(ScriptInstance inst, Wish wish) {
        ApiObject o = new ApiObject("wish");
        o.value("id", "this wish's id (or null)", wish == null ? null : wish.id);
        o.value("text", "what the player wished for", wish == null ? "" : wish.text);
        o.fn("state", "() → current wish state", (in, s, a) -> {
            Wish w = inst.rec.wishId == null ? null : WishEngine.get().state().wishes.get(inst.rec.wishId);
            return w == null ? "NONE" : w.state.name();
        });
        o.fn("complete", "(summary) mark the wish fulfilled (the script itself keeps running if it is a lasting ability)", (in, s, a) -> {
            String sum = MemoryText.limit(Args.str(a, 0, ""), 300);
            if (inst.dry) { inst.effect("wish complete: " + sum); return true; }
            if (inst.rec.wishId != null) WishEngine.get().wishes().completeFromScript(inst.rec.wishId, sum);
            return true;
        });
        o.fn("fail", "(reason) mark the wish failed (e.g. quest deadline missed)", (in, s, a) -> {
            String why = MemoryText.limit(Args.str(a, 0, ""), 300);
            if (inst.dry) { inst.effect("wish failed: " + why); return true; }
            if (inst.rec.wishId != null) WishEngine.get().wishes().failFromScript(inst.rec.wishId, why);
            return true;
        });
        return o;
    }

    private static ApiObject buildScript(ScriptInstance inst) {
        ApiObject o = new ApiObject("script");
        o.value("id", "script id", inst.rec.id);
        o.value("name", "script name", inst.rec.name);
        o.getter("restored", "true when re-run after a server restart (state already holds saved values)", () -> inst.restored);
        o.getter("version", "code version number", () -> (double) inst.rec.version);
        o.fn("remaining", "() → seconds left (-1 = permanent)", (in, s, a) -> inst.rec.durationTicks < 0 ? -1.0 : Math.max(0, (inst.rec.expiresTick - WishEngine.get().tick()) / 20.0));
        o.fn("stop", "(reason?) end this script (cleans up its entities, temporary blocks, HUD)", (in, s, a) -> {
            if (inst.dry) { inst.effect("script stop"); return true; }
            WishEngine.get().scripts().requestStop(inst.rec.id, MemoryText.limit(Args.str(a, 0, "script finished"), 200));
            return true;
        });
        return o;
    }

    private static ApiObject buildAi(ScriptInstance inst) {
        ApiObject o = new ApiObject("ai");
        o.fn("ask", "(text, data?) ask the wish spirit (AI) to react to something important (e.g. quest done). Rate limited: once per 2 minutes per script", (in, s, a) -> {
            String text = MemoryText.limit(Args.str(in, a, 0, "text"), 500);
            if (inst.dry) { inst.effect("ai.ask: " + text); return true; }
            long t = WishEngine.get().tick();
            if (t - inst.lastAiRequestTick < 2400 || inst.aiRequests >= 20) return false;
            inst.lastAiRequestTick = t;
            inst.aiRequests++;
            Object data = Args.get(a, 1);
            String json = data == UNDEFINED ? "" : dev.newyear.countdown.ai.script.lang.Stdlib.toJson(data).toString();
            return WishEngine.get().requestFromScript(inst.rec, text, MemoryText.limit(json, 1500));
        });
        return o;
    }

    // =====================================================================================

    /** 텍스트 길이 제한 도우미. */
    public static final class MemoryText {
        private MemoryText() {}

        public static String limit(String s, int n) {
            if (s == null) return "";
            String c = McUtil.clean(s, n);
            return c;
        }
    }
}
