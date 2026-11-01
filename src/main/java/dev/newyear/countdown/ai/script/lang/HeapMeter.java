package dev.newyear.countdown.ai.script.lang;

import dev.newyear.countdown.ai.script.lang.Interpreter.Binding;
import dev.newyear.countdown.ai.script.lang.Interpreter.Scope;
import dev.newyear.countdown.ai.script.lang.Interpreter.ScriptFunction;
import dev.newyear.countdown.ai.script.lang.Values.HostObject;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 스크립트가 실제로 붙잡고 있는 메모리를 바이트 단위로 잰다.
 * 실행 중의 할당 단위 예산(Budget)과 달리, 실행이 끝난 뒤에도 남아 있는 값 — 전역·state·클로저가 붙잡은 범위·
 * 타이머·리스너 — 을 도달 가능한 그래프로 따라가며 64비트 JVM(압축 포인터) 객체 크기 모형으로 더한다.
 * 같은 객체는 한 번만 센다. 게임 API 객체(HostObject)와 내장 함수는 모든 스크립트가 공유하므로 참조 크기만 센다.
 */
public final class HeapMeter {
    private HeapMeter() {}

    public record Result(long bytes, long objects, boolean overCap) {}

    private static long align(long n) {
        return (n + 7) & ~7L;
    }

    private static void enqueue(ArrayDeque<Object> work, Object value) {
        // null is a valid WishScript value; ArrayDeque explicitly rejects it.
        if (value != null && value != Values.UNDEFINED && !(value instanceof Boolean)) work.push(value);
    }

    private static long hashTable(int size) {
        int cap = 16;
        while (cap * 0.75 < size) cap <<= 1;
        return align(16 + 4L * cap);
    }

    /** 문자열 크기 (Latin-1 이면 글자당 1바이트, 한글 등은 2바이트). */
    public static long stringBytes(String s) {
        boolean latin1 = true;
        for (int i = 0; i < s.length() && latin1; i++) if (s.charAt(i) > 0xFF) latin1 = false;
        return 24 + align(16 + (long) s.length() * (latin1 ? 1 : 2));
    }

    /**
     * @param roots 시작점들 (Scope, 값, 함수 …)
     * @param cap   이 크기를 넘으면 더 세지 않고 멈춘다
     * @param barrier 세지 않을 객체 (보통 내장 함수·API 가 든 전역 범위)
     */
    public static Result measure(Collection<?> roots, long cap, Object barrier) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (barrier != null) seen.add(barrier);
        ArrayDeque<Object> work = new ArrayDeque<>();
        for (Object r : roots) enqueue(work, r);
        long bytes = 0, objects = 0;
        while (!work.isEmpty()) {
            Object v = work.pop();
            if (v == null || v == Values.UNDEFINED || v instanceof Boolean) continue;
            if (!seen.add(v)) continue;
            objects++;
            switch (v) {
                case String s -> bytes += stringBytes(s);
                case Double d -> bytes += 16;
                case JsArray a -> {
                    bytes += 16 + 24 + align(16 + 4L * Math.max(10, a.list.size() + (a.list.size() >> 2)));
                    for (Object o : a.list) enqueue(work, o);
                }
                case JsObject o -> {
                    bytes += 24 + 56 + hashTable(o.map.size()) + 40L * o.map.size();
                    for (Map.Entry<String, Object> e : o.map.entrySet()) {
                        enqueue(work, e.getKey());
                        enqueue(work, e.getValue());
                    }
                }
                case ScriptFunction f -> {
                    bytes += 24;
                    enqueue(work, f.closure);
                    enqueue(work, f.lexicalThis);
                }
                case Scope sc -> {
                    bytes += 16 + 48 + hashTable(sc.vars.size()) + (32L + 24) * sc.vars.size();
                    for (Map.Entry<String, Binding> e : sc.vars.entrySet()) {
                        enqueue(work, e.getKey());
                        enqueue(work, e.getValue().value);
                    }
                    enqueue(work, sc.parent);
                }
                case HostObject h -> bytes += 16;
                default -> bytes += 16;
            }
            if (bytes > cap) return new Result(bytes, objects, true);
        }
        return new Result(bytes, objects, false);
    }

    /** 사람이 읽는 크기 (예: "1.4 MB"). */
    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }
}
