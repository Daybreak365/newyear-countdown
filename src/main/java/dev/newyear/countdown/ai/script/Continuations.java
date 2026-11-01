package dev.newyear.countdown.ai.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.script.lang.ClosureCodec;
import dev.newyear.countdown.ai.script.lang.FuncIndex;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 재시작을 건너 스크립트의 "이어서 할 일"을 보존한다 — 대기 중인 after()/every(), 등록된 on()/once(),
 * 엔티티에 붙인 스크립트 두뇌(entity.brain)의 콜백까지.
 *
 * <p>재시작하면 최상위 코드가 다시 실행되며 일부를 새로 만든다. 그래서 저장본과 새로 만든 것을 "같은 함수(소스 위치)"끼리
 * 순서대로 짝짓는다.
 * <ul>
 *   <li>짝이 있으면: 새로 만든 쪽을 쓰되 남은 시간·실행 횟수는 저장본대로 (다시 처음부터 기다리지 않는다).</li>
 *   <li>저장본만 있으면: 클로저째 되살린다 ({@link ClosureCodec}) — 핸들러 안에서 만든 타이머,
 *       {@code if (!script.restored)} 로 감싼 최상위 코드가 만든 것 모두.</li>
 *   <li>새로 만든 것만 있으면: 재시작 전에 이미 끝났거나(일회성 타이머, once) 취소된 것이므로 취소한다
 *       (같은 환영 인사가 두 번 나오지 않게).</li>
 *   <li>코드가 바뀌었으면(update_script, revert) 아무것도 이어 붙이지 않는다.</li>
 * </ul>
 * 저장할 수 없는 값을 붙잡은 클로저만 빠지고, 그 이유를 남긴다. Minecraft 와 무관하다 (핸들 변환은 {@link ClosureCodec.Host}).
 */
public final class Continuations {
    private Continuations() {}

    public static final int MAX_NODES = 20_000;
    public static final int MAX_BYTES = 96 * 1024;

    public static String codeHash(String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(code.hashCode());
        }
    }

    static String fnKey(Values.Callable fn) {
        return fn instanceof Interpreter.ScriptFunction sf ? FuncIndex.key(sf.declaration()) : "native:" + fn.name();
    }

    /** 저장 결과: JSON 과 저장하지 못한 항목 설명. */
    public record Saved(JsonObject json, List<String> skipped) {}

    public static Saved save(ScriptInstance inst, long now, ClosureCodec.Host host) {
        JsonObject o = new JsonObject();
        List<String> skipped = new ArrayList<>();
        o.addProperty("code", codeHash(inst.source));
        o.addProperty("seq", inst.seq);
        ClosureCodec.Encoder enc = new ClosureCodec.Encoder(inst.in, inst.state, host, MAX_NODES);
        JsonArray timers = new JsonArray(), listeners = new JsonArray(), brains = new JsonArray();
        for (ScriptInstance.Timer t : inst.timers) {
            if (t.cancelled) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", t.id);
            j.addProperty("rem", Math.max(1, t.due - now));
            j.addProperty("iv", t.interval);
            j.addProperty("runs", t.runs);
            j.addProperty("k", fnKey(t.fn));
            try {
                j.add("fn", enc.value(t.fn));
            } catch (ClosureCodec.Unserializable e) {
                skipped.add((t.interval > 0 ? "every" : "after") + "(" + t.fn.name() + "): " + e.getMessage());
            }
            timers.add(j);
        }
        for (Map.Entry<String, List<ScriptInstance.Listener>> en : inst.listeners.entrySet()) {
            for (ScriptInstance.Listener l : en.getValue()) {
                JsonObject j = new JsonObject();
                j.addProperty("id", l.id());
                j.addProperty("ev", l.event());
                j.addProperty("once", l.once());
                j.addProperty("k", fnKey(l.fn()));
                try {
                    j.add("fn", enc.value(l.fn()));
                    if (l.filter() != null) j.add("filter", enc.value(l.filter()));
                } catch (ClosureCodec.Unserializable e) {
                    j.remove("fn");
                    skipped.add("on('" + l.event() + "'): " + e.getMessage());
                }
                listeners.add(j);
            }
        }
        for (ScriptInstance.Brain b : inst.brains.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", b.id);
            j.addProperty("entity", b.entity);
            j.addProperty("every", b.every);
            j.addProperty("priority", b.priority);
            try {
                if (b.canUse != null) j.add("canUse", enc.value(b.canUse));
                if (b.tick != null) j.add("tick", enc.value(b.tick));
                if (b.start != null) j.add("start", enc.value(b.start));
                if (b.stop != null) j.add("stop", enc.value(b.stop));
                brains.add(j);
            } catch (ClosureCodec.Unserializable e) {
                skipped.add("brain: " + e.getMessage());
            }
        }
        o.add("timers", timers);
        o.add("listeners", listeners);
        o.add("brains", brains);
        o.add("table", enc.table());
        if (o.toString().length() > MAX_BYTES) {
            // 너무 크면 클로저는 버리고 짝짓기 정보(남은 시간)만 남긴다
            skipped.add("closure data over " + (MAX_BYTES / 1024) + " KB; only timer phases kept");
            for (JsonElement e : timers) e.getAsJsonObject().remove("fn");
            for (JsonElement e : listeners) {
                e.getAsJsonObject().remove("fn");
                e.getAsJsonObject().remove("filter");
            }
            o.add("brains", new JsonArray());
            o.add("table", new JsonObject());
        }
        if (!skipped.isEmpty()) {
            JsonArray sk = new JsonArray();
            for (String s : skipped) sk.add(s);
            o.add("skipped", sk);
        }
        return new Saved(o, skipped);
    }

    /** 복구 결과. kept: 짝지어 남은 시간을 맞춘 것, revived: 클로저째 되살린 것, dropped: 이미 끝났다고 보고 취소한 것. */
    public record Restored(int kept, int revived, int dropped, int brains, List<String> lost) {}

    /**
     * 최상위 코드를 다시 실행한 직후에 부른다 (inst 의 타이머·리스너·두뇌는 이번 실행이 새로 만든 것뿐이다).
     * 저장본이 없거나 코드가 바뀌었으면 아무것도 하지 않는다.
     */
    public static Restored restore(ScriptInstance inst, JsonObject saved, long now, ClosureCodec.Host host) {
        List<String> lost = new ArrayList<>();
        if (saved == null || !saved.has("code")) return new Restored(0, 0, 0, 0, lost);
        if (!saved.get("code").getAsString().equals(codeHash(inst.source))) {
            lost.add("code changed since the save; nothing resumed");
            return new Restored(0, 0, 0, 0, lost);
        }
        FuncIndex funcs = FuncIndex.build(inst.program);
        ClosureCodec.Decoder dec = new ClosureCodec.Decoder(inst.in, funcs, inst.state, host,
                saved.has("table") ? saved.getAsJsonObject("table") : new JsonObject());
        Set<Integer> usedIds = new HashSet<>();
        for (ScriptInstance.Timer t : inst.timers) usedIds.add(t.id);
        for (List<ScriptInstance.Listener> ls : inst.listeners.values()) for (ScriptInstance.Listener l : ls) usedIds.add(l.id());
        int[] seq = {Math.max(inst.seq, saved.has("seq") ? saved.get("seq").getAsInt() : 0)};
        int kept = 0, revived = 0, dropped = 0, brains = 0;

        // ---- 타이머: 같은 함수끼리 순서대로 짝짓기
        Map<String, ArrayDeque<ScriptInstance.Timer>> fresh = new LinkedHashMap<>();
        for (ScriptInstance.Timer t : inst.timers) if (!t.cancelled) fresh.computeIfAbsent(fnKey(t.fn), k -> new ArrayDeque<>()).add(t);
        Set<ScriptInstance.Timer> paired = new HashSet<>();
        List<ScriptInstance.Timer> add = new ArrayList<>();
        if (saved.has("timers")) for (JsonElement e : saved.getAsJsonArray("timers")) {
            JsonObject j = e.getAsJsonObject();
            ArrayDeque<ScriptInstance.Timer> q = fresh.get(j.get("k").getAsString());
            ScriptInstance.Timer match = q == null ? null : q.poll();
            if (match != null) {
                match.due = now + Math.max(1, j.get("rem").getAsLong());
                match.runs = j.has("runs") ? j.get("runs").getAsInt() : match.runs;
                paired.add(match);
                kept++;
                continue;
            }
            if (!j.has("fn")) {
                lost.add("timer " + j.get("k").getAsString() + " was not savable");
                continue;
            }
            try {
                Object fn = dec.value(j.get("fn"));
                if (!(fn instanceof Values.Callable c)) throw new ClosureCodec.Unserializable("not a function");
                ScriptInstance.Timer t = new ScriptInstance.Timer();
                int id = j.get("id").getAsInt();
                t.id = usedIds.add(id) ? id : ++seq[0];
                t.due = now + Math.max(1, j.get("rem").getAsLong());
                t.interval = j.get("iv").getAsLong();
                t.runs = j.has("runs") ? j.get("runs").getAsInt() : 0;
                t.fn = c;
                add.add(t);
                revived++;
            } catch (ClosureCodec.Unserializable | RuntimeException ex) {
                lost.add("timer: " + ex.getMessage());
            }
        }
        for (ScriptInstance.Timer t : inst.timers) {
            if (!t.cancelled && !paired.contains(t)) {
                t.cancelled = true;      // 재시작 전에 이미 끝났거나 취소된 타이머
                dropped++;
            }
        }
        inst.timers.addAll(add);

        // ---- 리스너: (이벤트, 함수) 끼리 짝짓기
        Map<String, ArrayDeque<ScriptInstance.Listener>> freshL = new LinkedHashMap<>();
        for (List<ScriptInstance.Listener> ls : inst.listeners.values()) for (ScriptInstance.Listener l : ls) freshL.computeIfAbsent(l.event() + "|" + fnKey(l.fn()), k -> new ArrayDeque<>()).add(l);
        Set<ScriptInstance.Listener> pairedL = new HashSet<>();
        if (saved.has("listeners")) for (JsonElement e : saved.getAsJsonArray("listeners")) {
            JsonObject j = e.getAsJsonObject();
            String ev = j.get("ev").getAsString();
            ArrayDeque<ScriptInstance.Listener> q = freshL.get(ev + "|" + j.get("k").getAsString());
            ScriptInstance.Listener match = q == null ? null : q.poll();
            if (match != null) {
                pairedL.add(match);
                kept++;
                continue;
            }
            if (!j.has("fn")) {
                lost.add("on('" + ev + "') was not savable");
                continue;
            }
            try {
                Object fn = dec.value(j.get("fn"));
                if (!(fn instanceof Values.Callable c)) throw new ClosureCodec.Unserializable("not a function");
                Object filter = j.has("filter") ? dec.value(j.get("filter")) : null;
                if (!EventTypes.ALL.containsKey(ev)) throw new ClosureCodec.Unserializable("unknown event " + ev);
                int id = j.get("id").getAsInt();
                ScriptInstance.Listener l = new ScriptInstance.Listener(usedIds.add(id) ? id : ++seq[0], ev, c,
                        filter instanceof Values.JsObject f ? f : null, j.get("once").getAsBoolean());
                inst.listeners.computeIfAbsent(ev, k -> new ArrayList<>()).add(l);
                pairedL.add(l);
                if (!inst.rec.subscriptions.contains(ev)) inst.rec.subscriptions.add(ev);
                revived++;
            } catch (ClosureCodec.Unserializable | RuntimeException ex) {
                lost.add("listener: " + ex.getMessage());
            }
        }
        for (List<ScriptInstance.Listener> ls : inst.listeners.values()) {
            int before = ls.size();
            ls.removeIf(l -> !pairedL.contains(l));      // 이미 한 번 불린 once, 또는 off() 로 뗀 것
            dropped += before - ls.size();
        }

        // ---- 엔티티 두뇌: 이번 실행이 다시 만들지 않은 엔티티 것만 되살린다
        if (saved.has("brains")) for (JsonElement e : saved.getAsJsonArray("brains")) {
            JsonObject j = e.getAsJsonObject();
            String ent = j.get("entity").getAsString();
            if (inst.brains.containsKey(ent)) continue;
            try {
                ScriptInstance.Brain b = new ScriptInstance.Brain();
                b.id = j.get("id").getAsInt();
                b.entity = ent;
                b.every = j.get("every").getAsInt();
                b.priority = j.get("priority").getAsInt();
                b.canUse = j.has("canUse") ? (Values.Callable) dec.value(j.get("canUse")) : null;
                b.tick = j.has("tick") ? (Values.Callable) dec.value(j.get("tick")) : null;
                b.start = j.has("start") ? (Values.Callable) dec.value(j.get("start")) : null;
                b.stop = j.has("stop") ? (Values.Callable) dec.value(j.get("stop")) : null;
                inst.brains.put(ent, b);
                brains++;
            } catch (ClosureCodec.Unserializable | RuntimeException ex) {
                lost.add("brain: " + ex.getMessage());
            }
        }
        inst.seq = seq[0];
        return new Restored(kept, revived, dropped, brains, lost);
    }
}
