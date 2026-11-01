package dev.newyear.countdown.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.script.ScriptValidator;

/** Validation is not delivery. Keep that distinction in tool results and saved conversation context. */
public final class BuildPreview {
    private BuildPreview() {}
    public static JsonObject showSaved(dev.newyear.countdown.ai.WishEngine eng,net.minecraft.server.level.ServerPlayer viewer,
                                       dev.newyear.countdown.ai.wish.Wish wish,String title,int seconds) {
        var plan=wish.buildPlan;
        ScriptValidator.Report report=new ScriptValidator.Report();report.ok=true;report.planned.addAll(plan.cells);
        JsonObject out=prepare(report);
        eng.ui().blockPreview(viewer,plan.id,title,plan.cells,seconds);
        delivered(out,plan.id,plan.dimension,viewer.getGameProfile().name(),seconds,eng.ui().hasModClient(viewer));
        out.add("build_plan",plan.summary());
        out.addProperty("reused_saved_design",true);
        wish.lastBuildPreview=out.deepCopy();wish.lastBuildPreviewAttempt=out.deepCopy();return out;
    }
    public static JsonObject prepare(ScriptValidator.Report report) {
        JsonObject result=new JsonObject();
        result.addProperty("ok",report.ok && !report.planned.isEmpty() && !report.approximateBuild);
        result.addProperty("validation_passed",report.ok);
        result.addProperty("preview_sent",false);
        JsonArray errors=new JsonArray();
        for(String error:report.errors) errors.add(error);
        if(report.approximateBuild)errors.add("This design includes approximate/random generation. Use exact log/leaf block cells for saved construction previews.");
        if(!errors.isEmpty()) result.add("errors",errors);
        result.add("blocks",report.blockSummary());
        if(report.planned.isEmpty()) result.addProperty("note","No blocks to preview. No preview was sent; do not say the player has seen a design.");
        return result;
    }
    public static void delivered(JsonObject result,String id,String dimension,String viewer,int seconds,boolean overlay) {
        result.addProperty("preview_id",id);
        result.addProperty("dimension",dimension);
        result.addProperty("sent_to",viewer);
        result.addProperty("seconds",seconds);
        result.addProperty("sent_at",System.currentTimeMillis());
        result.addProperty("preview_sent",overlay);
        result.addProperty("delivery",overlay ? "overlay_packet" : "chat_summary_only");
        result.addProperty("note",overlay ? "Preview packet sent at these fixed world coordinates; client rendering is not acknowledged. Reuse these coordinates after the player moves."
                : "Viewer has no mod client; only a textual summary was sent, not a visible ghost build.");
    }
}
