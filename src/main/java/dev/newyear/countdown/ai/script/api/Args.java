package dev.newyear.countdown.ai.script.api;

import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;

import java.util.List;

/** 호스트 함수 인자 해석 도우미. 잘못된 인자는 고치기 쉬운 메시지로 실패한다. */
public final class Args {
    private Args() {}

    public static Object get(List<Object> a, int i) {
        return i < a.size() ? a.get(i) : Values.UNDEFINED;
    }

    public static boolean present(List<Object> a, int i) {
        Object v = get(a, i);
        return v != Values.UNDEFINED && v != null;
    }

    public static double num(Interpreter in, List<Object> a, int i, String what) {
        Object v = get(a, i);
        double d = Interpreter.toNumber(v);
        if (Double.isNaN(d) || Double.isInfinite(d)) throw in.error(what + " must be a number (got " + Interpreter.typeName(v) + ")");
        return d;
    }

    public static double num(List<Object> a, int i, double def) {
        Object v = get(a, i);
        if (v == Values.UNDEFINED || v == null) return def;
        double d = Interpreter.toNumber(v);
        return Double.isNaN(d) || Double.isInfinite(d) ? def : d;
    }

    public static String str(Interpreter in, List<Object> a, int i, String what) {
        Object v = get(a, i);
        if (v == Values.UNDEFINED || v == null) throw in.error(what + " is required");
        return Interpreter.toStr(v);
    }

    public static String str(List<Object> a, int i, String def) {
        Object v = get(a, i);
        return v == Values.UNDEFINED || v == null ? def : Interpreter.toStr(v);
    }

    public static JsObject opts(List<Object> a, int i) {
        Object v = get(a, i);
        return v instanceof JsObject o ? o : new JsObject();
    }

    public static Values.Callable fn(Interpreter in, List<Object> a, int i, String what) {
        Object v = get(a, i);
        if (v instanceof Values.Callable c) return c;
        throw in.error(what + " must be a function, e.g. () => { ... }");
    }

    public static double optNum(JsObject o, String k, double def) {
        Object v = o.get(k);
        if (v == Values.UNDEFINED || v == null) return def;
        double d = Interpreter.toNumber(v);
        return Double.isNaN(d) ? def : d;
    }

    public static String optStr(JsObject o, String k, String def) {
        Object v = o.get(k);
        return v == Values.UNDEFINED || v == null ? def : Interpreter.toStr(v);
    }

    public static boolean optBool(JsObject o, String k, boolean def) {
        Object v = o.get(k);
        return v == Values.UNDEFINED || v == null ? def : Interpreter.truthy(v);
    }

    /** 좌표: {x,y,z}, [x,y,z], 또는 pos() 가 있는 핸들 (플레이어/엔티티). */
    public static double[] pos(Interpreter in, Object v, String what) {
        if (v instanceof JsObject o) {
            Object x = o.get("x"), y = o.get("y"), z = o.get("z");
            if (x != Values.UNDEFINED && y != Values.UNDEFINED && z != Values.UNDEFINED) {
                double[] p = {Interpreter.toNumber(x), Interpreter.toNumber(y), Interpreter.toNumber(z)};
                return check(in, p, what);
            }
        }
        if (v instanceof JsArray arr && arr.list.size() >= 3) {
            double[] p = {Interpreter.toNumber(arr.list.get(0)), Interpreter.toNumber(arr.list.get(1)), Interpreter.toNumber(arr.list.get(2))};
            return check(in, p, what);
        }
        if (v instanceof Positioned h) {
            double[] p = h.position();
            if (p != null) return p;
            throw in.error(what + ": that " + h.typeName() + " is not available right now (offline/unloaded)");
        }
        throw in.error(what + " must be a position {x, y, z}, an array [x, y, z], a player or an entity (got " + Interpreter.typeName(v) + ")");
    }

    private static double[] check(Interpreter in, double[] p, String what) {
        for (double d : p) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw in.error(what + " has an invalid coordinate");
            if (Math.abs(d) > 29_999_000) throw new ScriptError(ScriptError.Kind.PERMISSION, what + " is outside the world", 0, 0);
        }
        return p;
    }

    public static JsObject posObj(double x, double y, double z) {
        JsObject o = new JsObject();
        o.put("x", round(x)).put("y", round(y)).put("z", round(z));
        return o;
    }

    public static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** 위치를 가진 핸들. */
    public interface Positioned extends Values.HostObject {
        double[] position();
    }
}
