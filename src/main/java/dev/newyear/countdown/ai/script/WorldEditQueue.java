package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.lang.ScriptError;

/** Persistent FIFO admission, shared by validation and live execution. Only the head is executed. */
public final class WorldEditQueue {
    private WorldEditQueue() {}
    public static final int MAX_PENDING_JOBS = 256;

    public static long remainingBlocks(ScriptRecord record) {
        long n = 0;
        for (ScriptRecord.EditJob job : record.edits) n += Math.max(0, job.total() - job.next);
        return n;
    }

    public static dev.newyear.countdown.ai.script.lang.Values.JsObject completion(ScriptRecord.EditJob job) {
        int unchanged = Math.max(0, job.next - job.placed - job.skipped);
        return new dev.newyear.countdown.ai.script.lang.Values.JsObject().put("id",job.id).put("kind",job.kind)
                .put("placed",(double)job.placed).put("unchanged",(double)unchanged).put("skipped",(double)job.skipped)
                .put("total",(double)job.total()).put("complete",job.next >= job.total() && job.skipped == 0);
    }

    public static void enqueue(ScriptRecord record, ScriptRecord.EditJob job, int blockLimit) {
        if (record.edits.size() >= MAX_PENDING_JOBS)
            throw new ScriptError(ScriptError.Kind.BUDGET, "Construction queue is full (" + MAX_PENDING_JOBS + " jobs); wait for world_edit_done", 0, 0);
        long reserved = record.blocks.size() + remainingBlocks(record);
        if (reserved + job.total() > blockLimit * 2L)
            throw new ScriptError(ScriptError.Kind.BUDGET, "Construction needs " + (reserved + job.total())
                    + " recorded/pending blocks; limit is " + blockLimit * 2L + ". Split the build into completed stages", 0, 0);
        record.edits.add(job);
    }
}
