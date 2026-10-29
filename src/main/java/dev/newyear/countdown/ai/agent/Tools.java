package dev.newyear.countdown.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.memory.MemoryStore;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.ScriptValidator;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.ui.UiService;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * AI 가 부르는 도구. 모든 처리기는 서버 스레드에서 실행되며 입력을 서버 쪽에서 검증한다.
 * 실패해도 예외 대신 {"error": "..."} 를 돌려줘 모델이 스스로 고칠 수 있게 한다.
 */
public final class Tools {
    private Tools() {}

    /** 도구 실행 맥락. */
    public static final class Ctx {
        public final WishEngine eng;
        public final TurnRequest req;
        public final String turnId;
        public Wish wish;
        public int validations;
        public final List<String> createdScripts = new ArrayList<>();

        public Ctx(WishEngine eng, TurnRequest req, String turnId, Wish wish) {
            this.eng = eng;
            this.req = req;
            this.turnId = turnId;
            this.wish = wish;
        }

        public ServerPlayer player() {
            return req.player() == null ? null : eng.player(req.player());
        }

        public String playerUuid() {
            return req.player() == null ? null : req.player().toString();
        }
    }

    @FunctionalInterface
    interface Handler {
        JsonElement run(Ctx ctx, JsonObject args);
    }

    record Tool(String name, String description, JsonObject schema, Handler handler) {}

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    private static void def(String name, String description, String schema, Handler h) {
        TOOLS.put(name, new Tool(name, description, JsonParser.parseString(schema).getAsJsonObject(), h));
    }

    public static JsonArray definitions() {
        JsonArray arr = new JsonArray();
        for (Tool t : TOOLS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "function");
            o.addProperty("name", t.name);
            o.addProperty("description", t.description);
            o.add("parameters", t.schema);
            o.addProperty("strict", false);
            arr.add(o);
        }
        return arr;
    }

    public static boolean exists(String name) {
        return TOOLS.containsKey(name);
    }

    /** 서버 스레드에서 호출한다. */
    public static String execute(Ctx ctx, String name, String argsJson) {
        Tool t = TOOLS.get(name);
        if (t == null) return err("Unknown tool '" + name + "'. Available: " + String.join(", ", TOOLS.keySet()));
        JsonObject args;
        try {
            JsonElement el = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            args = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return err("Arguments were not valid JSON: " + e.getMessage());
        }
        try {
            JsonElement r = t.handler.run(ctx, args);
            String s = r.toString();
            return s.length() > 24_000 ? s.substring(0, 24_000) + "…(truncated)" : s;
        } catch (ToolError e) {
            return err(e.getMessage());
        } catch (RuntimeException e) {
            dev.newyear.countdown.NewYearCountdown.LOGGER.warn("[WishAI] tool {} failed", name, e);
            return err("Tool failed internally: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static final class ToolError extends RuntimeException {
        ToolError(String m) {
            super(m, null, false, false);
        }
    }

    static String err(String m) {
        JsonObject o = new JsonObject();
        o.addProperty("error", m);
        return o.toString();
    }

    // ---------------------------------------------------------------- 인자 도우미

    static String str(JsonObject a, String k, String def) {
        JsonElement e = a.get(k);
        if (e == null || e.isJsonNull()) return def;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    static String req(JsonObject a, String k) {
        String s = str(a, k, null);
        if (s == null || s.isBlank()) throw new ToolError("Missing required argument '" + k + "'");
        return s;
    }

    static double num(JsonObject a, String k, double def) {
        JsonElement e = a.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static boolean bool(JsonObject a, String k, boolean def) {
        JsonElement e = a.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        return e.getAsBoolean();
    }

    static List<String> strList(JsonObject a, String k) {
        List<String> out = new ArrayList<>();
        JsonElement e = a.get(k);
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    static ServerPlayer playerByName(Ctx ctx, String name) {
        if (name == null || name.isEmpty() || name.equals("owner") || name.equals("me")) return ctx.player();
        ServerPlayer p = ctx.eng.playerByName(name);
        if (p == null) throw new ToolError("Player '" + name + "' is not online");
        return p;
    }

    static ServerPlayer requirePlayer(Ctx ctx) {
        ServerPlayer p = ctx.player();
        if (p == null) throw new ToolError("The player is offline; you can't show UI to them right now");
        return p;
    }

    static Wish requireWish(Ctx ctx) {
        if (ctx.wish == null) throw new ToolError("There is no wish in this turn");
        return ctx.wish;
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static double[] center(Ctx ctx, JsonObject a) {
        JsonElement c = a.get("center");
        if (c != null && c.isJsonObject()) {
            JsonObject o = c.getAsJsonObject();
            return new double[]{num(o, "x", 0), num(o, "y", 64), num(o, "z", 0)};
        }
        String who = c != null && c.isJsonPrimitive() ? c.getAsString() : null;
        ServerPlayer p = playerByName(ctx, who);
        if (p == null) throw new ToolError("No center given and the player is offline");
        return new double[]{p.getX(), p.getY(), p.getZ()};
    }

    // ---------------------------------------------------------------- 스크립트 만들기 공통

    static ScriptRecord draft(Ctx ctx, String name, String code, JsonObject a) {
        ScriptRecord r = new ScriptRecord();
        r.id = "s-" + UUID.randomUUID().toString().substring(0, 8);
        r.name = McUtil.clean(name == null ? "wish-script" : name, 60);
        r.description = McUtil.clean(str(a, "description", ""), 300);
        r.abilityLabel = McUtil.clean(str(a, "ability_label", ""), 40);
        r.code = code;
        r.wishId = ctx.wish == null ? null : ctx.wish.id;
        r.requestId = ctx.turnId;
        r.created = System.currentTimeMillis();
        r.autonomous = ctx.req.kind() == TurnRequest.Kind.AUTONOMOUS;
        r.lasting = bool(a, "lasting", false);
        double dur = num(a, "duration_seconds", -1);
        r.durationTicks = dur <= 0 ? -1 : (long) (Math.min(86400.0 * 30, dur) * 20);
        int maxMin = AiConfig.get().scriptDefaultMaxMinutes;
        if (r.durationTicks < 0 && maxMin > 0) r.durationTicks = maxMin * 1200L;
        ServerPlayer p = ctx.player();
        String ownerName = str(a, "owner", null);
        if (ownerName != null && !ownerName.isEmpty() && !ownerName.equals("owner") && !ownerName.equals("world")) p = playerByName(ctx, ownerName);
        if ("world".equals(ownerName)) p = null;
        if (p != null) {
            r.owner = p.getUUID().toString();
            r.ownerName = p.getGameProfile().name();
            r.dimension = McUtil.dimId(p.level());
            r.anchor = new double[]{p.getX(), p.getY(), p.getZ()};
        } else if (ctx.req.player() != null) {
            r.owner = ctx.req.player().toString();
            var prof = ctx.eng.state().players.get(r.owner);
            r.ownerName = prof == null ? "?" : prof.name;
        }
        JsonElement anchor = a.get("anchor");
        if (anchor != null && anchor.isJsonObject()) {
            JsonObject ao = anchor.getAsJsonObject();
            r.anchor = new double[]{num(ao, "x", 0), num(ao, "y", 64), num(ao, "z", 0)};
            if (ao.has("dimension")) r.dimension = str(ao, "dimension", r.dimension);
        }
        return r;
    }

    static List<ScriptValidator.SimEvent> sims(JsonObject a) {
        List<ScriptValidator.SimEvent> out = new ArrayList<>();
        JsonElement e = a.get("test_events");
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (!x.isJsonObject()) continue;
                JsonObject o = x.getAsJsonObject();
                String ev = str(o, "event", null);
                if (ev == null) continue;
                out.add(new ScriptValidator.SimEvent(ev, o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null));
                if (out.size() >= 12) break;
            }
        }
        return out;
    }

    private static JsonObject activate(Ctx ctx, ScriptRecord r, JsonObject a, boolean isUpdate) {
        if (ctx.validations++ > AiConfig.get().scriptValidationAttempts * 3) {
            throw new ToolError("Too many script attempts this turn. Offer the player a simpler alternative.");
        }
        AiConfig cfg = AiConfig.get();
        if (!isUpdate) {
            int mine = 0, total = 0;
            for (ScriptRecord x : ctx.eng.state().scripts.values()) {
                if (!x.state.live()) continue;
                total++;
                if (r.owner != null && r.owner.equals(x.owner)) mine++;
            }
            if (r.owner != null && mine >= cfg.maxActiveScriptsPerPlayer) {
                throw new ToolError("This player already has " + mine + " active scripts (limit " + cfg.maxActiveScriptsPerPlayer + "). Stop an old one with stop_script, or reuse/update an existing script.");
            }
            if (total >= cfg.maxActiveScriptsTotal) throw new ToolError("Server-wide script limit reached");
            if (r.autonomous) {
                int auto = 0;
                for (ScriptRecord x : ctx.eng.state().scripts.values()) if (x.state.live() && x.autonomous) auto++;
                if (auto >= cfg.autonomyMaxActiveEvents) throw new ToolError("Too many autonomous events are active (limit " + cfg.autonomyMaxActiveEvents + ")");
            }
        }
        ScriptValidator.Report rep = ScriptValidator.validate(r, sims(a), (int) Math.min(2400, num(a, "simulate_ticks", 0)));
        JsonObject out = new JsonObject();
        out.add("validation", rep.toJson());
        if (!rep.ok) {
            out.addProperty("activated", false);
            out.addProperty("hint", "Fix the errors above and call again.");
            return out;
        }
        r.apiCalls = new ArrayList<>(rep.apiCalls);
        r.subscriptions = new ArrayList<>(rep.events);
        ctx.eng.state().scripts.put(r.id, r);
        if (ctx.wish != null && !ctx.wish.scriptIds.contains(r.id)) ctx.wish.scriptIds.add(r.id);
        if (rep.approvalNeeded) {
            r.state = ScriptRecord.State.PENDING_APPROVAL;
            r.stateReason = String.join("; ", rep.approvalReasons);
            ctx.eng.notifyAdmins("스크립트 승인 요청: " + r.id + " (" + r.name + ", " + r.ownerName + ") — " + r.stateReason + "  /wishai approve " + r.id);
            if (ctx.wish != null) ctx.eng.wishes().transition(ctx.wish, Wish.State.AWAITING_APPROVAL, "script needs admin approval");
            out.addProperty("activated", false);
            out.addProperty("script_id", r.id);
            out.addProperty("pending_admin_approval", true);
            out.addProperty("hint", "An admin must approve this script. Tell the player the magic needs the stars' (admins') blessing first.");
            return out;
        }
        var act = ctx.eng.scripts().activate(r, false);
        out.addProperty("activated", act.ok());
        out.addProperty("script_id", r.id);
        if (!act.ok()) {
            out.addProperty("error", act.error());
            out.addProperty("hint", "The script failed when it started for real. Fix and use update_script or activate_script again.");
        } else {
            ctx.createdScripts.add(r.id);
            if (!act.log().isEmpty()) {
                JsonArray l = new JsonArray();
                for (String s : act.log()) l.add(s);
                out.add("console", l);
            }
            if (ctx.wish != null && r.durationTicks != 2400 && !ctx.wish.state.terminal() && ctx.wish.state != Wish.State.ACTIVE && !r.lasting) {
                ctx.eng.wishes().transition(ctx.wish, Wish.State.ACTIVE, "script activated");
            }
            if (r.lasting) {
                MemoryRecord m = new MemoryRecord();
                m.kind = "ability";
                m.scope = r.owner == null ? MemoryRecord.Scope.WORLD : MemoryRecord.Scope.PLAYER;
                m.owner = r.owner;
                m.ownerName = r.ownerName;
                m.scriptId = r.id;
                m.wishId = r.wishId;
                m.text = "새벽별이 " + (r.ownerName == null ? "세계" : r.ownerName) + "에게 '" + (r.abilityLabel.isEmpty() ? r.name : r.abilityLabel) + "'을(를) 내렸다"
                        + (r.description.isEmpty() ? "" : ": " + r.description);
                m.importance = 6;
                ctx.eng.memory().add(m, System.currentTimeMillis());
                if (ctx.wish != null) ctx.wish.memoryIds.add(m.id);
            }
        }
        return out;
    }

    // ================================================================= 정의

    static {
        // ---------------- 세계 조회
        def("get_world_context", "World time, weather, countdown and online players. Usually already in the context message.",
                "{\"type\":\"object\",\"properties\":{}}",
                (ctx, a) -> ContextBuilder.world(ctx.eng));

        def("get_player_context", "Detailed state of a player: position, biome, health, inventory summary, effects, nearby entities/players.",
                "{\"type\":\"object\",\"properties\":{\"player\":{\"type\":\"string\",\"description\":\"player name; omit for the wishing player\"}}}",
                (ctx, a) -> {
                    ServerPlayer p = playerByName(ctx, str(a, "player", null));
                    if (p == null) throw new ToolError("Player is offline");
                    return ContextBuilder.player(ctx.eng, p, true);
                });

        def("inspect_entities", "List entities near a player or position (uuid, type, name, position, whether a wish script owns it).",
                "{\"type\":\"object\",\"properties\":{\"center\":{\"description\":\"player name or {x,y,z}; default the wishing player\"},"
                        + "\"radius\":{\"type\":\"number\",\"description\":\"max 64\"},\"type\":{\"type\":\"string\",\"description\":\"entity type filter e.g. chicken\"}}}",
                (ctx, a) -> {
                    double[] c = center(ctx, a);
                    double r = Math.max(1, Math.min(64, num(a, "radius", 16)));
                    String type = str(a, "type", null);
                    String want = type == null ? null : (type.contains(":") ? type : "minecraft:" + type);
                    ServerPlayer p = ctx.player();
                    ServerLevel l = p != null ? (ServerLevel) p.level() : ctx.eng.server().overworld();
                    List<Entity> list = l.getEntities((Entity) null, new AABB(c[0] - r, c[1] - r, c[2] - r, c[0] + r, c[1] + r, c[2] + r),
                            e -> e.isAlive() && !(e instanceof Player) && (want == null || McUtil.entityId(e.getType()).equals(want)));
                    list.sort((x, y) -> Double.compare(x.distanceToSqr(c[0], c[1], c[2]), y.distanceToSqr(c[0], c[1], c[2])));
                    JsonArray arr = new JsonArray();
                    for (int i = 0; i < Math.min(40, list.size()); i++) {
                        Entity e = list.get(i);
                        JsonObject j = new JsonObject();
                        j.addProperty("uuid", e.getUUID().toString());
                        j.addProperty("type", McUtil.entityId(e.getType()).replace("minecraft:", ""));
                        if (e.hasCustomName()) j.addProperty("name", e.getName().getString());
                        j.addProperty("pos", (int) e.getX() + "," + (int) e.getY() + "," + (int) e.getZ());
                        for (String tag : e.entityTags()) if (tag.startsWith("wishai_") && !tag.equals("wishai_fx")) j.addProperty("script", tag.substring(7));
                        arr.add(j);
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("count", list.size());
                    o.add("entities", arr);
                    return o;
                });

        def("inspect_region", "Summarize blocks around a position (radius <= 8): counts of block types and notable blocks with positions.",
                "{\"type\":\"object\",\"properties\":{\"center\":{\"description\":\"player name or {x,y,z}\"},\"radius\":{\"type\":\"number\"}}}",
                (ctx, a) -> {
                    double[] c = center(ctx, a);
                    int r = (int) Math.max(1, Math.min(8, num(a, "radius", 5)));
                    ServerPlayer p = ctx.player();
                    ServerLevel l = p != null ? (ServerLevel) p.level() : ctx.eng.server().overworld();
                    Map<String, Integer> counts = new TreeMap<>();
                    JsonArray notable = new JsonArray();
                    BlockPos cp = BlockPos.containing(c[0], c[1], c[2]);
                    for (BlockPos bp : BlockPos.betweenClosed(cp.offset(-r, -r, -r), cp.offset(r, r, r))) {
                        if (!l.isLoaded(bp)) continue;
                        BlockState st = l.getBlockState(bp);
                        if (st.isAir()) continue;
                        String id = McUtil.blockId(st).replace("minecraft:", "");
                        counts.merge(id, 1, Integer::sum);
                        if (st.hasBlockEntity() && notable.size() < 20) notable.add(id + " @" + bp.getX() + "," + bp.getY() + "," + bp.getZ());
                    }
                    JsonObject o = new JsonObject();
                    JsonObject cj = new JsonObject();
                    counts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(30).forEach(e -> cj.addProperty(e.getKey(), e.getValue()));
                    o.add("block_counts", cj);
                    o.add("notable_blocks", notable);
                    o.addProperty("surface_y_at_center", l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cp.getX(), cp.getZ()));
                    return o;
                });

        def("get_active_content", "REAL current state of wishes, scripts (with state), quests and HUD statuses — check this before claiming an old ability/creature still exists.",
                "{\"type\":\"object\",\"properties\":{\"player\":{\"type\":\"string\",\"description\":\"player name, or 'all' for the whole world\"}}}",
                (ctx, a) -> {
                    String who = str(a, "player", null);
                    String uuid;
                    if ("all".equals(who)) uuid = null;
                    else if (who == null || who.isEmpty()) uuid = ctx.playerUuid();
                    else {
                        var prof = ctx.eng.state().findPlayer(who);
                        if (prof == null) throw new ToolError("Unknown player " + who);
                        uuid = prof.uuid;
                    }
                    return ContextBuilder.activeContent(ctx.eng, uuid);
                });

        // ---------------- 기억
        def("search_memories", "Search your memories (private memories of the current player + public world memories). Results include current_status checked against the real world.",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"},"
                        + "\"kind\":{\"type\":\"string\",\"description\":\"optional filter: wish, outcome, event, place, entity, item, ability, rule, fact, preference, summary\"},"
                        + "\"thread\":{\"type\":\"string\",\"description\":\"return the whole story thread with this name\"}},\"required\":[\"query\"]}",
                (ctx, a) -> {
                    String thread = str(a, "thread", null);
                    JsonArray arr = new JsonArray();
                    long now = System.currentTimeMillis();
                    if (thread != null && !thread.isEmpty()) {
                        for (MemoryRecord r : ctx.eng.memory().byThread(thread)) {
                            if (r.scope == MemoryRecord.Scope.PLAYER && !r.owner.equals(ctx.playerUuid())) continue;
                            arr.add(ContextBuilder.memoryJson(ctx.eng, r, now));
                        }
                    } else {
                        String kind = str(a, "kind", null);
                        for (MemoryStore.Hit h : ctx.eng.memory().search(req(a, "query"), ctx.playerUuid(), (int) Math.max(1, Math.min(20, num(a, "limit", 8))),
                                kind == null ? null : java.util.Set.of(kind), true, now)) {
                            arr.add(ContextBuilder.memoryJson(ctx.eng, h.record(), now));
                        }
                    }
                    JsonObject o = new JsonObject();
                    o.add("memories", arr);
                    if (arr.isEmpty()) o.addProperty("note", "No matching memories. Do not invent a shared past.");
                    return o;
                });

        def("store_memory", "Remember something important for the future (names you gave, promises, places, events). Duplicates are merged automatically.",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"kind\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\",\"enum\":[\"player\",\"world\"]},"
                        + "\"importance\":{\"type\":\"integer\",\"description\":\"1-10\"},\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                        + "\"thread\":{\"type\":\"string\",\"description\":\"story thread name to connect related events\"},"
                        + "\"related_to\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"memory ids this is connected to\"},"
                        + "\"entity_uuid\":{\"type\":\"string\"},\"script_id\":{\"type\":\"string\"},"
                        + "\"pos\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}}}},\"required\":[\"text\"]}",
                (ctx, a) -> {
                    MemoryRecord m = new MemoryRecord();
                    m.text = McUtil.clean(req(a, "text"), 500);
                    m.kind = str(a, "kind", "fact");
                    m.scope = "world".equals(str(a, "scope", "player")) || ctx.playerUuid() == null ? MemoryRecord.Scope.WORLD : MemoryRecord.Scope.PLAYER;
                    m.owner = ctx.playerUuid();
                    ServerPlayer p = ctx.player();
                    m.ownerName = p != null ? p.getGameProfile().name() : ctx.wish == null ? null : ctx.wish.playerName;
                    m.importance = (int) num(a, "importance", 5);
                    m.tags.addAll(strList(a, "tags").stream().limit(8).toList());
                    m.thread = str(a, "thread", null);
                    for (String l : strList(a, "related_to")) if (ctx.eng.memory().get(l) != null) m.links.add(l);
                    m.entityUuid = str(a, "entity_uuid", null);
                    m.scriptId = str(a, "script_id", null);
                    m.wishId = ctx.wish == null ? null : ctx.wish.id;
                    if (a.has("pos") && a.get("pos").isJsonObject()) {
                        JsonObject po = a.getAsJsonObject("pos");
                        m.pos = new int[]{(int) num(po, "x", 0), (int) num(po, "y", 0), (int) num(po, "z", 0)};
                        m.dimension = p != null ? McUtil.dimId(p.level()) : "minecraft:overworld";
                        if ("fact".equals(m.kind)) m.kind = "place";
                    }
                    MemoryRecord saved = ctx.eng.memory().add(m, System.currentTimeMillis());
                    if (ctx.wish != null && !ctx.wish.memoryIds.contains(saved.id)) ctx.wish.memoryIds.add(saved.id);
                    JsonObject o = ok();
                    o.addProperty("memory_id", saved.id);
                    o.addProperty("merged_with_existing", saved != m);
                    return o;
                });

        def("get_wish_history", "Past wishes of a player (or the whole world) with outcomes.",
                "{\"type\":\"object\",\"properties\":{\"player\":{\"type\":\"string\",\"description\":\"name, or 'all'\"},\"limit\":{\"type\":\"integer\"}}}",
                (ctx, a) -> {
                    String who = str(a, "player", null);
                    String uuid = "all".equals(who) ? null : who == null || who.isEmpty() ? ctx.playerUuid()
                            : ctx.eng.state().findPlayer(who) == null ? "?" : ctx.eng.state().findPlayer(who).uuid;
                    List<Wish> list = new ArrayList<>();
                    for (Wish w : ctx.eng.state().wishes.values()) if (uuid == null || uuid.equals(w.player)) list.add(w);
                    list.sort((x, y) -> Long.compare(y.created, x.created));
                    JsonArray arr = new JsonArray();
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < Math.min((int) Math.max(1, Math.min(30, num(a, "limit", 10))), list.size()); i++) {
                        Wish w = list.get(i);
                        JsonObject j = new JsonObject();
                        j.addProperty("id", w.id);
                        j.addProperty("player", w.playerName);
                        j.addProperty("wish", w.text);
                        j.addProperty("state", w.state.name());
                        j.addProperty("outcome", w.outcome == null ? "" : w.outcome);
                        j.addProperty("when", ContextBuilder.age(w.created, now));
                        arr.add(j);
                    }
                    JsonObject o = new JsonObject();
                    o.add("wishes", arr);
                    return o;
                });

        // ---------------- 스크립트
        def("get_script_api", "WishScript API reference. Topics: " + ApiDocs.TOPICS,
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}},\"required\":[\"topic\"]}",
                (ctx, a) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("doc", ApiDocs.topic(str(a, "topic", "overview")));
                    return o;
                });

        def("validate_script", "Check WishScript code WITHOUT changing the world: syntax, unknown names/API typos, a dry run of the top-level code, optional simulated events/timers. Returns errors with line:col and the effects it would have.",
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},"
                        + "\"test_events\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"event\":{\"type\":\"string\"},\"data\":{\"type\":\"object\"}}},"
                        + "\"description\":\"simulate events, e.g. [{event:'jump'}]; data overrides payload fields ('owner' = the wishing player)\"},"
                        + "\"simulate_ticks\":{\"type\":\"integer\",\"description\":\"also run timers for this many ticks (<=2400)\"}},\"required\":[\"code\"]}",
                (ctx, a) -> {
                    if (ctx.validations++ > AiConfig.get().scriptValidationAttempts * 3) throw new ToolError("Too many validation attempts this turn. Simplify the idea.");
                    ScriptRecord r = draft(ctx, "validation", req(a, "code"), a);
                    return ScriptValidator.validate(r, sims(a), (int) Math.min(2400, num(a, "simulate_ticks", 0))).toJson();
                });

        def("activate_script", "Validate and start a lasting WishScript program in the real world (abilities, rules, creatures, quest trackers). Re-runs automatically after server restarts.",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"},"
                        + "\"description\":{\"type\":\"string\",\"description\":\"what it does (for admins and your future self)\"},"
                        + "\"ability_label\":{\"type\":\"string\",\"description\":\"short Korean name shown to players\"},"
                        + "\"duration_seconds\":{\"type\":\"number\",\"description\":\"-1 = until stopped\"},"
                        + "\"lasting\":{\"type\":\"boolean\",\"description\":\"true = keeps running after the wish ends (ability/world rule); false = helper for this wish (quest tracker), cleaned up when the wish ends\"},"
                        + "\"owner\":{\"type\":\"string\",\"description\":\"player name the script belongs to; default the wishing player; 'world' for world rules\"},"
                        + "\"anchor\":{\"type\":\"object\",\"description\":\"center of the area the script may modify; default the owner's position\"},"
                        + "\"test_events\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"simulate_ticks\":{\"type\":\"integer\"}},\"required\":[\"name\",\"code\"]}",
                (ctx, a) -> activate(ctx, draft(ctx, req(a, "name"), req(a, "code"), a), a, false));

        def("run_script", "Run a short one-shot WishScript right now (give items, effects, fireworks, a little scene). It may use after()/every() for up to 2 minutes, then ends.",
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"code\"]}",
                (ctx, a) -> {
                    JsonObject args = a.deepCopy();
                    args.addProperty("duration_seconds", 120);
                    args.addProperty("lasting", false);
                    ScriptRecord r = draft(ctx, str(a, "name", "one-shot"), req(a, "code"), args);
                    r.keepEntitiesDefault = true;
                    JsonObject res = activate(ctx, r, args, false);
                    // 리스너·타이머가 없으면 바로 끝낸다
                    var inst = ctx.eng.scripts().instance(r.id);
                    if (inst != null && inst.listenerCount() == 0 && inst.activeTimers() == 0 && inst.rec.behaviours.isEmpty() && !ctx.eng.ui().hasPendingForScript(r.id)) {
                        ctx.eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "one-shot finished", false);
                    }
                    return res;
                });

        def("update_script", "Replace the code of an existing script (keeps its state, id and history). Use to fix or evolve an ability.",
                "{\"type\":\"object\",\"properties\":{\"script_id\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"},"
                        + "\"test_events\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}},\"required\":[\"script_id\",\"code\"]}",
                (ctx, a) -> {
                    ScriptRecord r = ctx.eng.state().scripts.get(req(a, "script_id"));
                    if (r == null) throw new ToolError("No script " + str(a, "script_id", ""));
                    if (r.owner != null && ctx.playerUuid() != null && !r.owner.equals(ctx.playerUuid()) && !r.autonomous) {
                        throw new ToolError("That script belongs to another player");
                    }
                    ScriptRecord trial = draft(ctx, r.name, req(a, "code"), a);
                    trial.id = r.id;
                    trial.owner = r.owner;
                    trial.ownerName = r.ownerName;
                    trial.anchor = r.anchor;
                    trial.dimension = r.dimension;
                    trial.wishId = r.wishId;
                    ScriptValidator.Report rep = ScriptValidator.validate(trial, sims(a), 0);
                    JsonObject out = new JsonObject();
                    out.add("validation", rep.toJson());
                    if (!rep.ok) {
                        out.addProperty("updated", false);
                        return out;
                    }
                    ScriptRecord.Version v = new ScriptRecord.Version();
                    v.version = r.version;
                    v.code = r.code;
                    v.time = System.currentTimeMillis();
                    v.reason = McUtil.clean(str(a, "reason", "update"), 200);
                    r.history.add(v);
                    while (r.history.size() > 10) r.history.remove(0);
                    var inst = ctx.eng.scripts().instance(r.id);
                    if (inst != null) ctx.eng.scripts().saveState(inst);
                    r.code = trial.code;
                    r.version++;
                    r.errors = 0;
                    r.apiCalls = new ArrayList<>(rep.apiCalls);
                    var act = ctx.eng.scripts().activate(r, true);
                    out.addProperty("updated", act.ok());
                    out.addProperty("version", r.version);
                    if (!act.ok()) out.addProperty("error", act.error());
                    return out;
                });

        def("stop_script", "Stop a running script (its spawned entities and temporary blocks are cleaned up; kept entities stay).",
                "{\"type\":\"object\",\"properties\":{\"script_id\":{\"type\":\"string\"},\"rollback_blocks\":{\"type\":\"boolean\"},\"reason\":{\"type\":\"string\"}},\"required\":[\"script_id\"]}",
                (ctx, a) -> {
                    ScriptRecord r = ctx.eng.state().scripts.get(req(a, "script_id"));
                    if (r == null) throw new ToolError("No script with that id");
                    if (r.owner != null && ctx.playerUuid() != null && !r.owner.equals(ctx.playerUuid()) && !r.autonomous) throw new ToolError("That script belongs to another player");
                    ctx.eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, McUtil.clean(str(a, "reason", "stopped by the spirit"), 200), bool(a, "rollback_blocks", false));
                    return ok();
                });

        // ---------------- UI
        def("show_dialogue", "Say an extra line immediately (e.g. before a long preparation). Your final 'say' is shown anyway.",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"mood\":{\"type\":\"string\"},\"player\":{\"type\":\"string\"}},\"required\":[\"text\"]}",
                (ctx, a) -> {
                    ServerPlayer p = playerByName(ctx, str(a, "player", null));
                    ctx.eng.ui().spiritLine(p, ctx.wish == null ? null : ctx.wish.id, McUtil.clean(req(a, "text"), 400), str(a, "mood", "neutral"));
                    return ok();
                });

        def("present_choices", "Show 2-6 choice buttons. The player's pick comes back to you as the next turn.",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"},\"options\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                        + "\"title\":{\"type\":\"string\"},\"timeout_seconds\":{\"type\":\"integer\"},\"player\":{\"type\":\"string\"}},\"required\":[\"prompt\",\"options\"]}",
                (ctx, a) -> {
                    ServerPlayer p = playerByName(ctx, str(a, "player", null));
                    if (p == null) throw new ToolError("Player offline");
                    List<String> opts = strList(a, "options").stream().map(s -> McUtil.clean(s, 80)).limit(6).toList();
                    if (opts.size() < 2) throw new ToolError("Need at least 2 options");
                    String prompt = McUtil.clean(req(a, "prompt"), 300);
                    Wish w = requireWish(ctx);
                    String id = ctx.eng.ui().choices(p, McUtil.clean(str(a, "title", ""), 40), prompt, new ArrayList<>(opts), (int) num(a, "timeout_seconds", 0), w.id,
                            ctx.eng.wishes().aiResponder(w.id, "choice", prompt));
                    JsonObject o = ok();
                    o.addProperty("interaction_id", id);
                    o.addProperty("note", "The answer will arrive as the next turn.");
                    return o;
                });

        def("request_text_input", "Show a text input box (riddles, names, answers). Optional countdown. The answer comes back as the next turn.",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"},\"placeholder\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},"
                        + "\"timeout_seconds\":{\"type\":\"integer\"},\"player\":{\"type\":\"string\"}},\"required\":[\"prompt\"]}",
                (ctx, a) -> {
                    ServerPlayer p = playerByName(ctx, str(a, "player", null));
                    if (p == null) throw new ToolError("Player offline");
                    String prompt = McUtil.clean(req(a, "prompt"), 300);
                    Wish w = requireWish(ctx);
                    String id = ctx.eng.ui().input(p, McUtil.clean(str(a, "title", ""), 40), prompt, McUtil.clean(str(a, "placeholder", ""), 60),
                            (int) num(a, "timeout_seconds", 0), w.id, ctx.eng.wishes().aiResponder(w.id, "text input", prompt));
                    JsonObject o = ok();
                    o.addProperty("interaction_id", id);
                    return o;
                });

        def("propose_contract", "Offer a contract card (terms, reward, price) with Accept/Decline buttons. Accept/decline comes back as the next turn; the player may also negotiate by typing.",
                "{\"type\":\"object\",\"properties\":{\"terms\":{\"type\":\"string\",\"description\":\"what the player must do\"},\"reward\":{\"type\":\"string\"},"
                        + "\"penalty\":{\"type\":\"string\",\"description\":\"price or side effect, if any\"},\"time_limit_seconds\":{\"type\":\"integer\"}},\"required\":[\"terms\",\"reward\"]}",
                (ctx, a) -> {
                    ServerPlayer p = requirePlayer(ctx);
                    Wish w = requireWish(ctx);
                    ctx.eng.ui().cancelInteractionsOfWish(w.id);
                    Wish.Contract c = w.contract == null ? new Wish.Contract() : w.contract;
                    c.id = "c-" + UUID.randomUUID().toString().substring(0, 6);
                    c.terms = McUtil.clean(req(a, "terms"), 400);
                    c.reward = McUtil.clean(req(a, "reward"), 200);
                    c.penalty = McUtil.clean(str(a, "penalty", ""), 200);
                    c.timeLimitSeconds = (int) Math.max(0, num(a, "time_limit_seconds", 0));
                    c.accepted = false;
                    c.declined = false;
                    c.proposedAt = System.currentTimeMillis();
                    c.revision++;
                    w.contract = c;
                    ctx.eng.wishes().transition(w, Wish.State.AWAITING_ACCEPTANCE, "contract proposed");
                    String id = ctx.eng.ui().contract(p, w, ctx.eng.wishes().aiResponder(w.id, "contract", c.terms));
                    JsonObject o = ok();
                    o.addProperty("interaction_id", id);
                    return o;
                });

        def("request_consent", "Ask ANOTHER player to agree to be affected by this wish (needed before taking their items, teleporting them, harmful effects). Their answer comes back as a turn.",
                "{\"type\":\"object\",\"properties\":{\"player\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}},\"required\":[\"player\",\"text\"]}",
                (ctx, a) -> {
                    ServerPlayer target = playerByName(ctx, req(a, "player"));
                    Wish w = requireWish(ctx);
                    String text = McUtil.clean(req(a, "text"), 300);
                    String id = ctx.eng.ui().consent(target, w.id, text, ctx.eng.wishes().aiResponder(w.id, "consent from " + target.getGameProfile().name(), text));
                    JsonObject o = ok();
                    o.addProperty("interaction_id", id);
                    return o;
                });

        def("create_quest", "Create a quest panel/HUD entry. You MUST also activate a tracker script that updates its objectives (quests.get(id).add(objId)) — quests do not progress by themselves.",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"conditions\":{\"type\":\"string\"},"
                        + "\"reward\":{\"type\":\"string\"},\"failure\":{\"type\":\"string\"},"
                        + "\"objectives\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"target\":{\"type\":\"number\"},\"optional\":{\"type\":\"boolean\"}},\"required\":[\"id\",\"text\"]}},"
                        + "\"time_limit_seconds\":{\"type\":\"integer\"},\"participants\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"priority\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"title\",\"objectives\"]}",
                (ctx, a) -> {
                    Wish w = ctx.wish;
                    Quest q = new Quest();
                    q.id = "q-" + UUID.randomUUID().toString().substring(0, 8);
                    q.wishId = w == null ? null : w.id;
                    q.owner = ctx.playerUuid();
                    ServerPlayer p = ctx.player();
                    q.ownerName = p != null ? p.getGameProfile().name() : w == null ? null : w.playerName;
                    q.title = McUtil.clean(req(a, "title"), 48);
                    q.description = McUtil.clean(str(a, "description", ""), 400);
                    q.conditions = McUtil.clean(str(a, "conditions", ""), 300);
                    q.reward = McUtil.clean(str(a, "reward", ""), 160);
                    q.failure = McUtil.clean(str(a, "failure", ""), 160);
                    q.priority = (int) Math.max(0, Math.min(10, num(a, "priority", 5)));
                    for (String name : strList(a, "participants")) {
                        var prof = ctx.eng.state().findPlayer(name);
                        if (prof != null && !prof.uuid.equals(q.owner)) q.participants.add(prof.uuid);
                    }
                    JsonElement objs = a.get("objectives");
                    if (objs == null || !objs.isJsonArray() || objs.getAsJsonArray().isEmpty()) throw new ToolError("objectives required");
                    int n = 0;
                    for (JsonElement e : objs.getAsJsonArray()) {
                        if (!e.isJsonObject() || ++n > 8) continue;
                        JsonObject oj = e.getAsJsonObject();
                        Quest.Objective ob = new Quest.Objective();
                        ob.id = McUtil.clean(str(oj, "id", "o" + n), 32);
                        ob.text = McUtil.clean(str(oj, "text", ob.id), 120);
                        ob.target = Math.max(1, Math.min(100000, num(oj, "target", 1)));
                        ob.optional = bool(oj, "optional", false);
                        q.objectives.add(ob);
                    }
                    q.startTick = ctx.eng.tick();
                    double lim = num(a, "time_limit_seconds", 0);
                    if (lim > 0) q.deadlineTick = q.startTick + (long) (Math.min(86400 * 7, lim) * 20);
                    q.created = System.currentTimeMillis();
                    ctx.eng.quests().create(q);
                    JsonObject o = ok();
                    o.addProperty("quest_id", q.id);
                    o.addProperty("next", "Now activate a tracker script: quests.get('" + q.id + "').add('<objective id>') on the right events; set its script with lasting:false.");
                    return o;
                });

        def("update_quest", "Manually change a quest: set objective progress or finish it.",
                "{\"type\":\"object\",\"properties\":{\"quest_id\":{\"type\":\"string\"},\"objective_id\":{\"type\":\"string\"},\"progress\":{\"type\":\"number\"},"
                        + "\"state\":{\"type\":\"string\",\"enum\":[\"completed\",\"failed\",\"cancelled\"]},\"note\":{\"type\":\"string\"},\"script_id\":{\"type\":\"string\",\"description\":\"link the tracker script\"}},\"required\":[\"quest_id\"]}",
                (ctx, a) -> {
                    Quest q = ctx.eng.state().quests.get(req(a, "quest_id"));
                    if (q == null) throw new ToolError("No such quest");
                    String sid = str(a, "script_id", null);
                    if (sid != null && ctx.eng.state().scripts.containsKey(sid)) { q.scriptId = sid; ctx.eng.ui().pushQuest(q); }
                    String oid = str(a, "objective_id", null);
                    if (oid != null && a.has("progress")) ctx.eng.quests().progress(q.id, oid, num(a, "progress", 0), false);
                    String st = str(a, "state", null);
                    if (st != null) ctx.eng.quests().finish(q.id, switch (st) {
                        case "completed" -> Quest.State.COMPLETED;
                        case "failed" -> Quest.State.FAILED;
                        default -> Quest.State.CANCELLED;
                    }, McUtil.clean(str(a, "note", ""), 200));
                    return ctx.eng.ui().questJson(q);
                });

        def("show_notification", "Toast notification in the wish UI style.",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"kind\":{\"type\":\"string\",\"enum\":[\"info\",\"success\",\"warning\",\"mystic\"]},"
                        + "\"player\":{\"type\":\"string\",\"description\":\"name or 'all'\"}},\"required\":[\"text\"]}",
                (ctx, a) -> {
                    String who = str(a, "player", null);
                    List<ServerPlayer> ps = "all".equals(who) ? ctx.eng.server().getPlayerList().getPlayers() : java.util.Collections.singletonList(playerByName(ctx, who));
                    for (ServerPlayer p : ps) if (p != null) ctx.eng.ui().notify(p, McUtil.clean(str(a, "title", ""), 40), McUtil.clean(req(a, "text"), 300), str(a, "kind", "info"), 6);
                    return ok();
                });

        def("show_cinematic", "Letterboxed cinematic title card (wish granted, a legend begins…).",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"subtitle\":{\"type\":\"string\"},"
                        + "\"style\":{\"type\":\"string\",\"enum\":[\"wish\",\"dawn\",\"mystic\",\"celebrate\",\"ominous\"]},\"seconds\":{\"type\":\"integer\"},"
                        + "\"player\":{\"type\":\"string\",\"description\":\"name or 'all'\"}},\"required\":[\"title\"]}",
                (ctx, a) -> {
                    String who = str(a, "player", null);
                    List<ServerPlayer> ps = "all".equals(who) ? ctx.eng.server().getPlayerList().getPlayers() : java.util.Collections.singletonList(playerByName(ctx, who));
                    for (ServerPlayer p : ps) if (p != null) ctx.eng.ui().cinematic(p, McUtil.clean(req(a, "title"), 60), McUtil.clean(str(a, "subtitle", ""), 100), str(a, "style", "wish"), (int) num(a, "seconds", 4));
                    return ok();
                });

        // ---------------- 소원 관리
        def("set_wish_state", "Move the wish through its lifecycle. completed = granted; rejected = refused; failed; cancelled; negotiating; awaiting_acceptance; preparing; active.",
                "{\"type\":\"object\",\"properties\":{\"state\":{\"type\":\"string\",\"enum\":[\"negotiating\",\"awaiting_acceptance\",\"preparing\",\"active\",\"completed\",\"failed\",\"rejected\",\"cancelled\"]},"
                        + "\"summary\":{\"type\":\"string\",\"description\":\"one sentence: what was granted/agreed (stored as memory)\"}},\"required\":[\"state\"]}",
                (ctx, a) -> {
                    Wish w = requireWish(ctx);
                    Wish.State s;
                    try {
                        s = Wish.State.valueOf(req(a, "state").toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new ToolError("Unknown state");
                    }
                    String sum = McUtil.clean(str(a, "summary", ""), 300);
                    if (!sum.isEmpty()) {
                        w.summary = sum;
                        if (s.terminal()) w.outcome = sum;
                    }
                    ctx.eng.wishes().transition(w, s, sum.isEmpty() ? "set by spirit" : sum);
                    JsonObject o = ok();
                    o.addProperty("state", w.state.name());
                    return o;
                });

        def("get_wish_state", "Current wish record: state, contract, linked scripts/quests, consents.",
                "{\"type\":\"object\",\"properties\":{}}",
                (ctx, a) -> {
                    Wish w = requireWish(ctx);
                    JsonObject o = new JsonObject();
                    o.addProperty("id", w.id);
                    o.addProperty("text", w.text);
                    o.addProperty("state", w.state.name());
                    JsonArray scripts = new JsonArray();
                    for (String sid : w.scriptIds) {
                        ScriptRecord r = ctx.eng.state().scripts.get(sid);
                        if (r != null) scripts.add(ContextBuilder.scriptSummary(ctx.eng, r));
                    }
                    o.add("scripts", scripts);
                    JsonArray qs = new JsonArray();
                    for (String qid : w.questIds) {
                        Quest q = ctx.eng.state().quests.get(qid);
                        if (q != null) qs.add(ctx.eng.ui().questJson(q));
                    }
                    o.add("quests", qs);
                    JsonArray hist = new JsonArray();
                    for (Wish.Transition t : w.history) hist.add(t.from + "→" + t.to + ": " + t.reason);
                    o.add("history", hist);
                    return o;
                });
    }

    public static EntityRef unused() {
        return null;
    }

    public static UiService.Responder none() {
        return null;
    }
}
