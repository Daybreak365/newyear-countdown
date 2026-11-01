package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Node;
import dev.newyear.countdown.ai.script.lang.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실행 중인(또는 검증용 모의 실행 중인) 스크립트 하나.
 * 이벤트 구독·타이머·영역은 스크립트마다 따로 관리되어 다른 스크립트와 섞이지 않는다.
 */
public final class ScriptInstance {
    public record Listener(int id, String event, Values.Callable fn, Values.JsObject filter, boolean once) {}

    public static final class Timer {
        public int id;
        public long due;
        public long interval;
        public Values.Callable fn;
        public boolean cancelled;
        public int runs;
    }

    /** entity.brain(): 스크립트가 직접 짠 엔티티 AI 목표의 콜백 (엔티티 UUID 하나당 하나). */
    public static final class Brain {
        public int id;
        public String entity;
        public Values.Callable canUse, tick, start, stop;
        public int every = 10;
        public int priority = 2;
    }

    public record Region(String name, String dimension, double x, double y, double z, double radius, double height) {
        public boolean contains(String dim, double px, double py, double pz) {
            if (!dim.equals(dimension)) return false;
            double dx = px - x, dz = pz - z;
            return dx * dx + dz * dz <= radius * radius && Math.abs(py - y) <= height;
        }
    }

    public final ScriptRecord rec;
    /** Source belonging to this running instance, even while an editor changes the shared record. */
    public final String source;
    /** Slot preferences survive an edit without retaining removed actions as live bindings. */
    public final com.google.gson.JsonObject previousControls;
    public final Interpreter in;
    /** 검증용 모의 실행: 세계를 바꾸는 호출은 기록만 하고 실제로 하지 않는다. */
    public final boolean dry;
    public final List<String> effects = new ArrayList<>();
    public int blockedBuildCells;
    public final List<String> blockedBuildReasons = new ArrayList<>();
    /** Shared within one validation, never shared with a live quest or another validation. */
    public final Map<String, dev.newyear.countdown.ai.wish.Quest> dryQuests = new LinkedHashMap<>();
    /** Snapshots of unpublished quests touched by this activation/callback; rolled back on startup failure. */
    public final Map<String, dev.newyear.countdown.ai.wish.Quest> preparingQuests = new LinkedHashMap<>();
    public final Map<String, String> engineQuestObjectives = new LinkedHashMap<>();
    public boolean initialized;
    public boolean awaitingQuestUi;

    public dev.newyear.countdown.ai.wish.Quest questSnapshot(dev.newyear.countdown.ai.wish.Quest quest) {
        if (!dry) {
            if (quest.preparing) preparingQuests.putIfAbsent(quest.id, quest.copy());
            return quest;
        }
        return dryQuests.computeIfAbsent(quest.id, id -> quest.copy());
    }
    public final Map<String, List<Listener>> listeners = new HashMap<>();
    public final List<Timer> timers = new ArrayList<>();
    public final Map<String, Region> regions = new LinkedHashMap<>();
    /** 엔티티 UUID → 스크립트 두뇌. */
    public final Map<String, Brain> brains = new LinkedHashMap<>();
    /** "regionName|playerUuid" — 현재 영역 안에 있는 플레이어. */
    public final Set<String> inside = new HashSet<>();
    public Values.JsObject state = new Values.JsObject();
    public Node.Program program;
    public boolean restored;
    public int seq;
    /** 마지막 힙 측정 이후 실행 횟수. */
    public int runsSinceHeap;
    /** 재시작 때 이어 붙이지 못한 클로저 수 (저장할 수 없는 값을 붙잡은 것). */
    public int lostContinuations;
    public long lastAiRequestTick = -1_000_000;
    public int aiRequests;
    public long blocksThisTick;
    public long blockTickStamp = -1;
    public int memoryWrites;
    /** 모의 실행에서 가짜 엔티티 번호. */
    public int dryEntitySeq;
    /** 실행 중 오류 메시지 (최근). */
    public final List<String> recentErrors = new ArrayList<>();

    public ScriptInstance(ScriptRecord rec, boolean dry) {
        this.rec = rec;
        this.source = rec.code == null ? "" : rec.code;
        this.previousControls = rec.runtime != null && rec.runtime.has("controls")
                ? rec.runtime.getAsJsonObject("controls").deepCopy() : new com.google.gson.JsonObject();
        this.dry = dry;
        this.in = new Interpreter();
        this.in.hostContext = this;
    }

    public int listenerCount() {
        int n = 0;
        for (List<Listener> l : listeners.values()) n += l.size();
        return n;
    }

    public int activeTimers() {
        int n = 0;
        for (Timer t : timers) if (!t.cancelled) n++;
        return n;
    }

    public void effect(String line) {
        if (effects.size() < 200) effects.add(line);
    }

    /** 모의 실행에서 계획된 블록 변경 (미리보기용). 같은 칸은 마지막 것만 남는다. */
    public final List<ScriptRecord.BlockChange> planned = new ArrayList<>();
    public boolean approximateBuild;
    private final Map<String, ScriptRecord.BlockChange> plannedAt = new HashMap<>();

    /** 이 스크립트가 바꾼 칸의 색인 (rec.blocks 를 매번 훑지 않도록). */
    private final java.util.Set<String> blockIndex = new java.util.HashSet<>();
    private int indexedCount = -1;

    private static String blockKey(String dim, int x, int y, int z) {
        return dim + "|" + x + "|" + y + "|" + z;
    }

    /** 이 스크립트가 바꾼(모의 실행이면 계획한) 칸인지. */
    public boolean ownsBlock(String dim, int x, int y, int z) {
        String key = blockKey(dim, x, y, z);
        if (dry) return plannedAt.containsKey(key);
        if (indexedCount != rec.blocks.size()) {
            blockIndex.clear();
            for (ScriptRecord.BlockChange b : rec.blocks) blockIndex.add(blockKey(b.dimension, b.x, b.y, b.z));
            indexedCount = rec.blocks.size();
        }
        return blockIndex.contains(key);
    }

    /** rec.blocks 에 새 칸을 더한 직후 부른다 (색인을 다시 만들지 않고 따라간다). */
    public void noteBlock(ScriptRecord.BlockChange b) {
        if (indexedCount == rec.blocks.size() - 1) {
            blockIndex.add(blockKey(b.dimension, b.x, b.y, b.z));
            indexedCount++;
        }
    }

    public void plan(String dim, int x, int y, int z, String before, String after, boolean temporary) {
        String key = dim + "|" + x + "|" + y + "|" + z;
        ScriptRecord.BlockChange b = plannedAt.get(key);
        if (b == null) {
            if (planned.size() >= 4096) throw new dev.newyear.countdown.ai.script.lang.ScriptError(
                    dev.newyear.countdown.ai.script.lang.ScriptError.Kind.BUDGET,
                    "Build preview exceeds 4096 unique cells; reduce the design instead of silently truncating it",0,0);
            b = new ScriptRecord.BlockChange();
            b.dimension = dim;
            b.x = x;
            b.y = y;
            b.z = z;
            b.before = before;
            plannedAt.put(key, b);
            planned.add(b);
        }
        b.after = after;
        b.temporary = b.temporary || temporary;
    }

    /** 힙 측정 시작점: state, 최상위 변수, 리스너·타이머가 붙잡은 클로저. (전역 내장 API 는 세지 않는다) */
    public List<Object> heapRoots() {
        List<Object> roots = new ArrayList<>();
        roots.add(state);
        if (in.topScope != null) roots.add(in.topScope);
        for (List<Listener> ls : listeners.values()) {
            for (Listener l : ls) {
                roots.add(l.fn());
                if (l.filter() != null) roots.add(l.filter());
            }
        }
        for (Timer t : timers) if (!t.cancelled) roots.add(t.fn);
        for (Brain b : brains.values()) {
            roots.add(b.canUse);
            roots.add(b.tick);
            roots.add(b.start);
            roots.add(b.stop);
        }
        return roots;
    }

    public static ScriptInstance of(Interpreter in) {
        return (ScriptInstance) in.hostContext;
    }
}
