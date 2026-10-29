package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.Args;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import dev.newyear.countdown.ai.wish.Quest;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 스크립트가 보는 퀘스트 핸들. 진행 갱신은 서버 상태만 바꾸고 AI 를 부르지 않는다. */
public final class QuestRef implements Values.HostObject {
    public final String id;
    private final boolean dry;
    private final Quest dryQuest;

    public QuestRef(String id, boolean dry, Quest dryQuest) {
        this.id = id;
        this.dry = dry;
        this.dryQuest = dryQuest;
    }

    private Quest quest() {
        if (dry) return dryQuest;
        WishEngine e = WishEngine.get();
        return e == null ? null : e.state().quests.get(id);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof QuestRef r && r.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public Object getMember(Interpreter in, String name) {
        switch (name) {
            case "id": return id;
            case "$ref": return "quest:" + id;
            case "title": { Quest q = quest(); return q == null ? "" : q.title; }
            default: break;
        }
        Values.Callable m = METHODS.get(name);
        return m == null ? Values.UNDEFINED : m;
    }

    @Override
    public Collection<String> memberNames() {
        List<String> all = new java.util.ArrayList<>(List.of("id", "title"));
        all.addAll(METHODS.keySet());
        return all;
    }

    @Override
    public String typeName() {
        return "Quest";
    }

    private static final Map<String, Values.Callable> METHODS = new LinkedHashMap<>();
    public static final Map<String, String> DOCS = new LinkedHashMap<>();

    private static void def(String n, String doc, NativeFn.Impl impl) {
        METHODS.put(n, new NativeFn("quest." + n, impl));
        DOCS.put(n, doc);
    }

    private static QuestRef self(Interpreter in, Object s) {
        if (s instanceof QuestRef r) return r;
        throw in.error("quest method called without a quest");
    }

    private static Quest need(Interpreter in, Object s) {
        Quest q = self(in, s).quest();
        if (q == null) throw in.error("quest no longer exists");
        return q;
    }

    private static Object update(Interpreter in, Object s, String oid, double value, boolean add) {
        QuestRef r = self(in, s);
        Quest q = need(in, s);
        Quest.Objective o = q.objective(oid);
        if (o == null) throw in.error("quest has no objective '" + oid + "'. Objectives: " + q.objectives.stream().map(x -> x.id).toList());
        if (r.dry) {
            ScriptInstance.of(in).effect("quest progress " + oid + (add ? " +" : " = ") + value);
            o.progress = Math.max(0, Math.min(o.target, add ? o.progress + value : value));
            o.done = o.progress >= o.target;
            return q.allRequiredDone();
        }
        return WishEngine.get().quests().progress(q.id, oid, value, add);
    }

    static {
        def("add", "(objectiveId, amount=1) → true when all required objectives are done", (in, s, a) -> update(in, s, Args.str(in, a, 0, "objective id"), Args.num(a, 1, 1), true));
        def("set", "(objectiveId, value)", (in, s, a) -> update(in, s, Args.str(in, a, 0, "objective id"), Args.num(in, a, 1, "value"), false));
        def("done", "(objectiveId) mark one objective complete", (in, s, a) -> {
            Quest q = need(in, s);
            Quest.Objective o = q.objective(Args.str(in, a, 0, "objective id"));
            if (o == null) throw in.error("unknown objective");
            return update(in, s, o.id, o.target, false);
        });
        def("progress", "(objectiveId) → {progress, target, done}", (in, s, a) -> {
            Quest.Objective o = need(in, s).objective(Args.str(in, a, 0, "objective id"));
            if (o == null) return null;
            return new JsObject().put("progress", o.progress).put("target", o.target).put("done", o.done);
        });
        def("isComplete", "() → all required objectives done", (in, s, a) -> need(in, s).allRequiredDone());
        def("state", "() → 'ACTIVE' | 'COMPLETED' | 'FAILED' | 'CANCELLED'", (in, s, a) -> need(in, s).state.name());
        def("complete", "(note?) finish the quest successfully (give rewards yourself in the script)", (in, s, a) -> {
            QuestRef r = self(in, s);
            if (r.dry) { ScriptInstance.of(in).effect("quest complete"); need(in, s).state = Quest.State.COMPLETED; return true; }
            WishEngine.get().quests().finish(r.id, Quest.State.COMPLETED, Args.str(a, 0, ""));
            return true;
        });
        def("fail", "(reason?)", (in, s, a) -> {
            QuestRef r = self(in, s);
            if (r.dry) { ScriptInstance.of(in).effect("quest fail"); need(in, s).state = Quest.State.FAILED; return true; }
            WishEngine.get().quests().finish(r.id, Quest.State.FAILED, Args.str(a, 0, ""));
            return true;
        });
        def("remaining", "() → seconds left (-1 if no limit)", (in, s, a) -> {
            Quest q = need(in, s);
            if (q.deadlineTick <= 0) return -1.0;
            long now = WishEngine.get() == null ? 0 : WishEngine.get().tick();
            return Math.max(0, (q.deadlineTick - now) / 20.0);
        });
        def("objectives", "() → [{id, text, progress, target, done}]", (in, s, a) -> {
            JsArray arr = new JsArray();
            for (Quest.Objective o : need(in, s).objectives) {
                arr.list.add(new JsObject().put("id", o.id).put("text", o.text).put("progress", o.progress).put("target", o.target).put("done", o.done));
            }
            return arr;
        });
    }
}
