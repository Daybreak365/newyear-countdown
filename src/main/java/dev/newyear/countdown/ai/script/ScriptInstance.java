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

    public record Region(String name, String dimension, double x, double y, double z, double radius, double height) {
        public boolean contains(String dim, double px, double py, double pz) {
            if (!dim.equals(dimension)) return false;
            double dx = px - x, dz = pz - z;
            return dx * dx + dz * dz <= radius * radius && Math.abs(py - y) <= height;
        }
    }

    public final ScriptRecord rec;
    public final Interpreter in;
    /** 검증용 모의 실행: 세계를 바꾸는 호출은 기록만 하고 실제로 하지 않는다. */
    public final boolean dry;
    public final List<String> effects = new ArrayList<>();
    public final Map<String, List<Listener>> listeners = new HashMap<>();
    public final List<Timer> timers = new ArrayList<>();
    public final Map<String, Region> regions = new LinkedHashMap<>();
    /** "regionName|playerUuid" — 현재 영역 안에 있는 플레이어. */
    public final Set<String> inside = new HashSet<>();
    public Values.JsObject state = new Values.JsObject();
    public Node.Program program;
    public boolean restored;
    public int seq;
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

    public static ScriptInstance of(Interpreter in) {
        return (ScriptInstance) in.hostContext;
    }
}
