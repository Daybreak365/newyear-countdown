package dev.newyear.countdown.ai.script.lang;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로그램 안의 모든 함수 노드를 "줄:칸:인자 수:종류" 키로 찾는 색인.
 * 같은 코드를 다시 파싱하면 같은 키가 나오므로, 저장해 둔 클로저가 재시작 뒤에도 자기 함수 본문을 찾을 수 있다.
 * (호스트 쪽 코드다 — 스크립트는 이 클래스에 닿을 수 없다.)
 */
public final class FuncIndex {
    private final Map<String, Node.Func> byKey = new HashMap<>();

    private FuncIndex() {}

    public static String key(Node.Func f) {
        return f.line() + ":" + f.col() + ":" + f.params().size() + (f.arrow() ? "a" : "f");
    }

    public static FuncIndex build(Node.Program program) {
        FuncIndex idx = new FuncIndex();
        idx.walk(program, 0);
        return idx;
    }

    public Node.Func get(String key) {
        return byKey.get(key);
    }

    public int size() {
        return byKey.size();
    }

    private void walk(Object n, int depth) {
        if (n == null || depth > 400) return;
        if (n instanceof List<?> list) {
            for (Object o : list) walk(o, depth + 1);
            return;
        }
        if (!(n instanceof Record r)) return;
        if (n instanceof Node.Func f) byKey.putIfAbsent(key(f), f);
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            Class<?> t = c.getType();
            if (t.isPrimitive() || t == String.class) continue;
            try {
                walk(c.getAccessor().invoke(r), depth + 1);
            } catch (ReflectiveOperationException e) {
                // 노드 레코드는 모두 공개 접근자를 가진다
            }
        }
    }
}
