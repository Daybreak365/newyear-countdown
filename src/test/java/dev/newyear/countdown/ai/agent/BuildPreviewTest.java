package dev.newyear.countdown.ai.agent;

import com.google.gson.Gson;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.ScriptValidator;
import dev.newyear.countdown.ai.wish.Wish;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildPreviewTest {
    ScriptValidator.Report report() {
        var r=new ScriptValidator.Report();r.ok=true;
        var b=new ScriptRecord.BlockChange();b.dimension="minecraft:overworld";b.x=271;b.y=102;b.z=4;b.before="minecraft:air";b.after="minecraft:oak_planks";
        r.planned.add(b);return r;
    }
    @Test void emptyValidationCannotClaimThatAPreviewWasSent() {
        var r=new ScriptValidator.Report();r.ok=true;var result=BuildPreview.prepare(r);
        assertFalse(result.get("ok").getAsBoolean());assertFalse(result.get("preview_sent").getAsBoolean());assertTrue(result.get("validation_passed").getAsBoolean());
    }
    @Test void blockedPartialPlanIsRejectedEvenWhenSomeBlocksWerePlanned() {
        var r=report();r.ok=false;r.errors.add("blocked at 271, 102, 5");var result=BuildPreview.prepare(r);
        assertFalse(result.get("ok").getAsBoolean());assertFalse(result.get("preview_sent").getAsBoolean());assertEquals(1,result.getAsJsonArray("errors").size());
    }
    @Test void textFallbackDoesNotClaimVisualDelivery() {
        var result=BuildPreview.prepare(report());BuildPreview.delivered(result,"pv-test","minecraft:overworld","player",30,false);
        assertFalse(result.get("preview_sent").getAsBoolean());assertEquals("chat_summary_only",result.get("delivery").getAsString());
    }
    @Test void approximateTreePreviewCannotBecomeAnExactConstructionContract() {
        var r=report();r.approximateBuild=true;var result=BuildPreview.prepare(r);
        assertFalse(result.get("ok").getAsBoolean());assertFalse(result.get("preview_sent").getAsBoolean());
        assertTrue(result.getAsJsonArray("errors").get(0).getAsString().contains("approximate"));
    }
    @Test void previewCoordinatesAndDeliveryEvidencePersistWithTheWish() {
        var result=BuildPreview.prepare(report());BuildPreview.delivered(result,"pv-test","minecraft:overworld","player",30,true);
        Wish wish=new Wish();wish.lastBuildPreview=result.deepCopy();wish.lastBuildPreviewAttempt=BuildPreview.prepare(new ScriptValidator.Report());
        wish=new Gson().fromJson(new Gson().toJson(wish),Wish.class);
        assertTrue(wish.lastBuildPreview.get("preview_sent").getAsBoolean());
        assertEquals("271,102,4",wish.lastBuildPreview.getAsJsonObject("blocks").get("from").getAsString());
        assertFalse(wish.lastBuildPreviewAttempt.get("preview_sent").getAsBoolean());
    }
}
