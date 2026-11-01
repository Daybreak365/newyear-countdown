package dev.newyear.countdown.ai.script.api;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.Models;
import dev.newyear.countdown.ai.script.Policy;
import dev.newyear.countdown.ai.script.ScriptInstance;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static dev.newyear.countdown.ai.script.lang.Values.UNDEFINED;

/**
 * entities.model(...) 이 돌려주는 조립 모델 핸들. 스크립트 기록의 ModelSpec 을 이름(id)으로만 붙잡는다 —
 * state 에 넣어 두면 재시작 뒤에도 같은 모델로 되살아난다 ($ref "model:&lt;script&gt;:&lt;id&gt;").
 */
public final class ModelRef implements Args.Positioned {
    public final String scriptId;
    public final String id;
    /** 모의 실행에서 만든 모델 (아무것도 바꾸지 않는다). */
    private final ScriptRecord.ModelSpec dry;

    public ModelRef(String scriptId, String id, ScriptRecord.ModelSpec dry) {
        this.scriptId = scriptId;
        this.id = id;
        this.dry = dry;
    }

    ScriptRecord.ModelSpec spec() {
        if (dry != null) return dry;
        WishEngine e = WishEngine.get();
        ScriptRecord r = e == null ? null : e.state().scripts.get(scriptId);
        if (r == null) return null;
        for (ScriptRecord.ModelSpec m : r.models) if (m.id.equals(id)) return m;
        return null;
    }

    @Override
    public double[] position() {
        ScriptRecord.ModelSpec m = spec();
        return m == null ? null : m.origin.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModelRef r && r.scriptId.equals(scriptId) && r.id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scriptId, id);
    }

    @Override
    public String typeName() {
        return "Model";
    }

    @Override
    public Object getMember(Interpreter in, String member) {
        switch (member) {
            case "id": return id;
            case "$ref": return dry != null ? null : "model:" + scriptId + ":" + id;
            case "parts": {
                ScriptRecord.ModelSpec m = spec();
                return m == null ? 0.0 : (double) m.parts.size();
            }
            default: break;
        }
        Values.Callable c = METHODS.get(member);
        return c == null ? UNDEFINED : c;
    }

    @Override
    public Collection<String> memberNames() {
        List<String> all = new ArrayList<>(List.of("id", "parts"));
        all.addAll(METHODS.keySet());
        return all;
    }

    private static final Map<String, Values.Callable> METHODS = new LinkedHashMap<>();
    public static final Map<String, String> DOCS = new LinkedHashMap<>();

    private static void def(String name, String doc, NativeFn.Impl impl) {
        METHODS.put(name, new NativeFn("model." + name, impl));
        DOCS.put(name, doc);
    }

    private static ModelRef self(Interpreter in, Object s) {
        if (s instanceof ModelRef r) return r;
        throw in.error("model method called without a model");
    }

    private static ScriptRecord.ModelSpec need(Interpreter in, Object s) {
        ModelRef r = self(in, s);
        ScriptInstance inst = ScriptInstance.of(in);
        if (!r.scriptId.equals(inst.rec.id)) throw in.error("That model belongs to another script");
        ScriptRecord.ModelSpec m = r.spec();
        if (m == null) throw in.error("That model no longer exists (check model.exists())");
        return m;
    }

    private static int ticks(List<Object> a, int i, int def) {
        return (int) Math.max(0, Math.min(200, Args.num(a, i, def)));
    }

    static {
        def("exists", "() → true while at least one part is in the world", (in, s, a) -> {
            ModelRef r = self(in, s);
            if (r.dry != null) return true;
            ScriptRecord.ModelSpec m = r.spec();
            return m != null && Models.alive(m);
        });
        def("pos", "() → {x,y,z} model origin", (in, s, a) -> {
            double[] p = need(in, s).origin;
            return Args.posObj(p[0], p[1], p[2]);
        });
        def("move", "(pos, ticks=0) move the whole model (glides over 'ticks', max 59)", (in, s, a) -> {
            ScriptRecord.ModelSpec m = need(in, s);
            double[] p = Args.pos(in, Args.get(a, 0), "model position");
            ScriptInstance inst = ScriptInstance.of(in);
            Policy.checkRange(in, inst, p);
            if (inst.dry) { inst.effect("model move"); m.origin = p; return true; }
            Models.moveTo(m, p, ticks(a, 1, 0));
            return true;
        });
        def("rotate", "(yawDegrees, ticks=10) turn the model around its origin (smoothly)", (in, s, a) -> {
            ScriptRecord.ModelSpec m = need(in, s);
            m.yaw = (float) Args.num(in, a, 0, "yaw");
            if (ScriptInstance.of(in).dry) return true;
            Models.refresh(m, ticks(a, 1, 10));
            return true;
        });
        def("scale", "(factor 0.05-8, ticks=10) grow/shrink the whole model", (in, s, a) -> {
            ScriptRecord.ModelSpec m = need(in, s);
            m.scale = (float) Math.max(0.05, Math.min(8, Args.num(in, a, 0, "scale")));
            if (ScriptInstance.of(in).dry) return true;
            Models.refresh(m, ticks(a, 1, 10));
            return true;
        });
        def("part", "(index, {x,y,z, rx,ry,rz, sx,sy,sz, scale}, ticks=10) animate one part (keyframe; client interpolates)", (in, s, a) -> {
            ScriptRecord.ModelSpec m = need(in, s);
            int i = (int) Args.num(in, a, 0, "part index");
            if (i < 0 || i >= m.parts.size()) throw in.error("part index out of range (0.." + (m.parts.size() - 1) + ")");
            JsObject o = Args.opts(a, 1);
            ScriptRecord.ModelSpec.Part p = m.parts.get(i);
            p.x = (float) Math.max(-16, Math.min(16, Args.optNum(o, "x", p.x)));
            p.y = (float) Math.max(-16, Math.min(16, Args.optNum(o, "y", p.y)));
            p.z = (float) Math.max(-16, Math.min(16, Args.optNum(o, "z", p.z)));
            p.rx = (float) Args.optNum(o, "rx", p.rx);
            p.ry = (float) Args.optNum(o, "ry", p.ry);
            p.rz = (float) Args.optNum(o, "rz", p.rz);
            if (o.map.containsKey("scale")) p.sx = p.sy = p.sz = (float) Math.max(0.01, Math.min(16, Args.optNum(o, "scale", 1)));
            p.sx = (float) Math.max(0.01, Math.min(16, Args.optNum(o, "sx", p.sx)));
            p.sy = (float) Math.max(0.01, Math.min(16, Args.optNum(o, "sy", p.sy)));
            p.sz = (float) Math.max(0.01, Math.min(16, Args.optNum(o, "sz", p.sz)));
            if (ScriptInstance.of(in).dry) return true;
            Models.refreshPart(m, p, ticks(a, 2, 10));
            return true;
        });
        def("follow", "(player|entity, offset?{x,y,z}, opts?{turn:true}) the model rides along with a target (persists)", (in, s, a) -> {
            ScriptRecord.ModelSpec m = need(in, s);
            Object t = Args.get(a, 0);
            if (t instanceof PlayerRef pr) m.follow = "player:" + pr.id;
            else if (t instanceof EntityRef er) m.follow = "entity:" + er.id;
            else throw in.error("follow needs a player or entity");
            double[] off = Args.present(a, 1) ? Args.pos(in, a.get(1), "offset") : new double[]{0, 0, 0};
            m.followOffset = off;
            m.followTurn = Args.optBool(Args.opts(a, 2), "turn", true);
            return true;
        });
        def("stopFollow", "() stay where it is", (in, s, a) -> {
            need(in, s).follow = null;
            return true;
        });
        def("remove", "() remove the whole model", (in, s, a) -> {
            ScriptRecord.ModelSpec m = need(in, s);
            ScriptInstance inst = ScriptInstance.of(in);
            if (inst.dry) { inst.effect("model remove"); return true; }
            Models.remove(inst, m);
            return true;
        });
    }
}
