package dev.newyear.countdown.ai.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.net.AiPackets;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.StatusEntry;
import dev.newyear.countdown.ai.wish.Wish;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 서버 쪽 UI 툴킷. AI 와 스크립트는 여기 있는 연산(대사, 선택지, 입력, 계약, 퀘스트, 상태, 알림, 연출)만으로 UI 를 만들고,
 * 실제 그리기는 클라이언트의 통일된 디자인 시스템이 맡는다.
 * 모든 상호작용은 고유 ID 를 가지며, 서버가 주인·만료·중복을 검증한 뒤에만 처리한다.
 */
public final class UiService {
    /** 상호작용 응답 처리기. index 는 선택지 번호(입력창은 0), timedOut 이면 value 는 null. */
    public interface Responder {
        void respond(ServerPlayer player, int index, String value, boolean timedOut);

        default String scriptId() {
            return null;
        }
    }

    public static Responder forScript(String scriptId, Responder r) {
        return new Responder() {
            @Override
            public void respond(ServerPlayer player, int index, String value, boolean timedOut) {
                r.respond(player, index, value, timedOut);
            }

            @Override
            public String scriptId() {
                return scriptId;
            }
        };
    }

    public static final class Pending {
        public String id;
        public UUID player;
        /** choices / input / consent / contract */
        public String kind;
        public String title;
        public String prompt;
        public List<String> options = new ArrayList<>();
        public String placeholder = "";
        public long expiresTick;
        public String wishId;
        public Responder responder;
        public long createdTick;
    }

    private final WishEngine eng;
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();
    /** 최근 처리한 상호작용 (재전송·중복 클릭 무시용). */
    private final LinkedHashSet<String> answered = new LinkedHashSet<>();
    /** 플레이어별 마지막 nonce (같은 패킷 재전송 방지). */
    private final Map<UUID, Integer> lastNonce = new LinkedHashMap<>();

    public UiService(WishEngine eng) {
        this.eng = eng;
    }

    public Map<String, Pending> pending() {
        return pending;
    }

    // ---------------------------------------------------------------- 전송

    /** 자가 진단: 가짜 플레이어에게 가는 UI 연산을 가로채 확인한다. */
    private final Map<UUID, java.util.function.Consumer<JsonObject>> testSinks = new LinkedHashMap<>();

    public void setTestSink(UUID player, java.util.function.Consumer<JsonObject> sink) {
        if (sink == null) testSinks.remove(player);
        else testSinks.put(player, sink);
    }

    public boolean hasModClient(ServerPlayer p) {
        if (testSinks.containsKey(p.getUUID())) return true;
        try {
            return p.connection != null && ServerPlayNetworking.canSend(p, AiPackets.UiS2C.ID);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void send(ServerPlayer p, JsonObject op) {
        if (p == null) return;
        var sink = testSinks.get(p.getUUID());
        if (sink != null) {
            sink.accept(op);
            return;
        }
        if (p.connection == null) return;
        if (!hasModClient(p)) return;
        String json = op.toString();
        if (json.length() > AiPackets.MAX_JSON) {
            dev.newyear.countdown.NewYearCountdown.LOGGER.warn("[WishAI] UI op {} too large ({}), dropped", op.get("op"), json.length());
            return;
        }
        ServerPlayNetworking.send(p, new AiPackets.UiS2C(json));
    }

    private static JsonObject op(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("op", name);
        return o;
    }

    private static MutableComponent spiritPrefix() {
        return Component.literal("✦ " + AiConfig.get().spiritName + " ").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xF3D98A)));
    }

    // ---------------------------------------------------------------- 대사·알림·연출

    /** 소원 정령의 대사를 대화 기록에 남기고 화면에 보낸다. */
    public void spiritLine(ServerPlayer p, String wishId, String text, String mood) {
        spiritLine(p, wishId, text, mood, List.of(), false);
    }

    public void spiritLine(ServerPlayer p, String wishId, String text, String mood, List<String> quick, boolean expectReply) {
        if (text == null || text.isBlank()) return;
        Wish w = wishId == null ? null : eng.state().wishes.get(wishId);
        if (w != null) w.addLine("spirit", text, System.currentTimeMillis());
        if (p == null) return;
        if (hasModClient(p)) {
            JsonObject o = op("line");
            o.addProperty("wishId", wishId == null ? "" : wishId);
            o.addProperty("role", "spirit");
            o.addProperty("text", text);
            o.addProperty("mood", mood == null ? "neutral" : mood);
            JsonArray q = new JsonArray();
            for (String s : quick) q.add(s);
            o.add("quick", q);
            o.addProperty("expect", expectReply);
            if (w != null) o.addProperty("state", w.state.name());
            send(p, o);
        } else {
            p.sendSystemMessage(spiritPrefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
            if (expectReply) p.sendSystemMessage(Component.literal("  (/wish <말> 로 대답할 수 있습니다)").withStyle(ChatFormatting.GRAY));
        }
    }

    public void playerLine(ServerPlayer p, String wishId, String text) {
        if (p == null || !hasModClient(p)) return;
        JsonObject o = op("line");
        o.addProperty("wishId", wishId);
        o.addProperty("role", "player");
        o.addProperty("text", text);
        send(p, o);
    }

    public void thinking(ServerPlayer p, String wishId, boolean on, String label) {
        if (p == null) return;
        JsonObject o = op("thinking");
        o.addProperty("wishId", wishId == null ? "" : wishId);
        o.addProperty("on", on);
        o.addProperty("label", label == null ? "" : label);
        send(p, o);
    }

    public void openDialogue(ServerPlayer p, String wishId) {
        JsonObject o = op("open");
        o.addProperty("wishId", wishId == null ? "" : wishId);
        send(p, o);
    }

    public void notify(ServerPlayer p, String title, String text, String kind, int seconds) {
        if (p == null) return;
        if (hasModClient(p)) {
            JsonObject o = op("notify");
            o.addProperty("title", title == null ? "" : title);
            o.addProperty("text", text);
            o.addProperty("kind", kind == null ? "info" : kind);
            o.addProperty("seconds", Math.max(2, Math.min(20, seconds)));
            send(p, o);
        } else {
            p.sendSystemMessage(spiritPrefix().append(Component.literal((title == null || title.isEmpty() ? "" : "[" + title + "] ") + text)));
        }
    }

    public void cinematic(ServerPlayer p, String title, String subtitle, String style, int seconds) {
        if (p == null) return;
        if (hasModClient(p)) {
            JsonObject o = op("cinematic");
            o.addProperty("title", title);
            o.addProperty("subtitle", subtitle == null ? "" : subtitle);
            o.addProperty("style", style == null ? "wish" : style);
            o.addProperty("seconds", Math.max(2, Math.min(12, seconds)));
            send(p, o);
        } else {
            p.sendSystemMessage(spiritPrefix().append(Component.literal(title + (subtitle == null || subtitle.isEmpty() ? "" : " — " + subtitle)).withStyle(ChatFormatting.GOLD)));
        }
    }

    // ---------------------------------------------------------------- 상호작용

    private String newId() {
        return "ui-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String register(ServerPlayer p, String kind, String title, String prompt, List<String> options, String placeholder,
                            int timeoutSec, String wishId, Responder r) {
        // 같은 플레이어의 같은 종류 대기 상호작용이 너무 많으면 오래된 것부터 닫는다
        int mine = 0;
        for (Pending x : pending.values()) if (x.player.equals(p.getUUID())) mine++;
        if (mine >= 6) {
            Pending oldest = null;
            for (Pending x : pending.values()) if (x.player.equals(p.getUUID())) { oldest = x; break; }
            if (oldest != null) expire(oldest, "replaced");
        }
        Pending pd = new Pending();
        pd.id = newId();
        pd.player = p.getUUID();
        pd.kind = kind;
        pd.title = title == null ? "" : title;
        pd.prompt = prompt;
        pd.options = options;
        pd.placeholder = placeholder == null ? "" : placeholder;
        pd.createdTick = eng.tick();
        pd.expiresTick = timeoutSec > 0 ? eng.tick() + Math.min(3600, timeoutSec) * 20L : 0;
        pd.wishId = wishId;
        pd.responder = r;
        pending.put(pd.id, pd);
        sendInteraction(p, pd);
        return pd.id;
    }

    private void sendInteraction(ServerPlayer p, Pending pd) {
        if (hasModClient(p)) {
            JsonObject o = op("interaction");
            o.addProperty("id", pd.id);
            o.addProperty("kind", pd.kind);
            o.addProperty("title", pd.title);
            o.addProperty("prompt", pd.prompt);
            JsonArray arr = new JsonArray();
            for (String s : pd.options) arr.add(s);
            o.add("options", arr);
            o.addProperty("placeholder", pd.placeholder);
            o.addProperty("remainingMs", pd.expiresTick <= 0 ? -1 : (pd.expiresTick - eng.tick()) * 50);
            o.addProperty("wishId", pd.wishId == null ? "" : pd.wishId);
            send(p, o);
        } else {
            // 모드가 없는 클라이언트: 채팅에 클릭 가능한 선택지
            p.sendSystemMessage(spiritPrefix().append(Component.literal((pd.title.isEmpty() ? "" : "[" + pd.title + "] ") + pd.prompt)));
            if (pd.kind.equals("input")) {
                p.sendSystemMessage(Component.literal("  /wish answer " + pd.id + " <답>").withStyle(ChatFormatting.GRAY));
            } else {
                MutableComponent line = Component.literal("  ");
                for (int i = 0; i < pd.options.size(); i++) {
                    String cmd = "/wish answer " + pd.id + " " + i;
                    line.append(Component.literal("[" + pd.options.get(i) + "] ").setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent.RunCommand(cmd))));
                }
                p.sendSystemMessage(line);
            }
        }
    }

    public String choices(ServerPlayer p, String title, String prompt, List<String> options, int timeoutSec, String wishId, Responder r) {
        return register(p, "choices", title, prompt, options, "", timeoutSec, wishId, r);
    }

    public String input(ServerPlayer p, String title, String prompt, String placeholder, int timeoutSec, String wishId, Responder r) {
        return register(p, "input", title, prompt, List.of(), placeholder, timeoutSec, wishId, r);
    }

    /** 다른 플레이어에게 동의를 구한다. 동의하면 그 소원의 consents 에 추가된다. */
    public String consent(ServerPlayer target, String wishId, String text, Responder r) {
        Wish w = wishId == null ? null : eng.state().wishes.get(wishId);
        String who = w == null ? "누군가" : w.playerName;
        Responder wrapped = new Responder() {
            @Override
            public void respond(ServerPlayer player, int index, String value, boolean timedOut) {
                if (!timedOut && index == 0 && w != null && !w.consents.contains(player.getUUID().toString())) {
                    w.consents.add(player.getUUID().toString());
                }
                if (r != null) r.respond(player, index, value, timedOut);
            }

            @Override
            public String scriptId() {
                return r == null ? null : r.scriptId();
            }
        };
        return register(target, "consent", who + "의 소원", text, List.of("동의한다", "거절한다"), "", 120, wishId, wrapped);
    }

    public String contract(ServerPlayer p, Wish w, Responder r) {
        Wish.Contract c = w.contract;
        StringBuilder sb = new StringBuilder(c.terms);
        if (!c.reward.isEmpty()) sb.append("\n보상: ").append(c.reward);
        if (!c.penalty.isEmpty()) sb.append("\n대가: ").append(c.penalty);
        if (c.timeLimitSeconds > 0) sb.append("\n제한 시간: ").append(formatSeconds(c.timeLimitSeconds));
        return register(p, "contract", "소원의 계약", sb.toString(), List.of("수락", "거절"), "", 0, w.id, r);
    }

    public static String formatSeconds(int s) {
        if (s >= 3600) return (s / 3600) + "시간 " + (s % 3600 / 60) + "분";
        if (s >= 60) return (s / 60) + "분" + (s % 60 > 0 ? " " + (s % 60) + "초" : "");
        return s + "초";
    }

    /** 클라이언트가 보낸 응답을 검증하고 처리한다. 결과 메시지(실패 이유)를 돌려준다, 성공 시 null. */
    public String handleInteract(ServerPlayer p, String id, int index, String value, int nonce) {
        Integer last = lastNonce.get(p.getUUID());
        if (last != null && nonce != 0 && last == nonce) return "duplicate";
        if (nonce != 0) lastNonce.put(p.getUUID(), nonce);
        if (answered.contains(id)) return "already answered";
        Pending pd = pending.get(id);
        if (pd == null) {
            closeOnClient(p, id, "expired");
            return "expired";
        }
        if (!pd.player.equals(p.getUUID())) return "not yours";      // 다른 플레이어의 UI 조작은 무시
        if (pd.expiresTick > 0 && eng.tick() > pd.expiresTick) {
            expire(pd, "expired");
            return "expired";
        }
        String v;
        if (pd.kind.equals("input")) {
            v = value == null ? "" : dev.newyear.countdown.ai.script.api.McUtil.clean(value, 200).strip();
            if (v.isEmpty()) return "empty";
            index = 0;
        } else {
            if (index < 0 || index >= pd.options.size()) return "invalid option";
            v = pd.options.get(index);
        }
        pending.remove(id);
        markAnswered(id);
        closeOnClient(p, id, "answered");
        if (pd.wishId != null) {
            Wish w = eng.state().wishes.get(pd.wishId);
            if (w != null) w.addLine("player", (pd.kind.equals("input") ? "" : "[선택] ") + v, System.currentTimeMillis());
            eng.scripts().emitUiResponse(pd.wishId, p, id, v);
        }
        if (pd.responder != null) {
            try {
                pd.responder.respond(p, index, v, false);
            } catch (RuntimeException e) {
                dev.newyear.countdown.NewYearCountdown.LOGGER.warn("[WishAI] UI responder failed", e);
            }
        }
        return null;
    }

    private void markAnswered(String id) {
        answered.add(id);
        while (answered.size() > 500) {
            Iterator<String> it = answered.iterator();
            it.next();
            it.remove();
        }
    }

    private void closeOnClient(ServerPlayer p, String id, String reason) {
        JsonObject o = op("interaction_close");
        o.addProperty("id", id);
        o.addProperty("reason", reason);
        send(p, o);
    }

    private void expire(Pending pd, String reason) {
        pending.remove(pd.id);
        markAnswered(pd.id);
        ServerPlayer p = eng.player(pd.player);
        if (p != null) closeOnClient(p, pd.id, reason);
        if (pd.responder != null) {
            try {
                pd.responder.respond(p, -1, null, true);
            } catch (RuntimeException e) {
                dev.newyear.countdown.NewYearCountdown.LOGGER.warn("[WishAI] UI timeout responder failed", e);
            }
        }
    }

    public void cancelInteractionsOfScript(String scriptId) {
        for (Pending pd : new ArrayList<>(pending.values())) {
            if (pd.responder != null && scriptId.equals(pd.responder.scriptId())) {
                pending.remove(pd.id);
                markAnswered(pd.id);
                ServerPlayer p = eng.player(pd.player);
                if (p != null) closeOnClient(p, pd.id, "cancelled");
            }
        }
    }

    public void cancelInteractionsOfWish(String wishId) {
        for (Pending pd : new ArrayList<>(pending.values())) {
            if (wishId.equals(pd.wishId)) {
                pending.remove(pd.id);
                markAnswered(pd.id);
                ServerPlayer p = eng.player(pd.player);
                if (p != null) closeOnClient(p, pd.id, "cancelled");
            }
        }
    }

    // ---------------------------------------------------------------- 퀘스트·상태

    public JsonObject questJson(Quest q) {
        JsonObject o = new JsonObject();
        o.addProperty("id", q.id);
        o.addProperty("wishId", q.wishId == null ? "" : q.wishId);
        o.addProperty("title", q.title);
        o.addProperty("description", q.description);
        o.addProperty("conditions", q.conditions);
        o.addProperty("reward", q.reward);
        o.addProperty("failure", q.failure);
        o.addProperty("state", q.state.name());
        o.addProperty("priority", q.priority);
        o.addProperty("note", q.resultNote == null ? "" : q.resultNote);
        o.addProperty("owner", q.ownerName == null ? "" : q.ownerName);
        o.addProperty("remainingMs", q.deadlineTick <= 0 ? -1 : Math.max(0, (q.deadlineTick - eng.tick()) * 50));
        o.addProperty("totalMs", q.deadlineTick <= 0 ? -1 : (q.deadlineTick - q.startTick) * 50);
        JsonArray objs = new JsonArray();
        for (Quest.Objective ob : q.objectives) {
            if (ob.hidden && !ob.done) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", ob.id);
            j.addProperty("text", ob.text);
            j.addProperty("progress", ob.progress);
            j.addProperty("target", ob.target);
            j.addProperty("done", ob.done);
            j.addProperty("optional", ob.optional);
            objs.add(j);
        }
        o.add("objectives", objs);
        return o;
    }

    public void pushQuest(Quest q) {
        JsonObject o = op("quest");
        o.add("quest", questJson(q));
        for (ServerPlayer p : participants(q)) send(p, o);
    }

    private List<ServerPlayer> participants(Quest q) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : eng.server().getPlayerList().getPlayers()) if (q.involves(p.getUUID().toString())) out.add(p);
        return out;
    }

    public JsonObject statusJson(StatusEntry s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id);
        o.addProperty("label", s.label);
        o.addProperty("desc", s.description == null ? "" : s.description);
        o.addProperty("kind", s.kind);
        o.addProperty("counter", s.counter == null ? "" : s.counter);
        o.addProperty("remainingMs", s.expiresTick <= 0 ? -1 : Math.max(0, (s.expiresTick - eng.tick()) * 50));
        o.addProperty("totalMs", s.expiresTick <= 0 ? -1 : (s.expiresTick - s.startTick) * 50);
        if (s.iconPixels != null && s.iconPixels.length > 0) {
            JsonObject icon = new JsonObject();
            JsonArray rows = new JsonArray();
            for (String r : s.iconPixels) rows.add(r);
            icon.add("pixels", rows);
            icon.addProperty("palette", s.iconPalette == null ? "" : s.iconPalette);
            o.add("icon", icon);
        }
        return o;
    }

    public void setStatus(StatusEntry s) {
        eng.state().statuses.put(s.id, s);
        ServerPlayer p = eng.player(UUID.fromString(s.owner));
        if (p != null) {
            JsonObject o = op("status");
            o.add("status", statusJson(s));
            send(p, o);
        }
    }

    public void clearStatus(String id) {
        StatusEntry s = eng.state().statuses.remove(id);
        if (s == null) return;
        ServerPlayer p = eng.player(UUID.fromString(s.owner));
        if (p != null) {
            JsonObject o = op("status_remove");
            o.addProperty("id", id);
            send(p, o);
        }
    }

    public void clearStatusesOfScript(String scriptId) {
        for (StatusEntry s : new ArrayList<>(eng.state().statuses.values())) if (scriptId.equals(s.scriptId)) clearStatus(s.id);
    }

    public void wishState(ServerPlayer p, Wish w) {
        JsonObject o = op("wish_state");
        o.addProperty("wishId", w.id);
        o.addProperty("state", w.state.name());
        o.addProperty("text", w.text);
        send(p, o);
    }

    // ---------------------------------------------------------------- 동기화·틱

    /** 접속·재접속 시 필요한 UI 를 모두 복구한다. */
    public void syncPlayer(ServerPlayer p) {
        String uuid = p.getUUID().toString();
        JsonObject o = op("sync");
        o.addProperty("spirit", AiConfig.get().spiritName);
        o.addProperty("enabled", eng.aiAvailable());
        JsonArray quests = new JsonArray();
        for (Quest q : eng.state().quests.values()) {
            if (!q.involves(uuid)) continue;
            if (q.state != Quest.State.ACTIVE && System.currentTimeMillis() - q.created > 3_600_000L * 24) continue;
            quests.add(questJson(q));
        }
        o.add("quests", quests);
        JsonArray statuses = new JsonArray();
        for (StatusEntry s : eng.state().statuses.values()) if (uuid.equals(s.owner)) statuses.add(statusJson(s));
        o.add("statuses", statuses);
        // 열린 대화 + 최근 소원 목록
        var prof = eng.state().players.get(uuid);
        Wish open = prof == null || prof.openWishId == null ? null : eng.state().wishes.get(prof.openWishId);
        if (open != null) o.add("conversation", conversationJson(open));
        JsonArray recent = new JsonArray();
        List<Wish> mine = new ArrayList<>();
        for (Wish w : eng.state().wishes.values()) if (uuid.equals(w.player)) mine.add(w);
        mine.sort((a, b) -> Long.compare(b.created, a.created));
        for (int i = 0; i < Math.min(20, mine.size()); i++) {
            Wish w = mine.get(i);
            JsonObject j = new JsonObject();
            j.addProperty("id", w.id);
            j.addProperty("text", w.text);
            j.addProperty("state", w.state.name());
            j.addProperty("outcome", w.outcome == null ? "" : w.outcome);
            j.addProperty("time", w.created);
            recent.add(j);
        }
        o.add("wishes", recent);
        send(p, o);
        for (Pending pd : pending.values()) if (pd.player.equals(p.getUUID())) sendInteraction(p, pd);
    }

    public JsonObject conversationJson(Wish w) {
        JsonObject c = new JsonObject();
        c.addProperty("wishId", w.id);
        c.addProperty("state", w.state.name());
        c.addProperty("text", w.text);
        c.addProperty("thinking", w.thinking);
        JsonArray lines = new JsonArray();
        int from = Math.max(0, w.conversation.size() - 40);
        for (int i = from; i < w.conversation.size(); i++) {
            Wish.Line l = w.conversation.get(i);
            if (l.role().equals("system")) continue;
            JsonObject j = new JsonObject();
            j.addProperty("role", l.role());
            j.addProperty("text", l.text());
            lines.add(j);
        }
        c.add("lines", lines);
        return c;
    }

    public void sendConversation(ServerPlayer p, Wish w) {
        JsonObject o = op("conversation");
        o.add("conversation", conversationJson(w));
        send(p, o);
    }

    public void tick() {
        long now = eng.tick();
        if (now % 10 == 0 && !pending.isEmpty()) {
            for (Pending pd : new ArrayList<>(pending.values())) if (pd.expiresTick > 0 && now > pd.expiresTick) expire(pd, "expired");
        }
        if (now % 20 == 0) {
            for (StatusEntry s : new ArrayList<>(eng.state().statuses.values())) {
                if (s.expiresTick > 0 && now >= s.expiresTick) clearStatus(s.id);
            }
        }
    }
}
