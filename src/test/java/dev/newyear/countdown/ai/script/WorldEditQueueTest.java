package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldEditQueueTest {
    ScriptRecord.EditJob job(int id,int blocks) { var j=new ScriptRecord.EditJob();j.id="job"+id;j.ops=new int[blocks*4];return j; }
    @Test void thirteenPartHouseQueuesInOrderAndSurvivesRestart() {
        ScriptRecord r=new ScriptRecord();
        for(int i=0;i<13;i++) WorldEditQueue.enqueue(r,job(i,30),4096);
        r.edits.get(0).next=12;
        r=new Gson().fromJson(new Gson().toJson(r),ScriptRecord.class);
        assertEquals(13,r.edits.size());assertEquals(378,WorldEditQueue.remainingBlocks(r));
        for(int i=0;i<13;i++) assertEquals("job"+i,r.edits.remove(0).id);
    }
    @Test void wholePendingPlanIsLimitedBeforeAdmittingAnotherJob() {
        ScriptRecord r=new ScriptRecord();WorldEditQueue.enqueue(r,job(0,70),50);
        assertThrows(ScriptError.class,()->WorldEditQueue.enqueue(r,job(1,31),50));assertEquals(1,r.edits.size());
        r.edits.clear();WorldEditQueue.enqueue(r,job(1,31),50);assertEquals(31,WorldEditQueue.remainingBlocks(r));
    }
    @Test void completedBlocksRemainAccountedForWhileCancelledPendingBlocksAreReleased() {
        ScriptRecord r=new ScriptRecord();var j=job(0,90);WorldEditQueue.enqueue(r,j,50);
        j.next=40;for(int i=0;i<40;i++)r.blocks.add(new ScriptRecord.BlockChange());
        assertThrows(ScriptError.class,()->WorldEditQueue.enqueue(r,job(1,11),50));
        r.edits.clear();WorldEditQueue.enqueue(r,job(1,60),50);assertEquals(60,WorldEditQueue.remainingBlocks(r));
    }
    @Test void tinyJobsCannotCreateAnUnboundedQueue() {
        ScriptRecord r=new ScriptRecord();for(int i=0;i<WorldEditQueue.MAX_PENDING_JOBS;i++)WorldEditQueue.enqueue(r,job(i,1),4096);
        assertThrows(ScriptError.class,()->WorldEditQueue.enqueue(r,job(999,1),4096));
    }
    @Test void alreadyMatchingCellsCountAsCompleteWithoutClaimingTheyWereChanged() {
        var j=job(1,49);j.next=49;j.placed=40;
        var done=WorldEditQueue.completion(j);
        assertEquals(true,done.get("complete"));assertEquals(9.0,done.get("unchanged"));assertEquals(40.0,done.get("placed"));
        j.skipped=1;assertEquals(false,WorldEditQueue.completion(j).get("complete"));
    }
    @Test void unfinishedAndEmptyJobsHaveDistinctCompletionStates() {
        assertEquals(false,WorldEditQueue.completion(job(1,10)).get("complete"));
        assertEquals(true,WorldEditQueue.completion(job(2,0)).get("complete"));
    }
}
