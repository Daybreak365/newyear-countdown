package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.Wish;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildPlanTest {
    ScriptRecord.BlockChange block(int x,int y,int z,String state) {
        var c=new ScriptRecord.BlockChange();c.dimension="minecraft:overworld";c.x=x;c.y=y;c.z=z;c.before="minecraft:air";c.after=state;return c;
    }
    BuildPlan plan() {return BuildPlan.capture(List.of(block(271,102,4,"minecraft:oak_planks"),block(271,107,4,"minecraft:oak_planks")),1);}
    Quest quest(BuildPlan p) {
        Quest q=new Quest();q.id="q-house";
        var logs=new Quest.Objective();logs.id="logs";logs.target=16;q.objectives.add(logs);
        var build=new Quest.Objective();build.id="build";q.objectives.add(build);p.bind(q.id,build.id);return q;
    }
    void accept(BuildPlan p){p.accept(p.id,p.revision);}
    @Test void previewSnapshotCannotFollowLaterEditsToTheSourceOrPlayerCoordinates() {
        var c=block(271,102,4,"minecraft:oak_planks");var p=BuildPlan.capture(List.of(c),1);
        c.x=999;c.after="minecraft:stone";accept(p);
        assertEquals("271,102,4 ~ 271,102,4",p.bounds());
        assertEquals(271,p.job().ops[0]);assertEquals("minecraft:oak_planks",p.job().palette.get(0));
    }
    @Test void contractMustAcceptTheExactDisplayedRevisionAndCannotResetRunningProgress() {
        var p=plan();assertThrows(IllegalArgumentException.class,()->p.accept("wrong",1));
        assertThrows(IllegalArgumentException.class,()->p.accept(p.id,2));assertFalse(p.accepted);
        accept(p);p.status="BUILDING";accept(p);assertEquals("BUILDING",p.status);
    }
    @Test void noConstructionBeforeAcceptanceAndNoDifferentLocationMaterialOrDimensionAfterIt() {
        var p=plan();assertThrows(IllegalArgumentException.class,p::job);accept(p);
        assertDoesNotThrow(()->p.requireCell("minecraft:overworld",271,102,4,"minecraft:oak_planks"));
        assertThrows(IllegalArgumentException.class,()->p.requireCell("minecraft:overworld",282,102,21,"minecraft:oak_planks"));
        assertThrows(IllegalArgumentException.class,()->p.requireCell("minecraft:the_nether",271,102,4,"minecraft:oak_planks"));
        assertThrows(IllegalArgumentException.class,()->p.requireCell("minecraft:overworld",271,102,4,"minecraft:diamond_block"));
    }
    @Test void acceptedSiteAndCompletedConditionsSurviveWaitAndRestart() {
        Wish w=new Wish();w.buildPlan=plan();var q=quest(w.buildPlan);accept(w.buildPlan);
        assertFalse(w.buildPlan.requirementsMet(q));q.objective("logs").progress=16;q.objective("logs").done=true;
        w.buildPlan.status="WAITING_SITE";w.buildPlan.reason="player at site";
        w=new Gson().fromJson(new Gson().toJson(w),Wish.class);q=new Gson().fromJson(new Gson().toJson(q),Quest.class);
        assertTrue(w.buildPlan.requirementsMet(q));assertEquals(16,q.objective("logs").progress);
        assertEquals("271,102,4 ~ 271,107,4",w.buildPlan.bounds());assertEquals(271,w.buildPlan.job().ops[0]);
    }
    @Test void stalledBuildCannotResetPaidWorkOrClaimEarlyCompletion() {
        var p=plan();var q=quest(p);accept(p);
        assertFalse(p.holdQuestEnd(q,Quest.State.FAILED,false));
        q.objective("logs").done=true;q.objective("logs").progress=16;
        assertTrue(p.preserveObjective(q,"logs"));assertTrue(p.preserveObjective(q,"build"));
        assertTrue(p.holdQuestEnd(q,Quest.State.FAILED,false));assertTrue(p.holdQuestEnd(q,Quest.State.COMPLETED,false));
        assertFalse(p.holdQuestEnd(q,Quest.State.CANCELLED,true));
        p.status="COMPLETE";assertFalse(p.preserveObjective(q,"build"));assertFalse(p.holdQuestEnd(q,Quest.State.COMPLETED,false));
    }
    @Test void creatingAnotherTrackerCannotReplaceThePaidConditions() {
        var p=plan();quest(p);
        assertDoesNotThrow(()->p.bind("q-house","build"));
        assertThrows(IllegalArgumentException.class,()->p.bind("fresh-unpaid-quest","build"));
        assertThrows(IllegalArgumentException.class,()->p.bind("q-house","logs"));
    }
    @Test void revisionCandidateDoesNotMoveTheOriginalAndRetainsItsConditions() {
        var p=plan();var q=quest(p);accept(p);q.objective("logs").done=true;
        var candidate=BuildPlan.revision(p,List.of(block(282,102,21,"minecraft:oak_planks")));
        assertFalse(candidate.accepted);assertTrue(p.accepted);assertEquals(271,p.cells.get(0).x);
        assertEquals(p.questId,candidate.questId);assertTrue(candidate.requirementsMet(q));
        assertEquals(2,candidate.revision);assertNotEquals(p.id,candidate.id);
        accept(candidate);assertEquals(282,candidate.job().ops[0]);
    }
    @Test void partiallyBuiltPlanCannotBeReplaced() {
        var p=plan();accept(p);p.started=true;
        assertThrows(IllegalArgumentException.class,()->BuildPlan.revision(p,List.of(block(282,102,21,"minecraft:oak_planks"))));
    }
    @Test void endedConstructionCannotEnqueueOrPlaceEvenBeforeTheRunnerStops() {
        for(String end:List.of("COMPLETE","CANCELLED","FAILED","REJECTED")) {
            var p=plan();accept(p);p.status=end;
            assertFalse(p.pending());assertFalse(p.unfinished());
            assertThrows(IllegalArgumentException.class,p::job);
            assertThrows(IllegalArgumentException.class,()->p.requireCell(p.dimension,271,102,4,"minecraft:oak_planks"));
        }
    }
    @Test void replacingTrackerPreservesPartialProgressButExplicitCancellationStillWorks() {
        var p=plan();var q=quest(p);accept(p);q.objective("logs").progress=7;
        assertTrue(p.holdQuestEnd(q,Quest.State.CANCELLED,false));
        assertFalse(p.holdQuestEnd(q,Quest.State.CANCELLED,true));
        assertFalse(p.holdQuestEnd(q,Quest.State.FAILED,false));
    }
    @Test void savedBlueprintRetainsItsRangeAnchorAndApprovalRequirementAcrossRestart() {
        var p=plan();p.anchor=new double[]{270,101,3};p.approvalReasons.add("large design");
        p=new Gson().fromJson(new Gson().toJson(p),BuildPlan.class);
        assertArrayEquals(new double[]{270,101,3},p.anchor);
        var runner=new ScriptRecord();assertTrue(p.needsApproval(runner));
        runner.approvedBy="admin";assertFalse(p.needsApproval(runner));
    }
    @Test void oversizePreviewIsRejectedInsteadOfLosingTheRestOfTheHouse() {
        var inst=new ScriptInstance(new ScriptRecord(),true);
        for(int i=0;i<4096;i++)inst.plan("minecraft:overworld",i,100,0,"minecraft:air","minecraft:stone",false);
        assertThrows(dev.newyear.countdown.ai.script.lang.ScriptError.class,()->inst.plan("minecraft:overworld",4096,100,0,"minecraft:air","minecraft:stone",false));
        // Replacing a cell at the cap remains legal and records the final design, including cleared air.
        inst.plan("minecraft:overworld",0,100,0,"minecraft:air","minecraft:air",false);
        assertEquals(4096,inst.planned.size());assertEquals("minecraft:air",inst.planned.get(0).after);
    }
    @Test void temporaryPreviewCannotSilentlyBecomePermanentConstruction() {
        var c=block(1,100,1,"minecraft:stone");c.temporary=true;
        assertThrows(IllegalArgumentException.class,()->BuildPlan.capture(List.of(c),1));
    }
    @Test void queuedBuildResumesAtSavedCursorWithoutRecalculatingCoordinates() {
        var p=plan();accept(p);ScriptRecord runner=new ScriptRecord();WorldEditQueue.enqueue(runner,p.job(),4096);
        runner.edits.get(0).next=1;
        runner=new Gson().fromJson(new Gson().toJson(runner),ScriptRecord.class);
        var j=runner.edits.get(0);assertEquals(p.id,j.id);assertEquals(1,j.next);
        assertArrayEquals(new int[]{271,107,4,0},Arrays.copyOfRange(j.ops,j.next*4,j.next*4+4));
    }
    @Test void derivedFenceConnectionsDoNotCauseEndlessCompletionFailureButOrientationMatters() {
        assertTrue(BuildPlan.matchesPlaced("minecraft:oak_fence[north=false,waterlogged=false]","minecraft:oak_fence[north=true,waterlogged=false]"));
        assertTrue(BuildPlan.matchesPlaced("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]","minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left]"));
        assertFalse(BuildPlan.matchesPlaced("minecraft:oak_stairs[facing=north,half=bottom]","minecraft:oak_stairs[facing=south,half=bottom]"));
        assertFalse(BuildPlan.matchesPlaced("minecraft:oak_planks","minecraft:stone"));
    }
}
