package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.lang.Values.JsObject;

/** Reduce only returned, successful decisions; event payload mutation has no authority. */
public final class InterceptDecision {
    private InterceptDecision() {}
    public static void merge(JsObject result,Object answer) {
        if (!(answer instanceof JsObject change) || Boolean.TRUE.equals(result.get("cancel"))) return;
        if (Boolean.TRUE.equals(change.get("cancel"))) { result.put("cancel",true); return; }
        if (change.get("amount") instanceof Number n && Double.isFinite(n.doubleValue()))
            result.put("amount",Math.max(0,Math.min(40,n.doubleValue())));
    }
}
