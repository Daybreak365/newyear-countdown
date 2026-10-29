package dev.newyear.countdown.ai.script.lang;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.newyear.countdown.ai.script.lang.Values.Callable;
import dev.newyear.countdown.ai.script.lang.Values.HostObject;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static dev.newyear.countdown.ai.script.lang.Interpreter.numToString;
import static dev.newyear.countdown.ai.script.lang.Interpreter.toNumber;
import static dev.newyear.countdown.ai.script.lang.Interpreter.toStr;
import static dev.newyear.countdown.ai.script.lang.Interpreter.truthy;
import static dev.newyear.countdown.ai.script.lang.Values.UNDEFINED;

/** 표준 라이브러리: Math, JSON, Object, Array, String/Number 메서드, console. */
public final class Stdlib {
    private Stdlib() {}

    public static final List<String> ARRAY_METHODS = Arrays.asList("push", "pop", "shift", "unshift", "slice", "splice", "indexOf",
            "lastIndexOf", "includes", "join", "map", "filter", "forEach", "find", "findIndex", "findLast", "some", "every",
            "reduce", "sort", "reverse", "concat", "flat", "fill", "at");
    public static final List<String> STRING_METHODS = Arrays.asList("includes", "startsWith", "endsWith", "indexOf", "lastIndexOf",
            "slice", "substring", "substr", "toUpperCase", "toLowerCase", "trim", "trimStart", "trimEnd", "split", "replace",
            "replaceAll", "repeat", "padStart", "padEnd", "charAt", "charCodeAt", "at", "concat", "toString");
    /** 정적 검사기가 아는 전역 이름. */
    public static final Set<String> GLOBALS = Set.of("Math", "JSON", "Object", "Array", "Number", "String", "Boolean",
            "parseInt", "parseFloat", "isNaN", "isFinite", "console", "Error", "TypeError", "RangeError", "NaN", "Infinity", "print");

    static final Callable HAS_OWN = new NativeFn("hasOwnProperty", (in, self, a) ->
            self instanceof JsObject o && o.map.containsKey(toStr(arg(a, 0))));

    private static final Map<String, Callable> ARRAY = new HashMap<>();
    private static final Map<String, Callable> STRING = new HashMap<>();
    private static final Map<String, Callable> NUMBER = new HashMap<>();

    public static Object arg(List<Object> a, int i) {
        return i < a.size() ? a.get(i) : UNDEFINED;
    }

    private static double num(List<Object> a, int i, double def) {
        Object v = arg(a, i);
        return v == UNDEFINED ? def : toNumber(v);
    }

    private static Callable fnArg(Interpreter in, List<Object> a, int i, String method) {
        Object v = arg(a, i);
        if (v instanceof Callable c) return c;
        throw in.error(method + " expects a function argument");
    }

    private static JsArray self(Interpreter in, Object self, String m) {
        if (self instanceof JsArray a) return a;
        throw in.error(m + " called on non-array");
    }

    private static int relIndex(double v, int len) {
        int i = (int) v;
        if (i < 0) i = Math.max(0, len + i);
        return Math.min(i, len);
    }

    private static void def(Map<String, Callable> m, String name, NativeFn.Impl impl) {
        m.put(name, new NativeFn(name, impl));
    }

    static Callable arrayMethod(String k) { return ARRAY.get(k); }
    static Callable stringMethod(String k) { return STRING.get(k); }
    static Callable numberMethod(String k) { return NUMBER.get(k); }

    static {
        // ---------------- Array
        def(ARRAY, "push", (in, s, a) -> {
            JsArray arr = self(in, s, "push");
            if (arr.list.size() + a.size() > Interpreter.MAX_ARRAY) throw new ScriptError(ScriptError.Kind.BUDGET, "Array too large (max " + Interpreter.MAX_ARRAY + ")", 0, 0);
            in.budget.allocate(a.size());
            arr.list.addAll(a);
            return (double) arr.list.size();
        });
        def(ARRAY, "pop", (in, s, a) -> { JsArray arr = self(in, s, "pop"); return arr.list.isEmpty() ? UNDEFINED : arr.list.remove(arr.list.size() - 1); });
        def(ARRAY, "shift", (in, s, a) -> { JsArray arr = self(in, s, "shift"); return arr.list.isEmpty() ? UNDEFINED : arr.list.remove(0); });
        def(ARRAY, "unshift", (in, s, a) -> {
            JsArray arr = self(in, s, "unshift");
            in.budget.allocate(a.size());
            arr.list.addAll(0, a);
            return (double) arr.list.size();
        });
        def(ARRAY, "slice", (in, s, a) -> {
            JsArray arr = self(in, s, "slice");
            int n = arr.list.size();
            int st = relIndex(num(a, 0, 0), n), en = relIndex(num(a, 1, n), n);
            List<Object> out = st < en ? new ArrayList<>(arr.list.subList(st, en)) : new ArrayList<>();
            in.budget.allocate(out.size() + 1);
            return new JsArray(out);
        });
        def(ARRAY, "splice", (in, s, a) -> {
            JsArray arr = self(in, s, "splice");
            int n = arr.list.size();
            int st = relIndex(num(a, 0, 0), n);
            int del = (int) Math.max(0, Math.min(n - st, num(a, 1, n - st)));
            List<Object> removed = new ArrayList<>(arr.list.subList(st, st + del));
            arr.list.subList(st, st + del).clear();
            if (a.size() > 2) arr.list.addAll(st, a.subList(2, a.size()));
            in.budget.allocate(removed.size() + a.size());
            return new JsArray(removed);
        });
        def(ARRAY, "indexOf", (in, s, a) -> {
            JsArray arr = self(in, s, "indexOf");
            for (int i = 0; i < arr.list.size(); i++) if (Interpreter.strictEquals(arr.list.get(i), arg(a, 0))) return (double) i;
            return -1.0;
        });
        def(ARRAY, "lastIndexOf", (in, s, a) -> {
            JsArray arr = self(in, s, "lastIndexOf");
            for (int i = arr.list.size() - 1; i >= 0; i--) if (Interpreter.strictEquals(arr.list.get(i), arg(a, 0))) return (double) i;
            return -1.0;
        });
        def(ARRAY, "includes", (in, s, a) -> {
            JsArray arr = self(in, s, "includes");
            for (Object o : arr.list) if (Interpreter.strictEquals(o, arg(a, 0))) return true;
            return false;
        });
        def(ARRAY, "join", (in, s, a) -> {
            JsArray arr = self(in, s, "join");
            String sep = arg(a, 0) == UNDEFINED ? "," : toStr(arg(a, 0));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.list.size(); i++) {
                if (i > 0) sb.append(sep);
                Object o = arr.list.get(i);
                if (o != null && o != UNDEFINED) sb.append(toStr(o));
                if (sb.length() > Interpreter.MAX_STRING) throw new ScriptError(ScriptError.Kind.BUDGET, "String too long", 0, 0);
            }
            in.budget.allocate(sb.length() / 16 + 1);
            return sb.toString();
        });
        def(ARRAY, "map", (in, s, a) -> {
            JsArray arr = self(in, s, "map");
            Callable f = fnArg(in, a, 0, "map");
            List<Object> out = new ArrayList<>(arr.list.size());
            for (int i = 0; i < arr.list.size(); i++) out.add(in.callFn(f, UNDEFINED, Arrays.asList(arr.list.get(i), (double) i, arr)));
            in.budget.allocate(out.size() + 1);
            return new JsArray(out);
        });
        def(ARRAY, "filter", (in, s, a) -> {
            JsArray arr = self(in, s, "filter");
            Callable f = fnArg(in, a, 0, "filter");
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < arr.list.size(); i++) {
                Object v = arr.list.get(i);
                if (truthy(in.callFn(f, UNDEFINED, Arrays.asList(v, (double) i, arr)))) out.add(v);
            }
            in.budget.allocate(out.size() + 1);
            return new JsArray(out);
        });
        def(ARRAY, "forEach", (in, s, a) -> {
            JsArray arr = self(in, s, "forEach");
            Callable f = fnArg(in, a, 0, "forEach");
            for (int i = 0; i < arr.list.size(); i++) in.callFn(f, UNDEFINED, Arrays.asList(arr.list.get(i), (double) i, arr));
            return UNDEFINED;
        });
        def(ARRAY, "find", (in, s, a) -> {
            JsArray arr = self(in, s, "find");
            Callable f = fnArg(in, a, 0, "find");
            for (int i = 0; i < arr.list.size(); i++) {
                Object v = arr.list.get(i);
                if (truthy(in.callFn(f, UNDEFINED, Arrays.asList(v, (double) i, arr)))) return v;
            }
            return UNDEFINED;
        });
        def(ARRAY, "findLast", (in, s, a) -> {
            JsArray arr = self(in, s, "findLast");
            Callable f = fnArg(in, a, 0, "findLast");
            for (int i = arr.list.size() - 1; i >= 0; i--) {
                Object v = arr.list.get(i);
                if (truthy(in.callFn(f, UNDEFINED, Arrays.asList(v, (double) i, arr)))) return v;
            }
            return UNDEFINED;
        });
        def(ARRAY, "findIndex", (in, s, a) -> {
            JsArray arr = self(in, s, "findIndex");
            Callable f = fnArg(in, a, 0, "findIndex");
            for (int i = 0; i < arr.list.size(); i++) if (truthy(in.callFn(f, UNDEFINED, Arrays.asList(arr.list.get(i), (double) i, arr)))) return (double) i;
            return -1.0;
        });
        def(ARRAY, "some", (in, s, a) -> {
            JsArray arr = self(in, s, "some");
            Callable f = fnArg(in, a, 0, "some");
            for (int i = 0; i < arr.list.size(); i++) if (truthy(in.callFn(f, UNDEFINED, Arrays.asList(arr.list.get(i), (double) i, arr)))) return true;
            return false;
        });
        def(ARRAY, "every", (in, s, a) -> {
            JsArray arr = self(in, s, "every");
            Callable f = fnArg(in, a, 0, "every");
            for (int i = 0; i < arr.list.size(); i++) if (!truthy(in.callFn(f, UNDEFINED, Arrays.asList(arr.list.get(i), (double) i, arr)))) return false;
            return true;
        });
        def(ARRAY, "reduce", (in, s, a) -> {
            JsArray arr = self(in, s, "reduce");
            Callable f = fnArg(in, a, 0, "reduce");
            int i = 0;
            Object acc;
            if (a.size() > 1) acc = a.get(1);
            else {
                if (arr.list.isEmpty()) throw in.error("reduce of empty array with no initial value");
                acc = arr.list.get(0);
                i = 1;
            }
            for (; i < arr.list.size(); i++) acc = in.callFn(f, UNDEFINED, Arrays.asList(acc, arr.list.get(i), (double) i, arr));
            return acc;
        });
        def(ARRAY, "sort", (in, s, a) -> {
            JsArray arr = self(in, s, "sort");
            Object cmp = arg(a, 0);
            List<Object> copy = new ArrayList<>(arr.list);
            try {
                if (cmp instanceof Callable f) {
                    copy.sort((x, y) -> (int) Math.signum(toNumber(in.callFn(f, UNDEFINED, Arrays.asList(x, y)))));
                } else {
                    copy.sort((x, y) -> toStr(x).compareTo(toStr(y)));
                }
            } catch (IllegalArgumentException e) {
                // 일관되지 않은 비교 함수: 부분 정렬 결과를 그대로 쓴다
            }
            arr.list.clear();
            arr.list.addAll(copy);
            return arr;
        });
        def(ARRAY, "reverse", (in, s, a) -> { JsArray arr = self(in, s, "reverse"); Collections.reverse(arr.list); return arr; });
        def(ARRAY, "concat", (in, s, a) -> {
            JsArray arr = self(in, s, "concat");
            List<Object> out = new ArrayList<>(arr.list);
            for (Object o : a) {
                if (o instanceof JsArray other) out.addAll(other.list);
                else out.add(o);
            }
            if (out.size() > Interpreter.MAX_ARRAY) throw new ScriptError(ScriptError.Kind.BUDGET, "Array too large", 0, 0);
            in.budget.allocate(out.size() + 1);
            return new JsArray(out);
        });
        def(ARRAY, "flat", (in, s, a) -> {
            JsArray arr = self(in, s, "flat");
            List<Object> out = new ArrayList<>();
            for (Object o : arr.list) {
                if (o instanceof JsArray other) out.addAll(other.list);
                else out.add(o);
            }
            in.budget.allocate(out.size() + 1);
            return new JsArray(out);
        });
        def(ARRAY, "fill", (in, s, a) -> {
            JsArray arr = self(in, s, "fill");
            for (int i = 0; i < arr.list.size(); i++) arr.list.set(i, arg(a, 0));
            return arr;
        });
        def(ARRAY, "at", (in, s, a) -> {
            JsArray arr = self(in, s, "at");
            int i = (int) num(a, 0, 0);
            if (i < 0) i += arr.list.size();
            return i >= 0 && i < arr.list.size() ? arr.list.get(i) : UNDEFINED;
        });

        // ---------------- String
        def(STRING, "includes", (in, s, a) -> ((String) s).contains(toStr(arg(a, 0))));
        def(STRING, "startsWith", (in, s, a) -> ((String) s).startsWith(toStr(arg(a, 0))));
        def(STRING, "endsWith", (in, s, a) -> ((String) s).endsWith(toStr(arg(a, 0))));
        def(STRING, "indexOf", (in, s, a) -> (double) ((String) s).indexOf(toStr(arg(a, 0)), (int) num(a, 1, 0)));
        def(STRING, "lastIndexOf", (in, s, a) -> (double) ((String) s).lastIndexOf(toStr(arg(a, 0))));
        def(STRING, "slice", (in, s, a) -> {
            String str = (String) s;
            int n = str.length();
            int st = relIndex(num(a, 0, 0), n), en = relIndex(num(a, 1, n), n);
            return st < en ? str.substring(st, en) : "";
        });
        def(STRING, "substring", (in, s, a) -> {
            String str = (String) s;
            int n = str.length();
            int st = (int) Math.max(0, Math.min(n, num(a, 0, 0))), en = (int) Math.max(0, Math.min(n, num(a, 1, n)));
            return str.substring(Math.min(st, en), Math.max(st, en));
        });
        def(STRING, "substr", (in, s, a) -> {
            String str = (String) s;
            int n = str.length();
            int st = relIndex(num(a, 0, 0), n);
            int len = (int) Math.max(0, Math.min(n - st, num(a, 1, n - st)));
            return str.substring(st, st + len);
        });
        def(STRING, "toUpperCase", (in, s, a) -> ((String) s).toUpperCase());
        def(STRING, "toLowerCase", (in, s, a) -> ((String) s).toLowerCase());
        def(STRING, "trim", (in, s, a) -> ((String) s).strip());
        def(STRING, "trimStart", (in, s, a) -> ((String) s).stripLeading());
        def(STRING, "trimEnd", (in, s, a) -> ((String) s).stripTrailing());
        def(STRING, "toString", (in, s, a) -> s);
        def(STRING, "concat", (in, s, a) -> {
            StringBuilder sb = new StringBuilder((String) s);
            for (Object o : a) sb.append(toStr(o));
            if (sb.length() > Interpreter.MAX_STRING) throw new ScriptError(ScriptError.Kind.BUDGET, "String too long", 0, 0);
            return sb.toString();
        });
        def(STRING, "split", (in, s, a) -> {
            String str = (String) s;
            Object sepV = arg(a, 0);
            List<Object> out = new ArrayList<>();
            if (sepV == UNDEFINED) out.add(str);
            else {
                String sep = toStr(sepV);
                if (sep.isEmpty()) str.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
                else {
                    int from = 0, idx;
                    while ((idx = str.indexOf(sep, from)) >= 0) {
                        out.add(str.substring(from, idx));
                        from = idx + sep.length();
                    }
                    out.add(str.substring(from));
                }
            }
            int limit = (int) num(a, 1, Interpreter.MAX_ARRAY);
            while (out.size() > limit) out.remove(out.size() - 1);
            in.budget.allocate(out.size() + 1);
            return new JsArray(out);
        });
        def(STRING, "replace", (in, s, a) -> {
            String str = (String) s, find = toStr(arg(a, 0));
            int i = str.indexOf(find);
            if (i < 0) return str;
            String rep = arg(a, 1) instanceof Callable f ? toStr(in.callFn(f, UNDEFINED, Arrays.asList(find))) : toStr(arg(a, 1));
            return str.substring(0, i) + rep + str.substring(i + find.length());
        });
        def(STRING, "replaceAll", (in, s, a) -> {
            String str = (String) s, find = toStr(arg(a, 0));
            if (find.isEmpty()) return str;
            String r = str.replace(find, toStr(arg(a, 1)));
            if (r.length() > Interpreter.MAX_STRING) throw new ScriptError(ScriptError.Kind.BUDGET, "String too long", 0, 0);
            return r;
        });
        def(STRING, "repeat", (in, s, a) -> {
            int n = (int) num(a, 0, 0);
            String str = (String) s;
            if (n < 0 || (long) n * str.length() > Interpreter.MAX_STRING) throw new ScriptError(ScriptError.Kind.BUDGET, "String too long", 0, 0);
            in.budget.allocate((long) n * str.length() / 16 + 1);
            return str.repeat(n);
        });
        def(STRING, "padStart", (in, s, a) -> pad((String) s, (int) Math.min(1000, num(a, 0, 0)), arg(a, 1) == UNDEFINED ? " " : toStr(arg(a, 1)), true));
        def(STRING, "padEnd", (in, s, a) -> pad((String) s, (int) Math.min(1000, num(a, 0, 0)), arg(a, 1) == UNDEFINED ? " " : toStr(arg(a, 1)), false));
        def(STRING, "charAt", (in, s, a) -> {
            String str = (String) s;
            int i = (int) num(a, 0, 0);
            return i >= 0 && i < str.length() ? String.valueOf(str.charAt(i)) : "";
        });
        def(STRING, "charCodeAt", (in, s, a) -> {
            String str = (String) s;
            int i = (int) num(a, 0, 0);
            return i >= 0 && i < str.length() ? (double) str.charAt(i) : Double.NaN;
        });
        def(STRING, "at", (in, s, a) -> {
            String str = (String) s;
            int i = (int) num(a, 0, 0);
            if (i < 0) i += str.length();
            return i >= 0 && i < str.length() ? String.valueOf(str.charAt(i)) : UNDEFINED;
        });

        // ---------------- Number
        def(NUMBER, "toFixed", (in, s, a) -> {
            int digits = (int) Math.max(0, Math.min(20, num(a, 0, 0)));
            double d = (Double) s;
            if (Double.isNaN(d) || Double.isInfinite(d)) return numToString(d);
            return new java.math.BigDecimal(d).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString();
        });
        def(NUMBER, "toString", (in, s, a) -> {
            Object radix = arg(a, 0);
            if (radix == UNDEFINED) return numToString((Double) s);
            int r = (int) toNumber(radix);
            if (r < 2 || r > 36) throw in.error("toString radix must be 2..36");
            return Long.toString((long) (double) (Double) s, r);
        });
    }

    private static String pad(String s, int len, String fill, boolean start) {
        if (s.length() >= len || fill.isEmpty()) return s;
        StringBuilder p = new StringBuilder();
        while (p.length() < len - s.length()) p.append(fill);
        p.setLength(len - s.length());
        return start ? p + s : s + p;
    }

    // ---------------------------------------------------------------- 전역 설치

    static JsObject frozen(JsObject o) {
        o.frozen = true;
        return o;
    }

    static void install(Interpreter in) {
        JsObject math = new JsObject();
        math.put("PI", Math.PI).put("E", Math.E);
        mathFn(math, "floor", Math::floor);
        mathFn(math, "ceil", Math::ceil);
        mathFn(math, "round", d -> Math.floor(d + 0.5));
        mathFn(math, "abs", Math::abs);
        mathFn(math, "sqrt", Math::sqrt);
        mathFn(math, "cbrt", Math::cbrt);
        mathFn(math, "sin", Math::sin);
        mathFn(math, "cos", Math::cos);
        mathFn(math, "tan", Math::tan);
        mathFn(math, "asin", Math::asin);
        mathFn(math, "acos", Math::acos);
        mathFn(math, "atan", Math::atan);
        mathFn(math, "log", Math::log);
        mathFn(math, "log10", Math::log10);
        mathFn(math, "log2", d -> Math.log(d) / Math.log(2));
        mathFn(math, "exp", Math::exp);
        mathFn(math, "sign", Math::signum);
        mathFn(math, "trunc", d -> d < 0 ? Math.ceil(d) : Math.floor(d));
        math.put("atan2", new NativeFn("atan2", (i, s, a) -> Math.atan2(num(a, 0, 0), num(a, 1, 0))));
        math.put("pow", new NativeFn("pow", (i, s, a) -> Math.pow(num(a, 0, Double.NaN), num(a, 1, Double.NaN))));
        math.put("hypot", new NativeFn("hypot", (i, s, a) -> {
            double t = 0;
            for (Object o : a) { double d = toNumber(o); t += d * d; }
            return Math.sqrt(t);
        }));
        math.put("random", new NativeFn("random", (i, s, a) -> ThreadLocalRandom.current().nextDouble()));
        math.put("min", new NativeFn("min", (i, s, a) -> {
            double m = Double.POSITIVE_INFINITY;
            for (Object o : flatten(a)) m = Math.min(m, toNumber(o));
            return m;
        }));
        math.put("max", new NativeFn("max", (i, s, a) -> {
            double m = Double.NEGATIVE_INFINITY;
            for (Object o : flatten(a)) m = Math.max(m, toNumber(o));
            return m;
        }));
        math.put("clamp", new NativeFn("clamp", (i, s, a) -> Math.max(num(a, 1, 0), Math.min(num(a, 2, 0), num(a, 0, 0)))));
        in.define("Math", frozen(math));

        JsObject json = new JsObject();
        json.put("stringify", new NativeFn("stringify", (i, s, a) -> {
            Object v = arg(a, 0);
            if (v == UNDEFINED) return UNDEFINED;
            String out = toJson(v).toString();
            if (out.length() > Interpreter.MAX_STRING) throw new ScriptError(ScriptError.Kind.BUDGET, "JSON too long", 0, 0);
            return out;
        }));
        json.put("parse", new NativeFn("parse", (i, s, a) -> {
            String src = toStr(arg(a, 0));
            if (src.length() > Interpreter.MAX_STRING) throw new ScriptError(ScriptError.Kind.BUDGET, "JSON too long", 0, 0);
            try {
                return fromJson(JsonParser.parseString(src), i);
            } catch (RuntimeException e) {
                if (e instanceof ScriptError se) throw se;
                throw i.error("Invalid JSON: " + e.getMessage());
            }
        }));
        in.define("JSON", frozen(json));

        JsObject object = new JsObject();
        object.put("keys", new NativeFn("keys", (i, s, a) -> {
            Object o = arg(a, 0);
            List<Object> out = new ArrayList<>();
            if (o instanceof JsObject jo) out.addAll(jo.map.keySet());
            else if (o instanceof JsArray ja) for (int k = 0; k < ja.list.size(); k++) out.add(numToString(k));
            else if (o instanceof HostObject h) out.addAll(h.memberNames());
            i.budget.allocate(out.size() + 1);
            return new JsArray(out);
        }));
        object.put("values", new NativeFn("values", (i, s, a) -> {
            Object o = arg(a, 0);
            List<Object> out = new ArrayList<>();
            if (o instanceof JsObject jo) out.addAll(jo.map.values());
            else if (o instanceof JsArray ja) out.addAll(ja.list);
            i.budget.allocate(out.size() + 1);
            return new JsArray(out);
        }));
        object.put("entries", new NativeFn("entries", (i, s, a) -> {
            Object o = arg(a, 0);
            List<Object> out = new ArrayList<>();
            if (o instanceof JsObject jo) for (Map.Entry<String, Object> e : jo.map.entrySet()) out.add(new JsArray(new ArrayList<>(Arrays.asList(e.getKey(), e.getValue() == null ? NULL_MARK : e.getValue()))));
            else if (o instanceof JsArray ja) for (int k = 0; k < ja.list.size(); k++) out.add(new JsArray(new ArrayList<>(Arrays.asList(numToString(k), ja.list.get(k) == null ? NULL_MARK : ja.list.get(k)))));
            for (Object e : out) {
                List<Object> l = ((JsArray) e).list;
                if (l.get(1) == NULL_MARK) l.set(1, null);
            }
            i.budget.allocate(out.size() * 3L + 1);
            return new JsArray(out);
        }));
        object.put("assign", new NativeFn("assign", (i, s, a) -> {
            Object t = arg(a, 0);
            if (!(t instanceof JsObject target)) throw i.error("Object.assign target must be an object");
            if (target.frozen) throw i.error("Cannot modify read-only object");
            for (int k = 1; k < a.size(); k++) if (a.get(k) instanceof JsObject src) target.map.putAll(src.map);
            i.budget.allocate(target.map.size());
            return target;
        }));
        object.put("fromEntries", new NativeFn("fromEntries", (i, s, a) -> {
            JsObject out = new JsObject();
            if (arg(a, 0) instanceof JsArray arr) for (Object e : arr.list) if (e instanceof JsArray pair && !pair.list.isEmpty()) {
                out.map.put(i.propertyKey(pair.list.get(0)), pair.list.size() > 1 ? pair.list.get(1) : UNDEFINED);
            }
            i.budget.allocate(out.map.size() + 1);
            return out;
        }));
        object.put("freeze", new NativeFn("freeze", (i, s, a) -> {
            if (arg(a, 0) instanceof JsObject o) o.frozen = true;
            return arg(a, 0);
        }));
        in.define("Object", frozen(object));

        JsObject array = new JsObject();
        array.put("isArray", new NativeFn("isArray", (i, s, a) -> arg(a, 0) instanceof JsArray));
        array.put("from", new NativeFn("from", (i, s, a) -> {
            Object src = arg(a, 0);
            List<Object> out = new ArrayList<>();
            if (src instanceof JsArray ja) out.addAll(ja.list);
            else if (src instanceof String str) str.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
            else if (src instanceof JsObject jo && jo.map.get("length") instanceof Double len) {
                int n = (int) Math.min(Interpreter.MAX_ARRAY, len);
                for (int k = 0; k < n; k++) out.add(UNDEFINED);
            }
            if (arg(a, 1) instanceof Callable f) for (int k = 0; k < out.size(); k++) out.set(k, i.callFn(f, UNDEFINED, Arrays.asList(out.get(k), (double) k)));
            i.budget.allocate(out.size() + 1);
            return new JsArray(out);
        }));
        array.put("of", new NativeFn("of", (i, s, a) -> new JsArray(new ArrayList<>(a))));
        in.define("Array", frozen(array));

        JsObject number = new JsObject();
        number.put("isInteger", new NativeFn("isInteger", (i, s, a) -> arg(a, 0) instanceof Double d && d == Math.rint(d) && !d.isInfinite()));
        number.put("isFinite", new NativeFn("isFinite", (i, s, a) -> arg(a, 0) instanceof Double d && !d.isInfinite() && !d.isNaN()));
        number.put("parseInt", new NativeFn("parseInt", (i, s, a) -> parseInt(a)));
        number.put("parseFloat", new NativeFn("parseFloat", (i, s, a) -> parseFloat(toStr(arg(a, 0)))));
        number.put("MAX_SAFE_INTEGER", 9007199254740991.0);
        number.put("MIN_SAFE_INTEGER", -9007199254740991.0);
        number.put("EPSILON", Math.ulp(1.0));
        frozen(number);
        // Number(x) 도 되고 Number.isInteger 도 되도록 호스트 객체로 만든다
        in.define("Number", callableObject("Number", number, (i, s, a) -> a.isEmpty() ? 0.0 : toNumber(a.get(0))));
        in.define("String", callableObject("String", frozen(new JsObject().put("fromCharCode", new NativeFn("fromCharCode", (i, s, a) -> {
            StringBuilder sb = new StringBuilder();
            for (Object o : a) sb.append((char) Interpreter.toInt32(o));
            return sb.toString();
        }))), (i, s, a) -> a.isEmpty() ? "" : toStr(a.get(0))));
        in.define("Boolean", new NativeFn("Boolean", (i, s, a) -> truthy(arg(a, 0))));
        in.define("parseInt", new NativeFn("parseInt", (i, s, a) -> parseInt(a)));
        in.define("parseFloat", new NativeFn("parseFloat", (i, s, a) -> parseFloat(toStr(arg(a, 0)))));
        in.define("isNaN", new NativeFn("isNaN", (i, s, a) -> Double.isNaN(toNumber(arg(a, 0)))));
        in.define("isFinite", new NativeFn("isFinite", (i, s, a) -> { double d = toNumber(arg(a, 0)); return !Double.isNaN(d) && !Double.isInfinite(d); }));
        in.define("NaN", Double.NaN);
        in.define("Infinity", Double.POSITIVE_INFINITY);

        NativeFn.Impl errorImpl = (i, s, a) -> {
            JsObject e = new JsObject();
            e.put("name", "Error");
            e.put("message", a.isEmpty() ? "" : toStr(a.get(0)));
            return e;
        };
        in.define("Error", new NativeFn("Error", errorImpl));
        in.define("TypeError", new NativeFn("TypeError", errorImpl));
        in.define("RangeError", new NativeFn("RangeError", errorImpl));

        NativeFn log = new NativeFn("log", (i, s, a) -> {
            StringBuilder sb = new StringBuilder();
            for (Object o : a) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(o instanceof String str ? str : debugString(o, 0));
            }
            i.print(sb.toString());
            return UNDEFINED;
        });
        JsObject console = new JsObject().put("log", log).put("info", log).put("warn", log).put("error", log).put("debug", log);
        in.define("console", frozen(console));
        in.define("print", log);
    }

    private static final Object NULL_MARK = new Object();

    private interface D2D {
        double f(double d);
    }

    private static void mathFn(JsObject m, String name, D2D f) {
        m.put(name, new NativeFn(name, (i, s, a) -> f.f(num(a, 0, Double.NaN))));
    }

    private static List<Object> flatten(List<Object> a) {
        if (a.size() == 1 && a.get(0) instanceof JsArray arr) return arr.list;
        return a;
    }

    private static Object parseInt(List<Object> a) {
        String s = toStr(arg(a, 0)).trim();
        int radix = arg(a, 1) == UNDEFINED ? 10 : (int) toNumber(arg(a, 1));
        if (radix == 16 || (radix == 10 && (s.startsWith("0x") || s.startsWith("0X")) && arg(a, 1) == UNDEFINED)) {
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
            radix = 16;
        }
        int end = 0;
        if (end < s.length() && (s.charAt(end) == '-' || s.charAt(end) == '+')) end++;
        int digitsStart = end;
        while (end < s.length() && Character.digit(s.charAt(end), radix) >= 0) end++;
        if (end == digitsStart) return Double.NaN;
        try {
            return (double) Long.parseLong(s.substring(0, end), radix);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double parseFloat(String s) {
        s = s.trim();
        int end = 0;
        boolean dot = false, exp = false;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (Character.isDigit(c) || (end == 0 && (c == '-' || c == '+'))) end++;
            else if (c == '.' && !dot && !exp) { dot = true; end++; }
            else if ((c == 'e' || c == 'E') && !exp && end > 0) { exp = true; end++; if (end < s.length() && (s.charAt(end) == '-' || s.charAt(end) == '+')) end++; }
            else break;
        }
        try {
            return Double.parseDouble(s.substring(0, end));
        } catch (NumberFormatException e) {
            return s.startsWith("Infinity") ? Double.POSITIVE_INFINITY : Double.NaN;
        }
    }

    /** 함수처럼 부를 수 있고 속성도 가진 전역 (Number, String). */
    private static HostObject callableObject(String name, JsObject props, NativeFn.Impl call) {
        return new CallableHost(name, props, call);
    }

    static final class CallableHost implements HostObject, Callable {
        private final String name;
        private final JsObject props;
        private final NativeFn.Impl impl;

        CallableHost(String name, JsObject props, NativeFn.Impl impl) {
            this.name = name;
            this.props = props;
            this.impl = impl;
        }

        @Override
        public Object getMember(Interpreter in, String n) {
            return props.get(n);
        }

        @Override
        public java.util.Collection<String> memberNames() {
            return props.map.keySet();
        }

        @Override
        public String typeName() {
            return name;
        }

        @Override
        public Object call(Interpreter in, Object self, List<Object> args) {
            return impl.call(in, self, args);
        }

        @Override
        public String name() {
            return name;
        }
    }

    // ---------------------------------------------------------------- JSON 변환 (상태 저장에도 쓴다)

    public static JsonElement toJson(Object v) {
        return toJson(v, new IdentityHashMap<>(), 0);
    }

    private static JsonElement toJson(Object v, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (depth > 32) throw new ScriptError(ScriptError.Kind.RUNTIME, "Value is nested too deeply to convert to JSON", 0, 0);
        if (v == null || v == UNDEFINED) return JsonNull.INSTANCE;
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) return JsonNull.INSTANCE;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return new JsonPrimitive((long) (double) d);
            return new JsonPrimitive(d);
        }
        if (v instanceof String s) return new JsonPrimitive(s);
        if (v instanceof JsArray a) {
            if (seen.put(a, true) != null) throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot convert circular structure to JSON", 0, 0);
            JsonArray arr = new JsonArray();
            for (Object o : a.list) arr.add(toJson(o, seen, depth + 1));
            seen.remove(a);
            return arr;
        }
        if (v instanceof JsObject o) {
            if (seen.put(o, true) != null) throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot convert circular structure to JSON", 0, 0);
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Object> e : o.map.entrySet()) {
                if (e.getValue() == UNDEFINED || e.getValue() instanceof Callable) continue;
                obj.add(e.getKey(), toJson(e.getValue(), seen, depth + 1));
            }
            seen.remove(o);
            return obj;
        }
        if (v instanceof HostObject h) {
            // 게임 객체 핸들은 참조만 저장한다 (예: {"$ref":"player:<uuid>"})
            Object ref = h.getMember(null, "$ref");
            if (ref instanceof String s) {
                JsonObject obj = new JsonObject();
                obj.addProperty("$ref", s);
                return obj;
            }
            return new JsonPrimitive("[" + h.typeName() + "]");
        }
        return JsonNull.INSTANCE;
    }

    public interface RefResolver {
        Object resolve(String ref);
    }

    public static Object fromJson(JsonElement e, Interpreter in) {
        return fromJson(e, in, null, 0);
    }

    public static Object fromJson(JsonElement e, Interpreter in, RefResolver refs, int depth) {
        if (depth > 40) throw new ScriptError(ScriptError.Kind.RUNTIME, "JSON nested too deeply", 0, 0);
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        if (in != null) in.budget.allocate(1);
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement c : e.getAsJsonArray()) list.add(fromJson(c, in, refs, depth + 1));
            return new JsArray(list);
        }
        JsonObject o = e.getAsJsonObject();
        if (refs != null && o.size() == 1 && o.has("$ref") && o.get("$ref").isJsonPrimitive()) {
            Object r = refs.resolve(o.get("$ref").getAsString());
            if (r != null) return r;
        }
        JsObject out = new JsObject();
        for (Map.Entry<String, JsonElement> en : o.entrySet()) out.map.put(en.getKey(), fromJson(en.getValue(), in, refs, depth + 1));
        return out;
    }

    /** console.log 용 표시 문자열. */
    public static String debugString(Object v, int depth) {
        if (v instanceof String s) return depth == 0 ? s : "\"" + s + "\"";
        if (v instanceof JsArray a) {
            if (depth > 2) return "[...]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.list.size() && i < 20; i++) {
                if (i > 0) sb.append(", ");
                sb.append(debugString(a.list.get(i), depth + 1));
            }
            if (a.list.size() > 20) sb.append(", …");
            return sb.append(']').toString();
        }
        if (v instanceof JsObject o) {
            if (depth > 2) return "{...}";
            StringBuilder sb = new StringBuilder("{");
            int n = 0;
            for (Map.Entry<String, Object> e : o.map.entrySet()) {
                if (n++ > 0) sb.append(", ");
                if (n > 20) { sb.append("…"); break; }
                sb.append(e.getKey()).append(": ").append(debugString(e.getValue(), depth + 1));
            }
            return sb.append('}').toString();
        }
        return toStr(v);
    }
}
