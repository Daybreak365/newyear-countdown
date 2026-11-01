package dev.newyear.countdown.ai.script.lang;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.newyear.countdown.ai.script.lang.Interpreter.Binding;
import dev.newyear.countdown.ai.script.lang.Interpreter.Scope;
import dev.newyear.countdown.ai.script.lang.Interpreter.ScriptFunction;
import dev.newyear.countdown.ai.script.lang.Values.Callable;
import dev.newyear.countdown.ai.script.lang.Values.HostObject;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static dev.newyear.countdown.ai.script.lang.Values.UNDEFINED;

/**
 * 스크립트 클로저(함수 + 그 함수가 붙잡은 변수들)를 JSON 으로 저장하고 되살린다.
 * 서버가 다시 켜질 때 after()/every() 콜백과 실행 중에 등록한 on() 핸들러를 이어서 돌리기 위해 쓴다.
 *
 * <p>규칙
 * <ul>
 *   <li>함수 본문은 저장하지 않는다 — 같은 코드를 다시 파싱한 AST 에서 {@link FuncIndex} 키로 찾는다.</li>
 *   <li>최상위 범위(top)와 전역(globals)은 저장하지 않는다 — 재시작 때 최상위 코드가 다시 만든 것을 그대로 잇는다.
 *       그 아래 범위(핸들러 안의 지역 변수, 루프 변수 …)만 값째 저장한다.</li>
 *   <li>같은 객체·배열·함수·범위를 여러 곳에서 가리키면 하나로 저장해 공유 관계(그리고 순환)를 지킨다.
 *       {@code state} 객체는 저장하지 않고 "state" 표시만 남긴다 (state 는 따로 저장된다).</li>
 *   <li>게임 핸들(Player/Entity/Quest)은 참조 문자열로, 전역 API 객체(world, ui …)는 이름으로 저장한다.
 *       그 밖의 자바 쪽 값(다른 API 객체에서 떼어 낸 메서드 등)은 저장할 수 없다 → {@link Unserializable}.</li>
 * </ul>
 */
public final class ClosureCodec {
    private ClosureCodec() {}

    public static final class Unserializable extends Exception {
        public Unserializable(String m) {
            super(m, null, false, false);
        }
    }

    /** 게임 핸들 처리 (참조 문자열 ↔ 핸들). */
    public interface Host {
        /** 핸들의 참조 문자열 (예: "player:<uuid>"). 저장할 수 없으면 null. */
        String refOf(HostObject h);

        /** 참조 문자열 → 핸들 (없으면 null). */
        Object resolve(String ref);
    }

    private static Map<Object, String> globalIdentity(Interpreter in) {
        Map<Object, String> m = new IdentityHashMap<>();
        for (Map.Entry<String, Binding> e : in.globals.vars.entrySet()) {
            Object v = e.getValue().value;
            if (v != null && !(v instanceof String) && !(v instanceof Double) && !(v instanceof Boolean) && v != UNDEFINED) m.putIfAbsent(v, e.getKey());
        }
        return m;
    }

    // ================================================================ 저장

    public static final class Encoder {
        private final Interpreter in;
        private final Scope top;
        private final Object state;
        private final Host host;
        private final Map<Object, String> globals;
        private final IdentityHashMap<Object, String> ids = new IdentityHashMap<>();
        private final JsonObject table = new JsonObject();
        private int seq;
        private int budget;

        public Encoder(Interpreter in, Object state, Host host, int maxNodes) {
            this.in = in;
            this.top = in.topScope;
            this.state = state;
            this.host = host;
            this.globals = globalIdentity(in);
            this.budget = maxNodes;
        }

        /** 공유된 객체·범위 표 (value() 들을 다 부른 뒤에 저장). */
        public JsonObject table() {
            return table;
        }

        private void spend() throws Unserializable {
            if (--budget < 0) throw new Unserializable("closure data too large");
        }

        public JsonElement value(Object v) throws Unserializable {
            spend();
            if (v == null) return JsonNull.INSTANCE;
            if (v == UNDEFINED) return tag("u", new JsonPrimitive(1));
            if (v instanceof Boolean b) return new JsonPrimitive(b);
            if (v instanceof String s) return new JsonPrimitive(s);
            if (v instanceof Double d) {
                if (d.isNaN() || d.isInfinite()) return tag("n", new JsonPrimitive(d.isNaN() ? "NaN" : d > 0 ? "Inf" : "-Inf"));
                return new JsonPrimitive(d);
            }
            if (v == state) return tag("state", new JsonPrimitive(1));
            String g = globals.get(v);
            if (g != null) return tag("g", new JsonPrimitive(g));
            String seen = ids.get(v);
            if (seen != null) return tag("r", new JsonPrimitive(seen));
            if (v instanceof JsArray a) {
                String id = newId(v);
                JsonArray arr = new JsonArray();
                JsonObject entry = new JsonObject();
                entry.add("a", arr);
                table.add(id, entry);
                for (Object o : a.list) arr.add(value(o));
                return tag("r", new JsonPrimitive(id));
            }
            if (v instanceof JsObject o) {
                String id = newId(v);
                JsonObject map = new JsonObject();
                JsonObject entry = new JsonObject();
                entry.add("o", map);
                if (o.frozen) entry.addProperty("frozen", true);
                table.add(id, entry);
                for (Map.Entry<String, Object> e : o.map.entrySet()) map.add(e.getKey(), value(e.getValue()));
                return tag("r", new JsonPrimitive(id));
            }
            if (v instanceof ScriptFunction f) {
                String id = newId(v);
                JsonObject entry = new JsonObject();
                entry.addProperty("f", FuncIndex.key(f.decl));
                if (f.decl.name() != null) entry.addProperty("name", f.decl.name());
                table.add(id, entry);
                entry.add("s", scope(f.closure));
                if (f.lexicalThis != null) entry.add("this", value(f.lexicalThis));
                return tag("r", new JsonPrimitive(id));
            }
            if (v instanceof HostObject h) {
                String ref = host == null ? null : host.refOf(h);
                if (ref != null) return tag("h", new JsonPrimitive(ref));
                throw new Unserializable("captures a " + h.typeName() + " value that cannot be saved");
            }
            if (v instanceof Callable c) throw new Unserializable("captures built-in function " + c.name() + " (call it through its object instead)");
            throw new Unserializable("captures an unsupported value");
        }

        private String newId(Object v) {
            String id = Integer.toString(seq++, 36);
            ids.put(v, id);
            return id;
        }

        private JsonElement scope(Scope s) throws Unserializable {
            spend();
            if (s == null || s == in.globals) return new JsonPrimitive("g");
            if (s == top) return new JsonPrimitive("t");
            String seen = ids.get(s);
            if (seen != null) return new JsonPrimitive(seen);
            if (top == null) throw new Unserializable("no top-level scope");
            String id = newId(s);
            JsonObject entry = new JsonObject();
            table.add(id, entry);
            entry.add("p", scope(s.parent));
            JsonObject vars = new JsonObject();
            for (Map.Entry<String, Binding> e : s.vars.entrySet()) {
                JsonArray pair = new JsonArray();
                pair.add(value(e.getValue().value));
                pair.add(e.getValue().constant);
                vars.add(e.getKey(), pair);
            }
            entry.add("v", vars);
            return new JsonPrimitive(id);
        }

        private static JsonObject tag(String k, JsonElement v) {
            JsonObject o = new JsonObject();
            o.add("$" + k, v);
            return o;
        }
    }

    // ================================================================되살리기

    public static final class Decoder {
        private final Interpreter in;
        private final Scope top;
        private final Object state;
        private final Host host;
        private final FuncIndex funcs;
        private final JsonObject table;
        private final Map<String, Object> made = new HashMap<>();
        private final Map<String, Binding> globals;

        public Decoder(Interpreter in, FuncIndex funcs, Object state, Host host, JsonObject table) {
            this.in = in;
            this.top = in.topScope;
            this.state = state;
            this.host = host;
            this.funcs = funcs;
            this.table = table == null ? new JsonObject() : table;
            this.globals = in.globals.vars;
        }

        public Object value(JsonElement e) throws Unserializable {
            if (e == null || e.isJsonNull()) return null;
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isBoolean()) return p.getAsBoolean();
                if (p.isNumber()) return p.getAsDouble();
                return p.getAsString();
            }
            if (!e.isJsonObject()) throw new Unserializable("bad closure data");
            JsonObject o = e.getAsJsonObject();
            if (o.has("$u")) return UNDEFINED;
            if (o.has("$n")) {
                String n = o.get("$n").getAsString();
                return n.equals("NaN") ? Double.NaN : n.equals("Inf") ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            }
            if (o.has("$state")) return state;
            if (o.has("$g")) {
                Binding b = globals.get(o.get("$g").getAsString());
                if (b == null) throw new Unserializable("global " + o.get("$g").getAsString() + " no longer exists");
                return b.value;
            }
            if (o.has("$h")) {
                Object h = host == null ? null : host.resolve(o.get("$h").getAsString());
                if (h == null) throw new Unserializable("cannot restore handle " + o.get("$h").getAsString());
                return h;
            }
            if (o.has("$r")) return ref(o.get("$r").getAsString());
            throw new Unserializable("bad closure value");
        }

        private Object ref(String id) throws Unserializable {
            Object done = made.get(id);
            if (done != null) return done;
            JsonObject entry = table.getAsJsonObject(id);
            if (entry == null) throw new Unserializable("missing closure entry " + id);
            if (entry.has("a")) {
                JsArray a = new JsArray();
                made.put(id, a);
                for (JsonElement x : entry.getAsJsonArray("a")) a.list.add(value(x));
                return a;
            }
            if (entry.has("o")) {
                JsObject obj = new JsObject();
                made.put(id, obj);
                for (Map.Entry<String, JsonElement> x : entry.getAsJsonObject("o").entrySet()) obj.map.put(x.getKey(), value(x.getValue()));
                obj.frozen = entry.has("frozen") && entry.get("frozen").getAsBoolean();
                return obj;
            }
            if (entry.has("f")) {
                Node.Func decl = funcs.get(entry.get("f").getAsString());
                if (decl == null) throw new Unserializable("function body not found (code changed?)");
                String name = entry.has("name") ? entry.get("name").getAsString() : null;
                if (name != null && !name.equals(decl.name())) {
                    decl = new Node.Func(decl.line(), decl.col(), name, decl.params(), decl.body(), decl.arrow(), decl.exprBody());
                }
                // 범위가 이 함수를 다시 가리킬 수 있으므로(재귀 지역 함수) 범위를 먼저 만든다
                Scope closure = scope(entry.get("s"));
                Object done2 = made.get(id);
                if (done2 != null) return done2;
                ScriptFunction f = new ScriptFunction(decl, closure, entry.has("this") ? value(entry.get("this")) : null);
                made.put(id, f);
                return f;
            }
            if (entry.has("p")) return scope(new JsonPrimitive(id));
            throw new Unserializable("bad closure entry " + id);
        }

        private Scope scope(JsonElement ref) throws Unserializable {
            String id = ref.getAsString();
            if (id.equals("g")) return in.globals;
            if (id.equals("t")) {
                if (top == null) throw new Unserializable("no top-level scope to attach to");
                return top;
            }
            Object done = made.get(id);
            if (done instanceof Scope s) return s;
            JsonObject entry = table.getAsJsonObject(id);
            if (entry == null || !entry.has("p")) throw new Unserializable("missing scope " + id);
            Scope s = new Scope(scope(entry.get("p")));
            made.put(id, s);
            for (Map.Entry<String, JsonElement> x : entry.getAsJsonObject("v").entrySet()) {
                JsonArray pair = x.getValue().getAsJsonArray();
                s.vars.put(x.getKey(), new Binding(value(pair.get(0)), pair.get(1).getAsBoolean()));
            }
            return s;
        }
    }
}
