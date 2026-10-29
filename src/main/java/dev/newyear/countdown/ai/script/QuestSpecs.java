package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.PlayerRef;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.wish.Quest;

import java.util.UUID;

import static dev.newyear.countdown.ai.script.ScriptApi.MemoryText.limit;

/** 퀘스트 정의(스크립트 객체) → Quest. */
public final class QuestSpecs {
    private QuestSpecs() {}

    public static Quest fromScript(Interpreter in, JsObject spec, ScriptInstance inst) {
        Quest q = new Quest();
        q.id = "q-" + UUID.randomUUID().toString().substring(0, 8);
        q.wishId = inst.rec.wishId;
        q.owner = inst.rec.owner;
        q.ownerName = inst.rec.ownerName;
        q.scriptId = inst.rec.id;
        q.title = limit(str(spec, "title", "소원의 과제"), 48);
        q.description = limit(str(spec, "description", ""), 400);
        q.conditions = limit(str(spec, "conditions", ""), 300);
        q.reward = limit(str(spec, "reward", ""), 160);
        q.failure = limit(str(spec, "failure", ""), 160);
        q.priority = (int) Math.max(0, Math.min(10, num(spec, "priority", 5)));
        if (spec.get("participants") instanceof JsArray ps) {
            for (Object o : ps.list) if (o instanceof PlayerRef pr && !pr.id.toString().equals(q.owner)) q.participants.add(pr.id.toString());
        }
        if (spec.get("owner") instanceof PlayerRef pr) {
            q.owner = pr.id.toString();
            q.ownerName = pr.name;
        }
        if (!(spec.get("objectives") instanceof JsArray objs) || objs.list.isEmpty()) throw in.error("quest needs objectives: [{id, text, target}]");
        int n = 0;
        for (Object o : objs.list) {
            if (!(o instanceof JsObject jo)) continue;
            if (++n > 8) break;
            Quest.Objective ob = new Quest.Objective();
            ob.id = limit(str(jo, "id", "o" + n), 32);
            ob.text = limit(str(jo, "text", ob.id), 120);
            ob.target = Math.max(1, Math.min(100000, num(jo, "target", 1)));
            ob.optional = Interpreter.truthy(jo.get("optional"));
            ob.hidden = Interpreter.truthy(jo.get("hidden"));
            q.objectives.add(ob);
        }
        double limitSec = num(spec, "timeLimit", 0);
        long now = WishEngine.get() == null ? 0 : WishEngine.get().tick();
        q.startTick = now;
        if (limitSec > 0) q.deadlineTick = now + (long) (Math.min(86400 * 7, limitSec) * 20);
        q.failOnTimeout = !Boolean.FALSE.equals(spec.get("failOnTimeout"));
        q.created = System.currentTimeMillis();
        return q;
    }

    private static String str(JsObject o, String k, String def) {
        Object v = o.get(k);
        return v == Values.UNDEFINED || v == null ? def : Interpreter.toStr(v);
    }

    private static double num(JsObject o, String k, double def) {
        Object v = o.get(k);
        if (v == Values.UNDEFINED || v == null) return def;
        double d = Interpreter.toNumber(v);
        return Double.isNaN(d) ? def : d;
    }
}
