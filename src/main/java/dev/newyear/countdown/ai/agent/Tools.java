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
        /** search_memories 질의의 임베딩 (작업 스레드가 미리 만들어 둔다; 없으면 키워드 검색만). */
        public volatile float[] queryVector;

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

    /** 도구 인자 JSON 에서 query 문자열만 꺼낸다 (작업 스레드에서 임베딩용). */
    public static String queryArg(String argsJson) {
        try {
            JsonElement el = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return el.isJsonObject() ? str(el.getAsJsonObject(), "query", "") : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 이름(또는 UUID)으로 알려진 플레이어 프로필. 없으면 ToolError. */
    static dev.newyear.countdown.ai.AiState.PlayerProfile knownPlayer(Ctx ctx, String name) {
        var prof = ctx.eng.state().findPlayer(name);
        if (prof == null) throw new ToolError("Unknown player '" + name + "' (they must have joined this server before)");
        return prof;
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
        r.code = dev.newyear.countdown.ai.script.ContentLibrary.expand(code,ctx.playerUuid());
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

    /** 플레이어가 빈 소원이고, 그 대가(수락된 계약 또는 완료된 퀘스트)가 아직 없는지. */
    static boolean conditionMissing(Ctx ctx) {
        Wish w = ctx.wish;
        if (w == null || !List.of("chat", "command", "lantern").contains(w.origin)) return false;   // 자율 사건·스크립트 이야기는 제외
        if (w.contract != null && w.contract.accepted) return false;
        for (String qid : w.questIds) {
            Quest q = ctx.eng.state().quests.get(qid);
            if (q != null && q.state == Quest.State.COMPLETED) return false;
        }
        return true;
    }

    private static JsonObject activate(Ctx ctx, ScriptRecord r, JsonObject a, boolean isUpdate) {
        if (ctx.validations++ > AiConfig.get().scriptValidationAttempts * 3) {
            throw new ToolError("Too many script attempts this turn. Offer the player a simpler alternative.");
        }
        AiConfig cfg = AiConfig.get();
        if (!isUpdate && r.lasting && cfg.requireConditionForLastingAbilities && conditionMissing(ctx)) {
            throw new ToolError("A permanent ability (lasting:true) is never free. First set a condition worth the ability: "
                    + "propose_contract (the player must accept it), or create_quest + a tracker script, and grant the ability only after the "
                    + "contract is accepted / the quest is completed. A temporary effect with duration_seconds and lasting:false is allowed now.");
        }
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
        r.preview = new ArrayList<>(rep.planned);
        ctx.eng.state().scripts.put(r.id, r);
        if (ctx.wish != null && !ctx.wish.scriptIds.contains(r.id)) ctx.wish.scriptIds.add(r.id);
        if (rep.approvalNeeded) {
            r.state = ScriptRecord.State.PENDING_APPROVAL;
            r.stateReason = String.join("; ", rep.approvalReasons);
            ctx.eng.notifyAdmins("스크립트 승인 요청: " + r.id + " (" + r.name + ", " + r.ownerName + ") — " + r.stateReason
                    + (r.preview.isEmpty() ? "" : " · 블록 " + r.preview.size() + "칸 미리보기: /wishai script " + r.id + " preview") + "  /wishai approve " + r.id);
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
            if (ctx.wish != null && !r.keepEntitiesDefault && !ctx.wish.state.terminal() && ctx.wish.state != Wish.State.ACTIVE && !r.lasting
                    && !(ctx.wish.buildPlan!=null && ctx.wish.buildPlan.status.equals("WAITING_APPROVAL"))) {
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
        def("get_capabilities", "Discover current WishScript APIs, interceptable events, reusable content and native extension status before deciding a wish is impossible.",
                "{\"type\":\"object\",\"properties\":{}}", (ctx,a)-> {
                    JsonObject out=new JsonObject();out.addProperty("runtime", "wishai-v5");
                    out.addProperty("topics",ApiDocs.TOPICS);
                    out.add("events",new com.google.gson.Gson().toJsonTree(dev.newyear.countdown.ai.script.EventTypes.ALL));
                    out.addProperty("nativeWorkflow","draft_extension -> compile -> operator isolated tests -> exact SHA-256 approval -> installed or staged restart. Never claim a compiled draft is usable.");
                    JsonArray modules=new JsonArray();for(var d:ctx.eng.state().extensions.values())if(java.util.Objects.equals(d.owner,ctx.playerUuid())||"INSTALLED".equals(d.status)||"INSTALLED_RESTART".equals(d.status)){
                        JsonObject j=new JsonObject();j.addProperty("id",d.id);j.addProperty("name",d.name);j.addProperty("status",d.status);j.addProperty("restartRequired",d.restartRequired);modules.add(j);
                    }out.add("extensions",modules);return out;
                });
        def("publish_content", "Save a reusable WishScript factory body. params contains arguments. Returns an immutable id; import with // @use exact-id@version then content.create(id,params).",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"}},\"required\":[\"name\",\"code\"]}",
                (ctx,a)->{var d=dev.newyear.countdown.ai.script.ContentLibrary.publish(ctx.playerUuid(),req(a,"name"),str(a,"description",""),req(a,"code"));ctx.eng.writeAsync();return new com.google.gson.Gson().toJsonTree(d);});
        def("get_content", "List your reusable factories, or fetch one immutable version including source.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",(ctx,a)->{
                    JsonArray out=new JsonArray();String id=str(a,"id",null);for(var d:ctx.eng.state().content.values())if(java.util.Objects.equals(d.owner,ctx.playerUuid())&&(id==null||id.equals(d.id))){JsonObject j=new com.google.gson.Gson().toJsonTree(d).getAsJsonObject();if(id==null)j.remove("code");out.add(j);}return out;});
        def("draft_extension", "Generate and compile a native Java extension asynchronously when WishScript primitives cannot express the desired mechanic. Read get_script_api topic native first. Compilation does not install or execute generated code. Operator testing and hash approval are required.",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"class_name\":{\"type\":\"string\"},\"source\":{\"type\":\"string\"},\"resources\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}},\"required\":[\"name\",\"class_name\",\"source\"]}",
                (ctx,a)->{var d=ctx.eng.extensions().draft(ctx.playerUuid(),req(a,"name"),req(a,"class_name"),req(a,"source"),str(a,"description",""),a.has("resources")?a.getAsJsonObject("resources"):new JsonObject());JsonObject out=new JsonObject();out.addProperty("id",d.id);out.addProperty("status",d.status);return out;});
        def("get_extension", "Read your native extension compile/test/install status and diagnostic output. A draft never grants native permissions.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}",(ctx,a)->{
                    if(ctx.playerUuid()==null)throw new ToolError("A player context is required");JsonObject out=ctx.eng.extensions().status(ctx.playerUuid(),req(a,"id"));out.remove("source");out.remove("resources");return out;});
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
        def("search_memories", "Search your memories (private memories of the current player, memories other players shared with them, public world memories). Matches by words AND meaning. Results include current_status checked against the real world.",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"},"
                        + "\"kind\":{\"type\":\"string\",\"description\":\"optional filter: wish, outcome, event, place, entity, item, ability, rule, fact, preference, summary\"},"
                        + "\"thread\":{\"type\":\"string\",\"description\":\"return the whole story thread with this name\"}},\"required\":[\"query\"]}",
                (ctx, a) -> {
                    String thread = str(a, "thread", null);
                    JsonArray arr = new JsonArray();
                    long now = System.currentTimeMillis();
                    if (thread != null && !thread.isEmpty()) {
                        for (MemoryRecord r : ctx.eng.memory().byThread(thread)) {
                            if (!r.visibleTo(ctx.playerUuid())) continue;
                            arr.add(ContextBuilder.memoryJson(ctx.eng, r, now, ctx.playerUuid()));
                        }
                    } else {
                        String kind = str(a, "kind", null);
                        for (MemoryStore.Hit h : ctx.eng.memory().search(req(a, "query"), ctx.queryVector, ctx.playerUuid(), (int) Math.max(1, Math.min(20, num(a, "limit", 8))),
                                kind == null ? null : java.util.Set.of(kind), true, now, java.util.Set.of())) {
                            JsonObject j = ContextBuilder.memoryJson(ctx.eng, h.record(), now, ctx.playerUuid());
                            if (!Double.isNaN(h.semantic())) j.addProperty("similarity", Math.round(h.semantic() * 100) / 100.0);
                            arr.add(j);
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
                        + "\"share_with\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"player names who may also know this private memory - ONLY when the current player asked you to share it with them\"},"
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
                    List<String> shareTo = strList(a, "share_with");
                    if (!shareTo.isEmpty() && m.scope == MemoryRecord.Scope.PLAYER) {
                        var me = ctx.eng.state().players.get(m.owner);
                        if (me != null && me.memoryLocked) throw new ToolError("This player has locked their memories; private memories can't be shared. Store it without share_with.");
                        for (String n : shareTo) {
                            var prof = knownPlayer(ctx, n);
                            if (!prof.uuid.equals(m.owner) && !m.sharedWith.contains(prof.uuid)) m.sharedWith.add(prof.uuid);
                        }
                    }
                    MemoryRecord saved = ctx.eng.memory().add(m, System.currentTimeMillis());
                    if (ctx.wish != null && !ctx.wish.memoryIds.contains(saved.id)) ctx.wish.memoryIds.add(saved.id);
                    JsonObject o = ok();
                    o.addProperty("memory_id", saved.id);
                    o.addProperty("merged_with_existing", saved != m);
                    if (saved.shared()) o.addProperty("shared_with", ContextBuilder.sharedNames(ctx.eng, saved));
                    return o;
                });

        def("share_memory", "Share the CURRENT player's private memory (or a whole story thread) with another player, so you may talk about it with them too. "
                        + "Only do this when the current player explicitly asks (e.g. 'tell Minsu about our secret'). You cannot share other players' memories.",
                "{\"type\":\"object\",\"properties\":{\"memory_id\":{\"type\":\"string\"},\"thread\":{\"type\":\"string\",\"description\":\"share every memory of this story thread instead\"},"
                        + "\"with\":{\"type\":\"string\",\"description\":\"player name\"},\"revoke\":{\"type\":\"boolean\",\"description\":\"true = stop sharing instead\"}},\"required\":[\"with\"]}",
                (ctx, a) -> {
                    String me = ctx.playerUuid();
                    if (me == null || ctx.req.kind() == TurnRequest.Kind.AUTONOMOUS || ctx.req.kind() == TurnRequest.Kind.SCRIPT) {
                        throw new ToolError("Only the memory's owner, speaking to you right now, can ask to share it");
                    }
                    var myProf = ctx.eng.state().players.get(me);
                    var other = knownPlayer(ctx, req(a, "with"));
                    if (other.uuid.equals(me)) throw new ToolError("That is the same player");
                    String id = str(a, "memory_id", null), thread = str(a, "thread", null);
                    boolean revoke = bool(a, "revoke", false);
                    if (!revoke && myProf != null && myProf.memoryLocked) throw new ToolError("This player has locked their memories (/wish memory unlock to allow sharing)");
                    JsonObject o = ok();
                    if (thread != null && !thread.isBlank()) {
                        int n = 0;
                        if (revoke) {
                            for (MemoryRecord r : ctx.eng.memory().byThread(thread)) if (me.equals(r.owner)) n += ctx.eng.memory().unshare(r.id, other.uuid, me);
                        } else n = ctx.eng.memory().shareThread(me, thread, other.uuid);
                        o.addProperty(revoke ? "revoked" : "shared", n);
                        return o;
                    }
                    if (id == null) throw new ToolError("Give memory_id or thread");
                    if (revoke) {
                        o.addProperty("revoked", ctx.eng.memory().unshare(id, other.uuid, me));
                        return o;
                    }
                    var res = ctx.eng.memory().share(id, other.uuid, me);
                    switch (res) {
                        case OK, ALREADY -> o.addProperty("shared_with", other.name);
                        case NOT_OWNER -> throw new ToolError("That memory belongs to someone else; only its owner can share it");
                        case NOT_PRIVATE -> throw new ToolError("That is a world memory; everyone already knows it");
                        case NOT_FOUND -> throw new ToolError("No memory with that id");
                        case SELF -> throw new ToolError("That is the same player");
                    }
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

        def("preview_blocks", "Show the player a translucent ghost preview of the blocks some WishScript code WOULD place (dry run; the world is not changed). "
                        + "Use before a contract/consent so the player can see a build first. Returns errors and a block summary.",
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"title\":{\"type\":\"string\",\"description\":\"short Korean label shown with the preview\"},"
                        + "\"seconds\":{\"type\":\"integer\",\"description\":\"how long the preview stays (default 30, max 120)\"},"
                        + "\"simulate_ticks\":{\"type\":\"integer\",\"description\":\"also include blocks placed by timers in this many ticks (<=2400)\"},"
                        + "\"player\":{\"type\":\"string\",\"description\":\"who sees it; default the wishing player\"}},\"required\":[\"code\"]}",
                (ctx, a) -> {
                    if (ctx.validations++ > AiConfig.get().scriptValidationAttempts * 3) throw new ToolError("Too many script attempts this turn");
                    ServerPlayer viewer = playerByName(ctx, str(a, "player", null));
                    if (viewer == null) throw new ToolError("The player is offline");
                    Wish buildWish=requireWish(ctx);
                    int secs=(int)Math.max(5,Math.min(120,num(a,"seconds",30)));
                    String title=McUtil.clean(str(a,"title","새벽별의 설계도"),40);
                    if(buildWish.buildPlan!=null) return BuildPreview.showSaved(ctx.eng,viewer,buildWish,title,secs);
                    ScriptRecord r = draft(ctx, "preview", req(a, "code"), a);
                    ScriptValidator.Report rep = ScriptValidator.validate(r, List.of(), (int) Math.min(2400, num(a, "simulate_ticks", 0)));
                    JsonObject o = BuildPreview.prepare(rep);
                    if (ctx.wish != null) ctx.wish.lastBuildPreviewAttempt = o.deepCopy();
                    if (!o.get("ok").getAsBoolean()) return o;
                    buildWish.buildPlan=dev.newyear.countdown.ai.script.BuildPlan.capture(rep.planned,1);
                    buildWish.buildPlan.anchor=r.anchor==null ? null : r.anchor.clone();
                    buildWish.buildPlan.approvalReasons=new ArrayList<>(rep.approvalReasons);
                    o=BuildPreview.showSaved(ctx.eng,viewer,buildWish,title,secs);
                    if (rep.approvalNeeded) o.addProperty("will_need_admin_approval", String.join("; ", rep.approvalReasons));
                    return o;
                });

        def("revise_build_plan", "Request an explicit player-confirmed replacement of the saved building design BEFORE construction starts. The current preview and accepted terms stay fixed until confirmation. Completed condition progress is preserved. Use only when the player asks to change the design/location.",
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"}},\"required\":[\"code\",\"reason\"]}",
                (ctx,a)->{
                    Wish w=requireWish(ctx);ServerPlayer viewer=requirePlayer(ctx);var old=w.buildPlan;
                    if(old==null) throw new ToolError("Create the initial design with preview_blocks first");
                    if(old.started) throw new ToolError("Construction has started. Keep the accepted site or explicitly cancel this build first");
                    if(ctx.validations++ > AiConfig.get().scriptValidationAttempts*3) throw new ToolError("Too many script attempts this turn");
                    var r=draft(ctx,"preview revision",req(a,"code"),a);
                    var validation=ScriptValidator.validate(r,List.of(),0);var out=BuildPreview.prepare(validation);
                    if(!out.get("ok").getAsBoolean()) return out;
                    var candidate=dev.newyear.countdown.ai.script.BuildPlan.revision(old,validation.planned);
                    candidate.anchor=r.anchor==null ? null : r.anchor.clone();
                    candidate.approvalReasons=new ArrayList<>(validation.approvalReasons);
                    String prompt="기존 위치: "+old.dimension+" "+old.bounds()+"\n새 위치: "+candidate.dimension+" "+candidate.bounds()+"\n"+McUtil.clean(req(a,"reason"),200)+"\n완료한 조건은 유지합니다. 설계를 변경할까요?";
                    String id=ctx.eng.ui().choices(viewer,"건축 설계 변경",prompt,List.of("새 설계로 변경","기존 설계 유지"),0,w.id,(player,index,value,timedOut)->{
                        String message="[BUILD REVISION DECLINED] Keep the saved site and conditions.";
                        if(!timedOut && index==0 && w.buildPlan==old && !old.started && !w.state.terminal()) {
                            if(old.accepted) candidate.accept(candidate.id,candidate.revision);
                            w.buildPlan=candidate;
                            ctx.eng.ui().cancelInteractionsOfWish(w.id);
                            if(w.contract!=null){w.contract.buildPlanId=candidate.id;w.contract.buildPlanRevision=candidate.revision;}
                            if(candidate.runnerScriptId!=null){var runner=ctx.eng.state().scripts.get(candidate.runnerScriptId);if(runner!=null){var c=candidate.cells.get(0);runner.dimension=candidate.dimension;runner.anchor=candidate.anchor==null ? new double[]{c.x,c.y,c.z} : candidate.anchor.clone();runner.approvedBy=null;runner.preview=new ArrayList<>(candidate.cells);}}
                            ctx.eng.ui().clearBlockPreview(player,old.id);
                            BuildPreview.showSaved(ctx.eng,player,w,"변경한 설계",60);
                            if(w.contract!=null && !w.contract.accepted) ctx.eng.ui().contract(player,w,ctx.eng.wishes().aiResponder(w.id,"contract",w.contract.terms));
                            message="[BUILD REVISION ACCEPTED] Player explicitly accepted "+candidate.summary()+". Preserve the existing terms and completed quest progress.";
                        }
                        ctx.eng.agent().submit(new TurnRequest(w.id,viewer.getUUID(),TurnRequest.Kind.UI,message,null));
                    });
                    out.addProperty("confirmation_required",true);out.addProperty("interaction_id",id);return out;
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
                    if (inst != null) ctx.eng.scripts().stopIfIdleOneShot(inst);
                    return res;
                });

        def("update_script", "Replace the code of an existing script (keeps its state, id and history). Use to fix or evolve an ability.",
                "{\"type\":\"object\",\"properties\":{\"script_id\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"},"
                        + "\"test_events\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}},\"required\":[\"script_id\",\"code\"]}",
                (ctx, a) -> {
                    ScriptRecord r = ctx.eng.state().scripts.get(req(a, "script_id"));
                    if(r!=null && r.wishId!=null){Wish bw=ctx.eng.state().wishes.get(r.wishId);
                        if(bw!=null && bw.buildPlan!=null && r.id.equals(bw.buildPlan.runnerScriptId)) throw new ToolError("This is the engine's fixed-plan constructor. Use construction.status/start instead of replacing its code");}
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
                    if(w.buildPlan!=null && w.buildPlan.accepted && w.contract!=null && w.contract.accepted){
                        JsonObject out=ok();out.addProperty("already_accepted",true);out.add("build_plan",w.buildPlan.summary());
                        out.addProperty("next","Reuse the existing quest and call construction.start; do not ask for the same acceptance again");return out;
                    }
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
                    if(w.buildPlan!=null){c.buildPlanId=w.buildPlan.id;c.buildPlanRevision=w.buildPlan.revision;}
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

        def("create_quest", "Reserve a HIDDEN quest draft ID, not a visible panel. FIRST design its checkable conditions and reward logic; then create this draft, read quests.get(id) at tracker top level, and validate/activate the complete tracker. HUD and deadline start only after the tracker succeeds. Never stop after this tool.",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"conditions\":{\"type\":\"string\"},"
                        + "\"reward\":{\"type\":\"string\"},\"failure\":{\"type\":\"string\"},"
                        + "\"objectives\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"target\":{\"type\":\"number\"},\"optional\":{\"type\":\"boolean\"}},\"required\":[\"id\",\"text\"]}},"
                        + "\"time_limit_seconds\":{\"type\":\"integer\"},\"participants\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"priority\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"title\",\"objectives\"]}",
                (ctx, a) -> {
                    Wish w = ctx.wish;
                    if (w != null && w.buildPlan == null) {
                        for (String id : w.questIds) {
                            Quest existing = ctx.eng.state().quests.get(id);
                            if (existing != null && existing.preparing && existing.state == Quest.State.ACTIVE
                                    && existing.title.equals(McUtil.clean(req(a, "title"), 48))) {
                                JsonObject out = ok(); out.addProperty("quest_id", id); out.addProperty("reused", true);
                                out.addProperty("ready", false); out.add("quest", ctx.eng.ui().questJson(existing));
                                out.addProperty("next", "Reuse this hidden draft. Implement and activate its condition tracker before replying.");
                                return out;
                            }
                        }
                    }
                    if(w!=null && w.buildPlan!=null && !w.questIds.isEmpty()) {
                        for(String id:w.questIds){Quest existing=ctx.eng.state().quests.get(id);if(existing==null)continue;
                            JsonObject out=ok();out.addProperty("quest_id",id);out.addProperty("reused",true);out.add("quest",ctx.eng.ui().questJson(existing));
                            out.addProperty("ready",!existing.preparing);out.addProperty("next","Keep completed progress. Read quests.get(questId) at top level, track unfinished conditions and arm construction.start(questId, buildObjectiveId) before replying.");return out;
                        }
                    }
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
                    o.addProperty("ready", false);
                    o.addProperty("next", "This is a hidden draft, not a running quest. Read const q = quests.get('" + q.id + "') at script top level; register condition handlers that update q.add('<objective id>') and the reward handler. Validate relevant test_events and activate_script with lasting:false. Only successful startup publishes it. Do not create another panel or give a final answer yet.");
                    return o;
                });

        def("update_quest", "Manually change a quest: set objective progress or finish it.",
                "{\"type\":\"object\",\"properties\":{\"quest_id\":{\"type\":\"string\"},\"objective_id\":{\"type\":\"string\"},\"progress\":{\"type\":\"number\"},"
                        + "\"state\":{\"type\":\"string\",\"enum\":[\"completed\",\"failed\",\"cancelled\"]},\"note\":{\"type\":\"string\"},\"script_id\":{\"type\":\"string\",\"description\":\"link the tracker script\"}},\"required\":[\"quest_id\"]}",
                (ctx, a) -> {
                    Quest q = ctx.eng.state().quests.get(req(a, "quest_id"));
                    if (q == null) throw new ToolError("No such quest");
                    String sid = str(a, "script_id", null);
                    if (sid != null) ctx.eng.quests().linkTracker(q, sid);
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
                    if(w.buildPlan!=null)o.add("build_plan",w.buildPlan.summary());
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
                    if(w.buildPlan!=null)o.add("build_plan",w.buildPlan.summary());
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
