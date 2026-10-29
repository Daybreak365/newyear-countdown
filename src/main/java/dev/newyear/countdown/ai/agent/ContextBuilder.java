package dev.newyear.countdown.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.newyear.countdown.CountdownState;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.AiState;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.memory.MemoryStore;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.StatusEntry;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 한 턴에 필요한 최소한의 세계·플레이어·소원·기억 정보를 모은다 (서버 스레드). */
public final class ContextBuilder {
    private ContextBuilder() {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static String age(long ms, long now) {
        long d = Math.max(0, now - ms) / 1000;
        if (d < 120) return "just now";
        if (d < 7200) return (d / 60) + " min ago";
        if (d < 172800) return (d / 3600) + " h ago";
        return (d / 86400) + " days ago";
    }

    public static JsonObject world(WishEngine eng) {
        JsonObject o = new JsonObject();
        ServerLevel ow = eng.server().overworld();
        long t = ow.getOverworldClockTime();
        o.addProperty("real_time", FMT.format(Instant.now().atZone(ZoneId.of("Asia/Seoul"))) + " KST");
        long rem = CountdownState.targetMs() - System.currentTimeMillis();
        o.addProperty("new_year_countdown", rem > 0 ? (rem / 86_400_000L) + " days " + (rem / 3_600_000L % 24) + " h until 2027" : "2027 has begun");
        o.addProperty("game_day", t / 24000L);
        long tod = Math.floorMod(t, 24000L);
        o.addProperty("time_of_day", tod + " (" + (tod < 1000 ? "dawn" : tod < 6000 ? "morning" : tod < 12000 ? "afternoon" : tod < 13000 ? "sunset" : tod < 23000 ? "night" : "pre-dawn") + ")");
        o.addProperty("weather", ow.isThundering() ? "thunder" : ow.isRaining() ? "rain" : "clear");
        JsonArray players = new JsonArray();
        for (ServerPlayer p : eng.server().getPlayerList().getPlayers()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", p.getGameProfile().name());
            j.addProperty("dimension", McUtil.dimId(p.level()));
            j.addProperty("pos", (int) p.getX() + "," + (int) p.getY() + "," + (int) p.getZ());
            players.add(j);
        }
        o.add("online_players", players);
        return o;
    }

    public static JsonObject player(WishEngine eng, ServerPlayer p, boolean detailed) {
        JsonObject o = new JsonObject();
        String uuid = p.getUUID().toString();
        o.addProperty("name", p.getGameProfile().name());
        o.addProperty("uuid", uuid);
        o.addProperty("dimension", McUtil.dimId(p.level()));
        o.addProperty("pos", (int) Math.floor(p.getX()) + "," + (int) Math.floor(p.getY()) + "," + (int) Math.floor(p.getZ()));
        o.addProperty("biome", p.level().getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
        o.addProperty("health", Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth()));
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("xp_level", p.experienceLevel);
        o.addProperty("game_mode", p.gameMode().getName());
        o.addProperty("held_item", describe(p.getMainHandItem()));
        AiState.PlayerProfile prof = eng.state().players.get(uuid);
        if (prof != null) {
            o.addProperty("wishes_made_total", prof.wishes);
            o.addProperty("first_seen", age(prof.firstSeen, System.currentTimeMillis()));
        }
        if (detailed) {
            JsonArray inv = new JsonArray();
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                String key = describeShort(st);
                counts.merge(key, st.getCount(), Integer::sum);
            }
            counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(24).forEach(e -> inv.add(e.getValue() + "x " + e.getKey()));
            o.add("inventory", inv);
            JsonArray eff = new JsonArray();
            for (MobEffectInstance e : p.getActiveEffects()) eff.add(e.getEffect().unwrapKey().map(k -> k.identifier().getPath()).orElse("?") + " " + (e.getAmplifier() + 1) + " (" + e.getDuration() / 20 + "s)");
            o.add("effects", eff);
            java.util.Map<String, Integer> near = new java.util.TreeMap<>();
            for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(16), x -> x.isAlive())) {
                near.merge(McUtil.entityId(e.getType()).replace("minecraft:", ""), 1, Integer::sum);
            }
            JsonArray ne = new JsonArray();
            near.forEach((k, v) -> ne.add(v + "x " + k));
            o.add("entities_within_16", ne);
            JsonArray np = new JsonArray();
            for (ServerPlayer q : ((ServerLevel) p.level()).players()) {
                if (q != p && q.distanceTo(p) < 48) np.add(q.getGameProfile().name() + " (" + Math.round(q.distanceTo(p)) + " blocks)");
            }
            o.add("players_nearby", np);
        }
        return o;
    }

    public static String describe(ItemStack st) {
        if (st.isEmpty()) return "nothing";
        return st.getCount() + "x " + describeShort(st);
    }

    private static String describeShort(ItemStack st) {
        String id = McUtil.itemId(st).replace("minecraft:", "");
        String name = st.getHoverName().getString();
        var info = McUtil.itemInfo(st);
        boolean special = Boolean.TRUE.equals(info.get("special"));
        return id + (special ? " \"" + name + "\" (special item)" : "");
    }

    /** 이 플레이어(또는 소원)와 관련된 실제 활성 콘텐츠. AI 가 지어내지 않도록 진짜 상태를 준다. */
    public static JsonObject activeContent(WishEngine eng, String playerUuid) {
        JsonObject o = new JsonObject();
        JsonArray wishes = new JsonArray();
        for (Wish w : eng.state().wishes.values()) {
            if (w.state.terminal()) continue;
            if (playerUuid != null && !playerUuid.equals(w.player)) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", w.id);
            j.addProperty("player", w.playerName);
            j.addProperty("text", w.text);
            j.addProperty("state", w.state.name());
            wishes.add(j);
        }
        o.add("open_wishes", wishes);
        JsonArray scripts = new JsonArray();
        for (ScriptRecord r : eng.state().scripts.values()) {
            if (r.state.ended() && System.currentTimeMillis() - r.created > 86_400_000L * 3) continue;
            if (playerUuid != null && !playerUuid.equals(r.owner) && !r.lasting) continue;
            scripts.add(scriptSummary(eng, r));
            if (scripts.size() >= 20) break;
        }
        o.add("scripts", scripts);
        JsonArray quests = new JsonArray();
        for (Quest q : eng.state().quests.values()) {
            if (q.state != Quest.State.ACTIVE) continue;
            if (playerUuid != null && !q.involves(playerUuid)) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", q.id);
            j.addProperty("title", q.title);
            j.addProperty("owner", q.ownerName);
            j.addProperty("wish", q.wishId);
            JsonArray obs = new JsonArray();
            for (Quest.Objective ob : q.objectives) obs.add(ob.id + ": " + ob.text + " " + (int) ob.progress + "/" + (int) ob.target + (ob.done ? " ✓" : ""));
            j.add("objectives", obs);
            if (q.deadlineTick > 0) j.addProperty("seconds_left", Math.max(0, (q.deadlineTick - eng.tick()) / 20));
            j.addProperty("tracked_by_script", q.scriptId == null ? "NONE (progress will not move unless a script updates it!)" : q.scriptId);
            quests.add(j);
        }
        o.add("active_quests", quests);
        JsonArray statuses = new JsonArray();
        for (StatusEntry s : eng.state().statuses.values()) {
            if (playerUuid != null && !playerUuid.equals(s.owner)) continue;
            statuses.add(s.label + " (" + s.kind + ")");
        }
        o.add("hud_statuses", statuses);
        return o;
    }

    public static JsonObject scriptSummary(WishEngine eng, ScriptRecord r) {
        JsonObject j = new JsonObject();
        j.addProperty("id", r.id);
        j.addProperty("name", r.name);
        j.addProperty("state", r.state.name());
        j.addProperty("owner", r.ownerName == null ? "(world)" : r.ownerName);
        j.addProperty("lasting", r.lasting);
        if (!r.description.isEmpty()) j.addProperty("description", r.description);
        j.addProperty("events", String.join(",", r.subscriptions));
        if (r.durationTicks > 0 && r.state.live()) j.addProperty("seconds_left", Math.max(0, (r.expiresTick - eng.tick()) / 20));
        if (r.errors > 0) j.addProperty("errors", r.errors + " (last: " + r.lastError + ")");
        if (!r.stateReason.isEmpty()) j.addProperty("state_reason", r.stateReason);
        j.addProperty("version", r.version);
        int alive = 0;
        for (String u : r.entities) {
            try {
                Entity e = new EntityRef(UUID.fromString(u), "?").live();
                if (e != null && e.isAlive()) alive++;
            } catch (IllegalArgumentException ignored) { }
        }
        if (!r.entities.isEmpty()) j.addProperty("entities_loaded_alive", alive + "/" + r.entities.size());
        return j;
    }

    /** 기억 + 실제 상태 주석. */
    public static JsonArray memories(WishEngine eng, String query, String playerUuid, int limit) {
        JsonArray arr = new JsonArray();
        long now = System.currentTimeMillis();
        for (MemoryStore.Hit h : eng.memory().search(query, playerUuid, limit, null, true, now)) {
            arr.add(memoryJson(eng, h.record(), now));
        }
        return arr;
    }

    public static JsonObject memoryJson(WishEngine eng, MemoryRecord r, long now) {
        JsonObject j = new JsonObject();
        j.addProperty("id", r.id);
        j.addProperty("kind", r.kind);
        j.addProperty("scope", r.scope == MemoryRecord.Scope.WORLD ? "world" : "private:" + (r.ownerName == null ? "?" : r.ownerName));
        j.addProperty("text", r.text);
        j.addProperty("importance", r.importance);
        j.addProperty("when", age(r.created, now));
        if (r.thread != null) j.addProperty("thread", r.thread);
        if (!r.links.isEmpty()) j.addProperty("related", String.join(",", r.links.subList(0, Math.min(4, r.links.size()))));
        // 실제 세계와 대조한 현재 상태
        List<String> live = new ArrayList<>();
        if (r.scriptId != null) {
            ScriptRecord s = eng.state().scripts.get(r.scriptId);
            live.add("script " + r.scriptId + ": " + (s == null ? "DELETED" : s.state.name()));
        }
        if (r.entityUuid != null) {
            try {
                Entity e = new EntityRef(UUID.fromString(r.entityUuid), "?").live();
                live.add(e == null ? "entity not currently loaded (may be gone or in an unloaded area)"
                        : (e.isAlive() ? "entity exists at " + (int) e.getX() + "," + (int) e.getY() + "," + (int) e.getZ() : "entity dead"));
            } catch (IllegalArgumentException ignored) { }
        }
        if (r.pos != null) live.add("place " + r.pos[0] + "," + r.pos[1] + "," + r.pos[2] + (r.dimension == null ? "" : " in " + r.dimension));
        if (!live.isEmpty()) j.addProperty("current_status", String.join("; ", live));
        return j;
    }

    /** 턴의 첫 developer 메시지. */
    public static String turnContext(WishEngine eng, TurnRequest req, Wish w, ServerPlayer p) {
        JsonObject o = new JsonObject();
        o.add("world", world(eng));
        if (p != null) o.add("player", player(eng, p, false));
        else if (w != null) o.addProperty("player", w.playerName + " (offline)");
        if (w != null) {
            JsonObject wj = new JsonObject();
            wj.addProperty("id", w.id);
            wj.addProperty("first_words", w.text);
            wj.addProperty("origin", w.origin);
            wj.addProperty("state", w.state.name());
            wj.addProperty("turns_so_far", w.turns);
            if (w.contract != null) {
                JsonObject c = new JsonObject();
                c.addProperty("terms", w.contract.terms);
                c.addProperty("reward", w.contract.reward);
                c.addProperty("accepted", w.contract.accepted);
                c.addProperty("declined", w.contract.declined);
                c.addProperty("revision", w.contract.revision);
                wj.add("contract", c);
            }
            if (!w.consents.isEmpty()) {
                JsonArray cs = new JsonArray();
                for (String u : w.consents) {
                    var prof = eng.state().players.get(u);
                    cs.add(prof == null ? u : prof.name);
                }
                wj.add("consented_players", cs);
            }
            JsonArray linked = new JsonArray();
            for (String sid : w.scriptIds) {
                ScriptRecord r = eng.state().scripts.get(sid);
                if (r != null) linked.add(r.id + " " + r.name + " " + r.state.name());
            }
            if (!linked.isEmpty()) wj.add("scripts", linked);
            JsonArray qs = new JsonArray();
            for (String qid : w.questIds) {
                Quest q = eng.state().quests.get(qid);
                if (q != null) qs.add(q.id + " " + q.title + " " + q.state.name());
            }
            if (!qs.isEmpty()) wj.add("quests", qs);
            if (w.conversationSummary != null && !w.conversationSummary.isEmpty()) wj.addProperty("earlier_conversation_summary", w.conversationSummary);
            o.add("current_wish", wj);
        }
        String uuid = p != null ? p.getUUID().toString() : w == null ? null : w.player;
        o.add("player_active_content", activeContent(eng, uuid));
        // 기억 검색: 소원 문장 + 마지막 메시지
        String query = (w == null ? "" : w.text) + " " + (req.message() == null ? "" : req.message()) + " " + (req.trigger() == null ? "" : req.trigger());
        o.add("relevant_memories", memories(eng, query, uuid, AiConfig.get().memoryRetrieveCount));
        // 이 플레이어의 최근 소원 (반복 행동 파악용)
        if (uuid != null) {
            JsonArray past = new JsonArray();
            List<Wish> mine = new ArrayList<>();
            for (Wish x : eng.state().wishes.values()) if (uuid.equals(x.player) && x != w) mine.add(x);
            mine.sort((a, b) -> Long.compare(b.created, a.created));
            long now = System.currentTimeMillis();
            for (int i = 0; i < Math.min(6, mine.size()); i++) {
                Wish x = mine.get(i);
                past.add("\"" + x.text + "\" → " + x.state.name() + (x.outcome == null || x.outcome.isEmpty() ? "" : " (" + clip(x.outcome, 80) + ")") + ", " + age(x.created, now));
            }
            o.add("recent_wishes_of_player", past);
        }
        if (req.kind() == TurnRequest.Kind.AUTONOMOUS) o.addProperty("autonomous_trigger", req.trigger());
        if (req.kind() == TurnRequest.Kind.SCRIPT) o.addProperty("script_notification", req.trigger());
        return "[context — current, authoritative game state; JSON]\n" + o;
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
