package dev.newyear.countdown.ai.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newyear.countdown.ai.script.lang.ClosureCodec;
import dev.newyear.countdown.ai.script.lang.HeapMeter;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Parser;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.Callable;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 재시작을 건너는 클로저 보존. 실제 ScriptApi 대신 같은 규칙으로 동작하는 최소 API(after/every/cancel/on/once/out)를 깔고,
 * "서버 재시작" = 같은 코드를 새 인터프리터에서 다시 실행한 뒤 저장본을 이어 붙이는 것으로 흉내 낸다.
 */
class ContinuationsTest {
    /** 재시작을 건너 유지되는 것: 출력과 시계만. */
    static final class World {
        final List<String> out = new ArrayList<>();
        long now;
    }

    static final ClosureCodec.Host HOST = new ClosureCodec.Host() {
        @Override
        public String refOf(Values.HostObject h) {
            return null;
        }

        @Override
        public Object resolve(String ref) {
            return null;
        }
    };

    static ScriptInstance boot(World w, String code, JsonObject saved) {
        ScriptRecord rec = new ScriptRecord();
        rec.id = "s-test";
        rec.code = code;
        ScriptInstance inst = new ScriptInstance(rec, false);
        inst.program = Parser.parse(code);
        Interpreter in = inst.in;
        in.define("state", inst.state);
        in.define("out", new NativeFn("out", (i, s, a) -> { w.out.add(Interpreter.toStr(a.get(0))); return Values.UNDEFINED; }));
        in.define("after", new NativeFn("after", (i, s, a) -> timer(w, inst, a, false)));
        in.define("every", new NativeFn("every", (i, s, a) -> timer(w, inst, a, true)));
        in.define("cancel", new NativeFn("cancel", (i, s, a) -> {
            int id = (int) Interpreter.toNumber(a.get(0));
            for (ScriptInstance.Timer t : inst.timers) if (t.id == id) t.cancelled = true;
            return Values.UNDEFINED;
        }));
        NativeFn.Impl sub = (i, s, a) -> {
            int id = ++inst.seq;
            String ev = Interpreter.toStr(a.get(0));
            inst.listeners.computeIfAbsent(ev, k -> new ArrayList<>()).add(new ScriptInstance.Listener(id, ev, (Callable) a.get(1), null, false));
            return (double) id;
        };
        in.define("on", new NativeFn("on", sub));
        in.define("once", new NativeFn("once", (i, s, a) -> {
            int id = ++inst.seq;
            String ev = Interpreter.toStr(a.get(0));
            inst.listeners.computeIfAbsent(ev, k -> new ArrayList<>()).add(new ScriptInstance.Listener(id, ev, (Callable) a.get(1), null, true));
            return (double) id;
        }));
        in.budget.reset();
        inst.restored = saved != null;
        in.define("restored", inst.restored);
        in.run(inst.program);
        if (saved != null) Continuations.restore(inst, saved, w.now, HOST);
        return inst;
    }

    static Object timer(World w, ScriptInstance inst, List<Object> a, boolean repeat) {
        ScriptInstance.Timer t = new ScriptInstance.Timer();
        t.id = ++inst.seq;
        long ticks = (long) Interpreter.toNumber(a.get(0));
        t.due = w.now + ticks;
        t.interval = repeat ? ticks : 0;
        t.fn = (Callable) a.get(1);
        inst.timers.add(t);
        return (double) t.id;
    }

    static void advance(World w, ScriptInstance inst, long to) {
        while (true) {
            ScriptInstance.Timer next = inst.timers.stream().filter(t -> !t.cancelled && t.due <= to).min(Comparator.comparingLong(t -> t.due)).orElse(null);
            if (next == null) break;
            w.now = next.due;
            if (next.interval > 0) next.due += next.interval;
            else next.cancelled = true;
            inst.in.budget.reset();
            inst.in.invoke(next.fn, List.of());
        }
        inst.timers.removeIf(t -> t.cancelled);
        w.now = to;
    }

    static void emit(ScriptInstance inst, String ev, JsObject payload) {
        List<ScriptInstance.Listener> ls = inst.listeners.get(ev);
        if (ls == null) return;
        for (ScriptInstance.Listener l : new ArrayList<>(ls)) {
            if (l.once()) ls.remove(l);
            inst.in.budget.reset();
            inst.in.invoke(l.fn(), Arrays.asList(payload));
        }
    }

    static JsonObject saveAndReload(ScriptInstance inst, long now) {
        // 디스크에 쓰고 다시 읽는 것과 같게 문자열로 한 번 돌린다
        return JsonParser.parseString(Continuations.save(inst, now, HOST).json().toString()).getAsJsonObject();
    }

    @Test
    void handlerCreatedTimerResumesWithItsCapturedLocals() {
        String code = """
                on('jump', (e) => {
                  const who = e.name;
                  let count = 0;
                  const tick = () => { count++; out(who + ':' + count); if (count < 3) after(10, tick); };
                  after(10, tick);
                });
                """;
        World w = new World();
        ScriptInstance a = boot(w, code, null);
        emit(a, "jump", new JsObject().put("name", "나비"));
        advance(w, a, 15);
        assertEquals(List.of("나비:1"), w.out);
        JsonObject saved = saveAndReload(a, w.now);   // 남은 시간 5틱

        w.now = 1000;                                  // 재시작 (엔진 시계는 이어진다고 가정하지 않는다)
        ScriptInstance b = boot(w, code, saved);
        advance(w, b, 1004);
        assertEquals(1, w.out.size(), "remaining time is kept, not reset");
        advance(w, b, 1100);
        assertEquals(List.of("나비:1", "나비:2", "나비:3"), w.out, "captured `count` and the self-referencing closure continue");
    }

    @Test
    void topLevelTimersKeepPhaseAndFinishedOnesDoNotRepeat() {
        String code = """
                after(20, () => out('welcome'));
                every(50, () => out('pulse'));
                const t = every(30, () => out('cancel-me'));
                on('x', () => cancel(t));
                """;
        World w = new World();
        ScriptInstance a = boot(w, code, null);
        advance(w, a, 25);
        emit(a, "x", new JsObject());
        assertEquals(List.of("welcome"), w.out);
        JsonObject saved = saveAndReload(a, w.now);

        w.out.clear();
        w.now = 5000;
        ScriptInstance b = boot(w, code, saved);
        advance(w, b, 5030);
        assertEquals(List.of("pulse"), w.out, "welcome already happened and the cancelled timer stays cancelled; pulse keeps its 25-tick phase");
    }

    @Test
    void guardedTopLevelWorkIsRevivedNotLost() {
        // AI 가 자주 쓰는 모양: 처음 한 번만 만드는 것은 script.restored 로 감싼다
        String code = """
                if (!restored) {
                  const name = '별고양이';
                  after(100, () => out(name + ' 떠남'));
                  once('chat', () => out('첫 인사'));
                }
                every(40, () => out('tick'));
                """;
        World w = new World();
        ScriptInstance a = boot(w, code, null);
        advance(w, a, 50);
        assertEquals(List.of("tick"), w.out);
        JsonObject saved = saveAndReload(a, w.now);   // after 는 50틱 남음, every 는 30틱 남음

        w.out.clear();
        w.now = 9000;
        ScriptInstance b = boot(w, code, saved);
        emit(b, "chat", new JsObject());
        advance(w, b, 9055);
        assertEquals(List.of("첫 인사", "tick", "별고양이 떠남"), w.out, "the guarded after()/once() come back with their captured locals; every keeps its phase");
    }

    @Test
    void dynamicListenersSurviveAndSharedObjectsStayShared() {
        String code = """
                state.box = state.box ?? null;
                on('start', () => {
                  const shared = {hits: 0};
                  once('chat', (e) => { shared.hits++; out('once ' + shared.hits); });
                  on('jump', () => { shared.hits++; out('jump ' + shared.hits); });
                });
                """;
        World w = new World();
        ScriptInstance a = boot(w, code, null);
        emit(a, "start", new JsObject());
        emit(a, "jump", new JsObject());
        JsonObject saved = saveAndReload(a, w.now);

        ScriptInstance b = boot(w, code, saved);
        emit(b, "chat", new JsObject());
        emit(b, "chat", new JsObject());
        emit(b, "jump", new JsObject());
        assertEquals(List.of("jump 1", "once 2", "jump 3"), w.out, "both handlers still share one object; once() fires once");
    }

    @Test
    void unsavableCapturesAreReportedNotCrashing() {
        String code = """
                on('go', () => { const f = out; after(5, () => f('x')); });
                """;
        World w = new World();
        ScriptInstance a = boot(w, code, null);
        a.in.define("out2", new NativeFn("out2", (i, s, x) -> Values.UNDEFINED));
        emit(a, "go", new JsObject());
        // out 은 전역이라 이름으로 저장할 수 있다 → 저장 성공
        var saved = Continuations.save(a, w.now, HOST);
        assertTrue(saved.skipped().isEmpty(), saved.skipped().toString());

        String code2 = "on('go', () => { const m = {k: 1}; const g = Object.keys; after(5, () => out(g(m))); });";
        ScriptInstance c = boot(w, code2, null);
        emit(c, "go", new JsObject());
        var saved2 = Continuations.save(c, w.now, HOST);
        assertEquals(1, saved2.skipped().size(), "a detached built-in method cannot be saved");
    }

    @Test
    void changedCodeResumesNothing() {
        World w = new World();
        ScriptInstance a = boot(w, "on('go', () => after(5, () => out('old')));", null);
        emit(a, "go", new JsObject());
        JsonObject saved = saveAndReload(a, w.now);
        ScriptInstance b = boot(w, "on('go', () => after(5, () => out('new')));", saved);
        advance(w, b, 100);
        assertTrue(w.out.isEmpty());
        assertEquals(1, b.listenerCount(), "new code must keep its new listeners");
        emit(b, "go", new JsObject());
        advance(w, b, 110);
        assertEquals(List.of("new"), w.out);
    }

    @Test
    void editingSharedRecordBeforeStoppingCannotRelabelOldCallbacks() {
        World w = new World();
        String oldCode = "on('ability_input', e => out('old')); on('player_join', e => out('join'));";
        String newCode = "state.updated = true;\n\non('ability_input', e => { out('new'); });\non('player_join', e => { out('new join'); });";
        ScriptInstance old = boot(w, oldCode, null);
        saveAndReload(old, 0); // update_script saves before changing the shared record
        old.rec.code = newCode;
        JsonObject onStop = saveAndReload(old, 0); // activate -> stop -> saveState saves again
        assertEquals(Continuations.codeHash(oldCode), onStop.get("code").getAsString());
        ScriptInstance replacement = boot(w, newCode, onStop);
        assertEquals(2, replacement.listenerCount());
        emit(replacement, "ability_input", new JsObject());
        assertEquals(List.of("new"), w.out);
    }

    @Test
    void editingCodeDoesNotReviveOldPendingActionsUnderNewFunctionBodies() {
        World w = new World();
        String oldCode = "after(5, () => out('old')); on('go', () => out('old'));";
        String newCode = "after(5, () => out('new')); on('go', () => out('new'));";
        ScriptInstance old = boot(w, oldCode, null);
        advance(w, old, 3);
        old.rec.code = newCode;
        ScriptInstance replacement = boot(w, newCode, saveAndReload(old, w.now));
        advance(w, replacement, 5);
        assertTrue(w.out.isEmpty(), "new code must not inherit the old timer's remaining duration");
        advance(w, replacement, 8);
        emit(replacement, "go", new JsObject());
        assertEquals(List.of("new", "new"), w.out);
    }

    @Test
    void heapMeterCountsRetainedBytes() {
        World w = new World();
        ScriptInstance small = boot(w, "state.x = 1;", null);
        long base = HeapMeter.measure(small.heapRoots(), Long.MAX_VALUE, small.in.globals).bytes();
        ScriptInstance big = boot(w, "const keep = []; for (let i = 0; i < 2000; i++) keep.push('새벽별의 기억 ' + i); on('x', () => keep.length);", null);
        var r = HeapMeter.measure(big.heapRoots(), Long.MAX_VALUE, big.in.globals);
        // 한글 문자열 2000개 ≈ 2000 × (24 + 16 + 2×10~13) 바이트
        assertTrue(r.bytes() - base > 2000 * 60, "measured " + r.bytes());
        assertTrue(HeapMeter.measure(big.heapRoots(), 50_000, big.in.globals).overCap());
        // 리스너가 없으면 최상위 변수는 도달할 수 없으므로 세지 않는다 (가비지)
        ScriptInstance garbage = boot(w, "const tmp = []; for (let i = 0; i < 2000; i++) tmp.push('x' + i);", null);
        garbage.in.topScope = null;
        assertTrue(HeapMeter.measure(garbage.heapRoots(), Long.MAX_VALUE, garbage.in.globals).bytes() < 10_000);
    }
}
