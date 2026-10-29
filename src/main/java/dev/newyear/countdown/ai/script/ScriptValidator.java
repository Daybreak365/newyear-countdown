package dev.newyear.countdown.ai.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.Args;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.PlayerRef;
import dev.newyear.countdown.ai.script.lang.Budget;
import dev.newyear.countdown.ai.script.lang.Node;
import dev.newyear.countdown.ai.script.lang.Parser;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.StaticChecker;
import dev.newyear.countdown.ai.script.lang.Stdlib;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 가 쓴 코드를 실제 세계에 적용하기 전에 검사한다.
 * 1) 문법 2) 정적 검사(없는 이름·API 오타·무한 반복) 3) 모의 실행(세계를 바꾸지 않고 효과만 기록)
 * 4) 가상 이벤트·타이머 시뮬레이션 5) 영향 분석(승인 필요 여부).
 */
public final class ScriptValidator {
    private ScriptValidator() {}

    public static final class Report {
        public boolean ok;
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> effects = new ArrayList<>();
        public final List<String> log = new ArrayList<>();
        public final Set<String> events = new LinkedHashSet<>();
        public final Set<String> apiCalls = new LinkedHashSet<>();
        public int timers;
        public long steps;
        public boolean approvalNeeded;
        public final List<String> approvalReasons = new ArrayList<>();

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.add("errors", arr(errors));
            if (!warnings.isEmpty()) o.add("warnings", arr(warnings));
            o.add("simulated_effects", arr(effects.size() > 40 ? effects.subList(0, 40) : effects));
            if (!log.isEmpty()) o.add("console", arr(log.size() > 20 ? log.subList(log.size() - 20, log.size()) : log));
            o.add("subscribed_events", arr(new ArrayList<>(events)));
            o.addProperty("active_timers", timers);
            o.addProperty("steps_used", steps);
            if (approvalNeeded) {
                o.addProperty("requires_admin_approval", true);
                o.add("approval_reasons", arr(approvalReasons));
            }
            return o;
        }

        private static JsonArray arr(List<String> l) {
            JsonArray a = new JsonArray();
            for (String s : l) a.add(s);
            return a;
        }
    }

    /** 가상 이벤트: {event, data?} 와 시뮬레이션할 틱 수. */
    public record SimEvent(String event, JsonObject data) {}

    public static Report validate(ScriptRecord draft, List<SimEvent> sims, int simulateTicks) {
        Report r = new Report();
        Node.Program program;
        try {
            program = Parser.parse(draft.code);
        } catch (ScriptError e) {
            r.errors.add(e.describe());
            return r;
        }
        StaticChecker.Result sc = StaticChecker.check(program, ScriptApi.globalNames(), ScriptApi.HOST_MEMBERS);
        for (StaticChecker.Issue i : sc.issues()) (i.error() ? r.errors : r.warnings).add(i.toString());
        r.apiCalls.addAll(sc.apiCalls());
        r.events.addAll(sc.events());
        for (String ev : sc.events()) if (!EventTypes.ALL.containsKey(ev)) r.errors.add("Unknown event '" + ev + "'. Valid: " + String.join(", ", EventTypes.ALL.keySet()));
        if (!r.errors.isEmpty()) return r;

        // 모의 실행
        ScriptRecord copy = shallowCopy(draft);
        ScriptInstance inst = new ScriptInstance(copy, true);
        inst.program = program;
        Wish wish = copy.wishId == null ? null : WishEngine.get().state().wishes.get(copy.wishId);
        ScriptApi.install(inst, wish);
        AiConfig cfg = AiConfig.get();
        Budget b = new Budget(cfg.scriptStepsPerRun * 2L, cfg.scriptAllocPerRun, 48, Math.max(50, cfg.scriptMillisPerRun * 4L));
        inst.in.budget = b;
        b.reset();
        try {
            inst.in.run(program);
        } catch (ScriptError e) {
            r.errors.add("while running top-level code: " + e.describe());
        } catch (RuntimeException e) {
            r.errors.add("internal error while running top-level code: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        r.steps += b.steps();
        r.events.addAll(inst.listeners.keySet());

        // 가상 이벤트
        if (r.errors.isEmpty() && sims != null) {
            for (SimEvent se : sims) {
                if (!EventTypes.ALL.containsKey(se.event())) {
                    r.errors.add("test event '" + se.event() + "' is not a known event");
                    continue;
                }
                List<ScriptInstance.Listener> ls = inst.listeners.get(se.event());
                if (ls == null || ls.isEmpty()) {
                    r.warnings.add("test event '" + se.event() + "': no listener subscribed");
                    continue;
                }
                JsObject payload = samplePayload(se.event(), se.data(), copy);
                for (ScriptInstance.Listener l : new ArrayList<>(ls)) {
                    b.reset();
                    try {
                        inst.in.invoke(l.fn(), Arrays.asList(payload));
                    } catch (ScriptError e) {
                        r.errors.add("in on('" + se.event() + "') handler: " + e.describe());
                    } catch (RuntimeException e) {
                        r.errors.add("internal error in on('" + se.event() + "'): " + e.getClass().getSimpleName());
                    }
                    r.steps += b.steps();
                }
            }
        }

        // 타이머 시뮬레이션
        if (r.errors.isEmpty() && simulateTicks > 0) {
            long base = WishEngine.get().tick();
            long end = base + Math.min(simulateTicks, 24000);
            int runs = 0;
            while (runs < 200) {
                ScriptInstance.Timer next = null;
                for (ScriptInstance.Timer t : inst.timers) if (!t.cancelled && t.due <= end && (next == null || t.due < next.due)) next = t;
                if (next == null) break;
                if (next.interval > 0) next.due += next.interval;
                else next.cancelled = true;
                b.reset();
                try {
                    inst.in.invoke(next.fn, List.of());
                } catch (ScriptError e) {
                    r.errors.add("in timer callback: " + e.describe());
                    break;
                }
                r.steps += b.steps();
                runs++;
            }
        }
        r.timers = inst.activeTimers();
        r.effects.addAll(inst.effects);
        r.log.addAll(inst.in.log);

        // 영향 분석
        for (String api : r.apiCalls) {
            if (cfg.approvalRequiredApis.contains(api)) {
                r.approvalNeeded = true;
                r.approvalReasons.add("uses " + api);
            }
        }
        long blocks = inst.effects.stream().filter(e -> e.startsWith("setBlock")).count();
        if (blocks > cfg.approvalBlockThreshold) {
            r.approvalNeeded = true;
            r.approvalReasons.add("places " + blocks + " blocks (threshold " + cfg.approvalBlockThreshold + ")");
        }
        if (inst.listenerCount() == 0 && inst.activeTimers() == 0 && copy.durationTicks != 0 && !r.apiCalls.contains("timer")) {
            r.warnings.add("This script subscribes to no events and has no timers; it will only run its top-level code once. Use run_script for one-shot actions.");
        }
        r.ok = r.errors.isEmpty();
        return r;
    }

    private static ScriptRecord shallowCopy(ScriptRecord d) {
        ScriptRecord c = new ScriptRecord();
        c.id = d.id == null ? "dry" : d.id;
        c.name = d.name;
        c.code = d.code;
        c.owner = d.owner;
        c.ownerName = d.ownerName;
        c.wishId = d.wishId;
        c.dimension = d.dimension;
        c.anchor = d.anchor;
        c.durationTicks = d.durationTicks;
        c.autonomous = d.autonomous;
        return c;
    }

    /** 이벤트별 그럴듯한 가짜 페이로드 (data 로 덮어쓸 수 있다; "owner" 문자열은 소원 주인 핸들로 바뀐다). */
    static JsObject samplePayload(String event, JsonObject data, ScriptRecord rec) {
        JsObject p = new JsObject();
        PlayerRef owner = rec.owner == null ? null : new PlayerRef(UUID.fromString(rec.owner), rec.ownerName);
        ServerPlayer live = owner == null ? null : owner.live();
        JsObject here = live == null ? Args.posObj(0, 64, 0) : Args.posObj(live.getX(), live.getY(), live.getZ());
        JsObject item = new JsObject().put("id", "minecraft:wheat_seeds").put("count", 1.0).put("name", "Wheat Seeds").put("special", false);
        p.put("player", owner);
        switch (event) {
            case "chat" -> p.put("message", "hello");
            case "jump" -> p.put("pos", here);
            case "move" -> p.put("from", here).put("to", here);
            case "item_use", "item_drop" -> p.put("item", item).put("hand", "main_hand").put("count", 1.0);
            case "item_pickup" -> p.put("item", item).put("count", 1.0).put("thrower", null);
            case "item_give" -> { p.map.remove("player"); p.put("from", owner).put("to", owner).put("item", item).put("count", 1.0); }
            case "block_place", "block_break" -> p.put("block", "minecraft:dirt").put("pos", here);
            case "attack", "interact_entity" -> p.put("target", EntityRef.fake("minecraft:chicken", new double[]{0, 64, 0})).put("entity", EntityRef.fake("minecraft:chicken", new double[]{0, 64, 0})).put("hand", "main_hand").put("item", item);
            case "kill" -> { p.put("killer", owner).put("victim", EntityRef.fake("minecraft:zombie", new double[]{0, 64, 0})).put("victimType", "minecraft:zombie").put("cause", "player"); }
            case "player_death" -> p.put("cause", "fall").put("killer", null).put("pos", here);
            case "region_enter", "region_leave" -> p.put("region", "test");
            case "effect_added", "effect_removed" -> p.put("effect", "minecraft:speed").put("amplifier", 0.0).put("seconds", 10.0);
            case "dawn", "dusk", "night", "noon" -> { p.map.remove("player"); p.put("day", 1.0); }
            case "timer" -> { p.map.remove("player"); p.put("name", "test").put("data", null); }
            case "custom" -> { p.map.remove("player"); p.put("name", "test").put("data", null).put("source", "test"); }
            case "quest_completed", "quest_failed", "quest_progress" -> p.put("quest", null).put("objective", "o1").put("progress", 1.0).put("target", 1.0);
            case "ui_response" -> p.put("interaction", "test").put("value", "0");
            default -> { }
        }
        if (data != null) {
            for (Map.Entry<String, JsonElement> e : data.entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().equals("owner")) p.put(e.getKey(), owner);
                else p.put(e.getKey(), Stdlib.fromJson(v, null));
            }
        }
        return p;
    }

    /** API 문서를 처음 한 번 채우기 위해 빈 인스턴스에 설치해 본다. */
    public static void primeDocs() {
        if (!ScriptApi.HOST_MEMBERS.isEmpty()) return;
        ScriptRecord rec = new ScriptRecord();
        rec.id = "doc";
        ScriptApi.install(new ScriptInstance(rec, true), null);
    }

    public static boolean isValues(Object o) {
        return o instanceof Values.HostObject;
    }
}
