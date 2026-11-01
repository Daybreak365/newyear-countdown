package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Stdlib;
import dev.newyear.countdown.ai.wish.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Engine-owned construction: generated scripts track conditions, never recreate accepted coordinates. */
public final class RuntimeConstruction {
    private RuntimeConstruction() {}
    public static BuildPlan plan(ScriptRecord r) {
        WishEngine eng=WishEngine.get();
        Wish w=eng==null || r.wishId==null ? null : eng.state().wishes.get(r.wishId);
        return w==null ? null : w.buildPlan;
    }
    public static ApiObject api(ScriptInstance inst) {
        ApiObject api=new ApiObject("construction");
        api.fn("status","() → saved build plan, fixed bounds, acceptance, condition tracker and waiting reason; null if none",(in,s,a)->{
            BuildPlan p=plan(inst.rec);return p==null ? null : Stdlib.fromJson(p.summary(),null);
        });
        api.fn("start","(questId, buildObjectiveId) → plan status. Arm the ACCEPTED saved design once. Server waits for all other required objectives, then builds at the original coordinates and completes this objective. Repeated calls preserve progress",(in,s,a)->{
            BuildPlan p=plan(inst.rec);
            if(p==null || !p.accepted) throw in.error("Preview and accept a build plan first");
            String qid=Args.str(in,a,0,"quest id"),oid=Args.str(in,a,1,"build objective");
            WishEngine eng=WishEngine.get();Quest q=inst.dry && inst.dryQuests.containsKey(qid) ? inst.dryQuests.get(qid) : eng.state().quests.get(qid);
            if(!Objects.equals(inst.rec.owner,eng.state().wishes.get(inst.rec.wishId).player)) throw in.error("Construction must belong to this wish's owner");
            if(q==null || !Objects.equals(q.wishId,inst.rec.wishId) || q.objective(oid)==null)
                throw in.error("Use this wish's existing quest and its construction objective");
            if(p.questId!=null && (!p.questId.equals(qid)||!Objects.equals(p.buildObjective,oid))) throw in.error("Build conditions are already bound; reuse the existing quest");
            inst.questSnapshot(q);
            inst.engineQuestObjectives.put(qid, oid);
            if(inst.dry) {inst.effect("arm saved build plan "+p.id);return Stdlib.fromJson(p.summary(),null);}
            p.bind(qid,oid);
            if(!p.pending()) return Stdlib.fromJson(p.summary(),null);
            ScriptRecord runner=p.runnerScriptId==null ? null : eng.state().scripts.get(p.runnerScriptId);
            if(runner==null) {
                runner=new ScriptRecord();runner.id="s-"+UUID.randomUUID().toString().substring(0,8);
                runner.name="확정 설계 시공";runner.code="// Construction is executed by the server from the accepted blueprint.";
                runner.owner=inst.rec.owner;runner.ownerName=inst.rec.ownerName;runner.wishId=inst.rec.wishId;runner.dimension=p.dimension;
                var first=p.cells.get(0);runner.anchor=p.anchor==null ? new double[]{first.x,first.y,first.z} : p.anchor.clone();runner.durationTicks=-1;
                runner.preview=new ArrayList<>(p.cells);runner.created=System.currentTimeMillis();
                p.runnerScriptId=runner.id;eng.state().scripts.put(runner.id,runner);eng.state().wishes.get(runner.wishId).scriptIds.add(runner.id);
            }
            if(waitForApproval(p,runner)) return Stdlib.fromJson(p.summary(),null);
            if(eng.scripts().instance(runner.id)!=null) return Stdlib.fromJson(p.summary(),null);
            if(eng.scripts().instance(runner.id)==null) {
                var result=eng.scripts().activate(runner,true);
                if(!result.ok()) throw in.error("Could not start saved build: "+result.error());
            }
            p.status="WAITING_REQUIREMENTS";p.reason="계약 조건이 완료되면 확정된 위치에 시공합니다.";
            return Stdlib.fromJson(p.summary(),null);
        });
        return api;
    }
    public static boolean owns(ScriptInstance inst,ScriptRecord.EditJob job) {
        BuildPlan p=plan(inst.rec);
        return p!=null && Objects.equals(p.runnerScriptId,inst.rec.id) && Objects.equals(job.id,p.id);
    }
    public static void guard(ScriptInstance inst,String dim,BlockPos at,String block) {
        if(inst.dry) return;
        BuildPlan p=plan(inst.rec);if(p==null) return;
        if(p.pending() && !p.requirementsMet(WishEngine.get().state().quests.get(p.questId)))
            throw new ScriptError(ScriptError.Kind.PERMISSION,"Build conditions are not complete; arm construction.start and let the server wait",0,0);
        try {p.requireCell(dim,at.getX(),at.getY(),at.getZ(),block);}
        catch(IllegalArgumentException e){throw new ScriptError(ScriptError.Kind.PERMISSION,e.getMessage(),0,0);}
    }
    public static void tick(ScriptInstance inst) {
        BuildPlan p=plan(inst.rec);
        if(p==null || !p.pending() || !inst.rec.state.live() || !Objects.equals(p.runnerScriptId,inst.rec.id)) return;
        if(waitForApproval(p,inst.rec) || !inst.rec.edits.isEmpty()) return;
        WishEngine eng=WishEngine.get();Quest q=eng.state().quests.get(p.questId);
        if (q != null && q.preparing) return;
        if(!p.requirementsMet(q)){waiting(p,"WAITING_REQUIREMENTS","계약 조건을 기다리는 중입니다.",inst);return;}
        var owner=inst.rec.owner==null ? null : eng.player(UUID.fromString(inst.rec.owner));
        if(owner==null){waiting(p,"WAITING_SITE","접속하면 확정된 위치에서 건축을 이어갑니다.",inst);return;}
        var job=p.job();
        try {WorldEditQueue.enqueue(inst.rec,job,dev.newyear.countdown.ai.AiConfig.get().scriptMaxBlocks);p.started=true;p.status="BUILDING";p.reason="";}
        catch(ScriptError e){waiting(p,"WAITING_SITE",e.getMessage(),inst);}
    }
    public static void waiting(BuildPlan p,String status,String reason,ScriptInstance inst) {
        if(!p.pending()) return;
        boolean changed=!status.equals(p.status)||!reason.equals(p.reason);p.status=status;p.reason=reason;
        if(changed && status.equals("WAITING_SITE") && inst.rec.owner!=null){var player=WishEngine.get().player(UUID.fromString(inst.rec.owner));
            if(player!=null)WishEngine.get().ui().notify(player,"건축 대기",reason+" 완료한 조건과 위치는 유지됩니다.","info",6);}
    }
    private static boolean waitForApproval(BuildPlan p,ScriptRecord runner) {
        if(!p.needsApproval(runner)) return false;
        var eng=WishEngine.get();
        boolean first=runner.state!=ScriptRecord.State.PENDING_APPROVAL;
        if(eng.scripts().instance(runner.id)!=null) eng.scripts().stop(runner.id,ScriptRecord.State.PENDING_APPROVAL,"build plan needs approval",false);
        runner.state=ScriptRecord.State.PENDING_APPROVAL;runner.stateReason=String.join("; ",p.approvalReasons);
        p.status="WAITING_APPROVAL";p.reason=runner.stateReason;
        if(first) {
            eng.notifyAdmins("건축 설계 승인 요청: "+runner.id+" — "+runner.stateReason+" · /wishai script "+runner.id+" preview · /wishai approve "+runner.id);
            var wish=eng.state().wishes.get(runner.wishId);
            if(wish!=null)eng.wishes().transition(wish,Wish.State.AWAITING_APPROVAL,"saved build plan needs admin approval");
        }
        return true;
    }
    public static boolean occupied(net.minecraft.server.level.ServerLevel level,BlockPos pos,net.minecraft.world.level.block.state.BlockState target) {
        return !target.getCollisionShape(level,pos).isEmpty() && level.getEntities((net.minecraft.world.entity.Entity)null,new AABB(pos),
                e->e instanceof LivingEntity && e.isAlive() && !e.isSpectator()).size()>0;
    }
    public static void completed(ScriptInstance inst) {
        BuildPlan p=plan(inst.rec);if(p==null || !p.pending()) return;
        var eng=WishEngine.get();var level=McUtil.level(eng.server(),p.dimension);
        for(var c:p.cells){BlockPos pos=new BlockPos(c.x,c.y,c.z);
            if(level==null || !level.isLoaded(pos) || !BuildPlan.matchesPlaced(c.after,McUtil.blockString(level.getBlockState(pos)))){
                waiting(p,"WAITING_SITE","완공 확인 중 변경된 블록을 발견했습니다. 같은 설계로 다시 확인합니다.",inst);return;
            }
        }
        p.status="COMPLETE";p.reason="";
        Quest q=eng.state().quests.get(p.questId);
        if(q!=null && q.objective(p.buildObjective)!=null) eng.quests().progress(q.id,p.buildObjective,q.objective(p.buildObjective).target,false);
        eng.wishes().completeFromScript(inst.rec.wishId,"수락한 설계와 좌표 그대로 건축하고 모든 블록을 확인했습니다.");
    }
}
