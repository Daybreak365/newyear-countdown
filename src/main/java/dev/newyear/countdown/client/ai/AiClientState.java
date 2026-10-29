package dev.newyear.countdown.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newyear.countdown.ai.net.AiPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 클라이언트 쪽 AI UI 상태. 서버가 보내는 선언형 UI 연산(JSON)을 받아 저장하고, 화면·HUD 는 이 상태만 그린다.
 * (재접속 시 서버의 sync 연산으로 전부 복구된다.)
 */
public final class AiClientState {
    private AiClientState() {}

    public static final class Line {
        public final String role, text, mood;
        public final long at;

        Line(String role, String text, String mood) {
            this.role = role;
            this.text = text;
            this.mood = mood;
            this.at = System.currentTimeMillis();
        }
    }

    public static final class Objective {
        public String id, text;
        public double progress, target;
        public boolean done, optional;
        /** 화면에서 부드럽게 따라가는 표시값. */
        public float shown;
    }

    public static final class Quest {
        public String id, wishId, title, description, conditions, reward, failure, state, note, owner;
        public int priority;
        public long deadlineLocal = -1, totalMs = -1;
        public final List<Objective> objectives = new ArrayList<>();
        public long updatedAt = System.currentTimeMillis();
        public boolean expanded = true;

        public double fraction() {
            double done = 0, all = 0;
            for (Objective o : objectives) {
                if (o.optional) continue;
                all += 1;
                done += Math.min(1, o.target <= 0 ? 1 : o.progress / o.target);
            }
            return all == 0 ? 0 : done / all;
        }

        public boolean active() {
            return "ACTIVE".equals(state);
        }
    }

    public static final class Status {
        public String id, label, desc, kind, counter;
        public long deadlineLocal = -1, totalMs = -1;
        public String[] pixels;
        public Map<Character, Integer> palette = new LinkedHashMap<>();
        public final long since = System.currentTimeMillis();
    }

    public static final class Interaction {
        public String id, kind, title, prompt, placeholder, wishId;
        public final List<String> options = new ArrayList<>();
        public long deadlineLocal = -1, totalMs = -1;
        public final long since = System.currentTimeMillis();
        public boolean sent;
    }

    public static final class Toast {
        public String title, text, kind;
        public long start = System.currentTimeMillis();
        public long durationMs;
    }

    public static final class Cinematic {
        public String title, subtitle, style;
        public long start = System.currentTimeMillis();
        public long durationMs;
    }

    public static final class PastWish {
        public String id, text, state, outcome;
        public long time;
    }

    public static String spiritName = "새벽별";
    public static boolean enabled = true;
    public static boolean synced;

    // 대화
    public static String wishId = "";
    public static String wishState = "";
    public static String wishText = "";
    public static final List<Line> lines = new ArrayList<>();
    public static boolean thinking;
    public static String thinkingLabel = "";
    public static long thinkingSince;
    public static final List<String> quick = new ArrayList<>();
    public static boolean expectReply;
    /** 화면이 닫혀 있는 동안 도착한 정령의 말 수. */
    public static int unread;

    public static final Map<String, Quest> quests = new LinkedHashMap<>();
    public static final Map<String, Status> statuses = new LinkedHashMap<>();
    public static final Map<String, Interaction> interactions = new LinkedHashMap<>();
    public static final List<Toast> toasts = new ArrayList<>();
    public static Cinematic cinematic;
    public static final List<PastWish> history = new ArrayList<>();

    private static int nonce = ThreadLocalRandom.current().nextInt(1, 1_000_000);

    public static void clear() {
        synced = false;
        wishId = "";
        wishState = "";
        wishText = "";
        lines.clear();
        thinking = false;
        quick.clear();
        quests.clear();
        statuses.clear();
        interactions.clear();
        toasts.clear();
        cinematic = null;
        history.clear();
        unread = 0;
    }

    // ---------------------------------------------------------------- 보내기

    public static void sendChat(String text) {
        if (!ClientPlayNetworking.canSend(AiPackets.ChatC2S.ID)) return;
        lines.add(new Line("player", text, ""));
        quick.clear();
        ClientPlayNetworking.send(new AiPackets.ChatC2S(wishId == null ? "" : wishId, text, ++nonce));
    }

    public static void answer(Interaction it, int index, String value) {
        if (it.sent || !ClientPlayNetworking.canSend(AiPackets.InteractC2S.ID)) return;
        it.sent = true;
        ClientPlayNetworking.send(new AiPackets.InteractC2S(it.id, index, value == null ? "" : value, ++nonce));
    }

    public static void action(String action, String arg) {
        if (ClientPlayNetworking.canSend(AiPackets.ActionC2S.ID)) ClientPlayNetworking.send(new AiPackets.ActionC2S(action, arg == null ? "" : arg));
    }

    public static void newWish() {
        action("new", "");
        wishId = "";
        wishState = "";
        wishText = "";
        lines.clear();
        quick.clear();
        expectReply = false;
    }

    // ---------------------------------------------------------------- 받기

    private static String s(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long l(JsonObject o, String k, long def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsLong() : def;
    }

    private static double d(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
    }

    private static boolean b(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    public static void apply(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String op = s(o, "op");
        Minecraft mc = Minecraft.getInstance();
        switch (op) {
            case "sync" -> {
                spiritName = s(o, "spirit");
                enabled = b(o, "enabled");
                quests.clear();
                for (JsonElement e : o.getAsJsonArray("quests")) putQuest(e.getAsJsonObject());
                statuses.clear();
                for (JsonElement e : o.getAsJsonArray("statuses")) putStatus(e.getAsJsonObject());
                if (o.has("conversation")) applyConversation(o.getAsJsonObject("conversation"));
                history.clear();
                if (o.has("wishes")) for (JsonElement e : o.getAsJsonArray("wishes")) {
                    JsonObject w = e.getAsJsonObject();
                    PastWish p = new PastWish();
                    p.id = s(w, "id");
                    p.text = s(w, "text");
                    p.state = s(w, "state");
                    p.outcome = s(w, "outcome");
                    p.time = l(w, "time", 0);
                    history.add(p);
                }
                interactions.clear();
                synced = true;
            }
            case "conversation" -> applyConversation(o.getAsJsonObject("conversation"));
            case "line" -> {
                String wid = s(o, "wishId");
                String role = s(o, "role");
                if (!wid.isEmpty() && !wid.equals(wishId)) {
                    // 새 소원의 대화가 시작됨
                    wishId = wid;
                    if (role.equals("spirit") && !lines.isEmpty() && !lines.get(lines.size() - 1).role.equals("player")) lines.clear();
                }
                if (o.has("state")) wishState = s(o, "state");
                if (role.equals("player")) {
                    // 내가 보낸 말은 이미 화면에 있음
                    if (lines.isEmpty() || !lines.get(lines.size() - 1).text.equals(s(o, "text"))) lines.add(new Line("player", s(o, "text"), ""));
                    return;
                }
                lines.add(new Line("spirit", s(o, "text"), s(o, "mood")));
                while (lines.size() > 80) lines.remove(0);
                quick.clear();
                if (o.has("quick")) for (JsonElement e : o.getAsJsonArray("quick")) quick.add(e.getAsString());
                expectReply = b(o, "expect");
                if (!(mc.gui.screen() instanceof WishChatScreen)) {
                    unread++;
                    if (mc.player != null) mc.player.playSound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 1.4f);
                }
            }
            case "thinking" -> {
                boolean on = b(o, "on");
                if (on && !thinking) thinkingSince = System.currentTimeMillis();
                thinking = on;
                thinkingLabel = s(o, "label");
                String wid = s(o, "wishId");
                if (!wid.isEmpty()) wishId = wid;
            }
            case "open" -> {
                String wid = s(o, "wishId");
                if (!wid.isEmpty()) wishId = wid;
                if (mc.gui.screen() == null) mc.gui.setScreen(new WishChatScreen());
            }
            case "wish_state" -> {
                if (s(o, "wishId").equals(wishId)) {
                    wishState = s(o, "state");
                    wishText = s(o, "text");
                }
                for (PastWish p : history) if (p.id.equals(s(o, "wishId"))) p.state = s(o, "state");
            }
            case "quest" -> {
                Quest q = putQuest(o.getAsJsonObject("quest"));
                if (q != null && !q.active()) q.expanded = false;
            }
            case "status" -> putStatus(o.getAsJsonObject("status"));
            case "status_remove" -> statuses.remove(s(o, "id"));
            case "interaction" -> {
                Interaction it = new Interaction();
                it.id = s(o, "id");
                it.kind = s(o, "kind");
                it.title = s(o, "title");
                it.prompt = s(o, "prompt");
                it.placeholder = s(o, "placeholder");
                it.wishId = s(o, "wishId");
                for (JsonElement e : o.getAsJsonArray("options")) it.options.add(e.getAsString());
                long rem = l(o, "remainingMs", -1);
                if (rem >= 0) {
                    it.deadlineLocal = System.currentTimeMillis() + rem;
                    it.totalMs = rem;
                }
                interactions.put(it.id, it);
                if (mc.player != null) mc.player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
                // 다른 화면이 없으면 바로 대화창을 열어 응답하게 한다
                if (mc.gui.screen() == null) mc.gui.setScreen(new WishChatScreen());
            }
            case "interaction_close" -> interactions.remove(s(o, "id"));
            case "notify" -> {
                Toast t = new Toast();
                t.title = s(o, "title");
                t.text = s(o, "text");
                t.kind = s(o, "kind");
                t.durationMs = l(o, "seconds", 5) * 1000;
                toasts.add(t);
                while (toasts.size() > 4) toasts.remove(0);
            }
            case "cinematic" -> {
                Cinematic c = new Cinematic();
                c.title = s(o, "title");
                c.subtitle = s(o, "subtitle");
                c.style = s(o, "style");
                c.durationMs = l(o, "seconds", 4) * 1000;
                cinematic = c;
                if (mc.player != null) mc.player.playSound(SoundEvents.AMETHYST_CLUSTER_BREAK, 0.6f, 0.7f);
            }
            default -> { }
        }
    }

    private static void applyConversation(JsonObject c) {
        String wid = s(c, "wishId");
        wishId = wid;
        wishState = s(c, "state");
        wishText = s(c, "text");
        thinking = b(c, "thinking");
        lines.clear();
        JsonArray arr = c.getAsJsonArray("lines");
        if (arr != null) for (JsonElement e : arr) {
            JsonObject l = e.getAsJsonObject();
            lines.add(new Line(s(l, "role"), s(l, "text"), ""));
        }
    }

    private static Quest putQuest(JsonObject j) {
        if (j == null) return null;
        String id = s(j, "id");
        Quest q = quests.computeIfAbsent(id, k -> new Quest());
        q.id = id;
        q.wishId = s(j, "wishId");
        q.title = s(j, "title");
        q.description = s(j, "description");
        q.conditions = s(j, "conditions");
        q.reward = s(j, "reward");
        q.failure = s(j, "failure");
        q.state = s(j, "state");
        q.note = s(j, "note");
        q.owner = s(j, "owner");
        q.priority = (int) l(j, "priority", 5);
        long rem = l(j, "remainingMs", -1);
        q.deadlineLocal = rem < 0 ? -1 : System.currentTimeMillis() + rem;
        q.totalMs = l(j, "totalMs", -1);
        Map<String, Float> shown = new LinkedHashMap<>();
        for (Objective o : q.objectives) shown.put(o.id, o.shown);
        q.objectives.clear();
        for (JsonElement e : j.getAsJsonArray("objectives")) {
            JsonObject oj = e.getAsJsonObject();
            Objective ob = new Objective();
            ob.id = s(oj, "id");
            ob.text = s(oj, "text");
            ob.progress = d(oj, "progress");
            ob.target = d(oj, "target");
            ob.done = b(oj, "done");
            ob.optional = b(oj, "optional");
            ob.shown = shown.getOrDefault(ob.id, 0f);
            q.objectives.add(ob);
        }
        q.updatedAt = System.currentTimeMillis();
        return q;
    }

    private static void putStatus(JsonObject j) {
        Status st = new Status();
        st.id = s(j, "id");
        st.label = s(j, "label");
        st.desc = s(j, "desc");
        st.kind = s(j, "kind");
        st.counter = s(j, "counter");
        long rem = l(j, "remainingMs", -1);
        st.deadlineLocal = rem < 0 ? -1 : System.currentTimeMillis() + rem;
        st.totalMs = l(j, "totalMs", -1);
        if (j.has("icon")) {
            JsonObject icon = j.getAsJsonObject("icon");
            JsonArray rows = icon.getAsJsonArray("pixels");
            st.pixels = new String[rows.size()];
            for (int i = 0; i < rows.size(); i++) st.pixels[i] = rows.get(i).getAsString();
            for (String pair : s(icon, "palette").split(";")) {
                if (pair.length() < 3 || pair.charAt(1) != '=') continue;
                try {
                    st.palette.put(pair.charAt(0), 0xFF000000 | Integer.parseInt(pair.substring(2), 16));
                } catch (NumberFormatException ignored) { }
            }
        }
        statuses.put(st.id, st);
    }

    /** 남은 시간 (ms). 없으면 -1. */
    public static long remaining(long deadlineLocal) {
        return deadlineLocal < 0 ? -1 : Math.max(0, deadlineLocal - System.currentTimeMillis());
    }

    public static Interaction firstInteraction() {
        Interaction first = null;
        for (Interaction it : interactions.values()) if (!it.sent) { first = it; break; }
        return first;
    }

    public static List<Quest> sortedQuests(boolean activeOnly) {
        List<Quest> list = new ArrayList<>();
        for (Quest q : quests.values()) if (!activeOnly || q.active() || System.currentTimeMillis() - q.updatedAt < 8000) list.add(q);
        list.sort((a, b) -> {
            if (a.active() != b.active()) return a.active() ? -1 : 1;
            if (a.priority != b.priority) return b.priority - a.priority;
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        return list;
    }
}
