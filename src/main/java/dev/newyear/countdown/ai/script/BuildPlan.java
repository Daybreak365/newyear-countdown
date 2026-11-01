package dev.newyear.countdown.ai.script;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.wish.Quest;
import java.util.*;

/** A persisted design. Preview, acceptance and execution all refer to these same world-space cells. */
public final class BuildPlan {
    public String id;
    public int revision;
    public String dimension;
    public double[] anchor;
    public List<String> approvalReasons = new ArrayList<>();
    public List<ScriptRecord.BlockChange> cells = new ArrayList<>();
    public boolean accepted;
    public long acceptedAt;
    public String status = "PREVIEW";
    public String reason = "";
    public String questId, buildObjective, runnerScriptId;
    public boolean started;
    private transient Map<String,String> targets;

    public static BuildPlan capture(List<ScriptRecord.BlockChange> source, int revision) {
        if (source.isEmpty()) throw new IllegalArgumentException("A build plan must contain blocks");
        BuildPlan plan = new BuildPlan();plan.id="bp-"+UUID.randomUUID().toString().substring(0,8);plan.revision=revision;
        plan.dimension=source.get(0).dimension;
        for(var b:source) {
            if (!Objects.equals(plan.dimension,b.dimension)) throw new IllegalArgumentException("A build plan must use one dimension");
            if(b.temporary) throw new IllegalArgumentException("A saved construction blueprint must contain permanent blocks; remove temporary/seconds options");
            var c=new ScriptRecord.BlockChange();c.dimension=b.dimension;c.x=b.x;c.y=b.y;c.z=b.z;c.before=b.before;c.after=b.after;
            plan.cells.add(c);
        }
        return plan;
    }
    public void accept(String planId,int planRevision) {
        if (!Objects.equals(id,planId) || revision!=planRevision) throw new IllegalArgumentException("The contract refers to a different build plan; keep the displayed design");
        if(accepted) return;
        accepted=true;acceptedAt=System.currentTimeMillis();status="ACCEPTED";
    }
    public boolean unfinished() { return !status.equals("COMPLETE") && !status.equals("CANCELLED") && !status.equals("FAILED") && !status.equals("REJECTED"); }
    public boolean pending() { return accepted && unfinished(); }
    public boolean needsApproval(ScriptRecord runner) { return !approvalReasons.isEmpty() && runner.approvedBy==null; }
    public boolean requirementsMet(Quest quest) {
        if (quest==null || !Objects.equals(quest.id,questId) || quest.objective(buildObjective)==null) return false;
        for(var o:quest.objectives) if (!o.id.equals(buildObjective) && !o.optional && !o.done) return false;
        return true;
    }
    public boolean preserveObjective(Quest q,String oid) {
        if(!Objects.equals(questId,q.id)) return false;
        if(Objects.equals(buildObjective,oid)) return !status.equals("COMPLETE");
        return pending() && q.objective(oid)!=null && q.objective(oid).done;
    }
    public boolean holdQuestEnd(Quest q,Quest.State end,boolean playerCancelled) {
        return pending() && Objects.equals(questId,q.id) && !playerCancelled
                && (end==Quest.State.COMPLETED || end==Quest.State.CANCELLED || requirementsMet(q));
    }
    public void bind(String qid,String objective) {
        if (questId!=null && (!questId.equals(qid) || !Objects.equals(buildObjective,objective)))
            throw new IllegalArgumentException("This build already has a condition tracker; reuse it without resetting progress");
        questId=qid;buildObjective=objective;
    }
    public ScriptRecord.EditJob job() {
        if(!pending()) throw new IllegalArgumentException("Construction needs an accepted, unfinished build plan");
        var job=new ScriptRecord.EditJob();job.id=id;job.kind="approved_build";job.dimension=dimension;job.ops=new int[cells.size()*4];
        Map<String,Integer> palette=new LinkedHashMap<>();int i=0;
        for(var c:cells){Integer n=palette.get(c.after);if(n==null){n=job.palette.size();palette.put(c.after,n);job.palette.add(c.after);}job.ops[i++]=c.x;job.ops[i++]=c.y;job.ops[i++]=c.z;job.ops[i++]=n;}
        return job;
    }
    /** Connections and powered/open states can change naturally; material and placed orientation must match. */
    public static boolean matchesPlaced(String expected,String actual) {
        String want=expected.split("\\[",2)[0],have=actual.split("\\[",2)[0];
        if(!want.equals(have)) return false;
        Map<String,String> a=properties(expected),b=properties(actual);
        for(String key:List.of("facing","axis","half","type","rotation")) if(!Objects.equals(a.get(key),b.get(key))) return false;
        return true;
    }
    private static Map<String,String> properties(String state) {
        Map<String,String> out=new HashMap<>();int at=state.indexOf('[');if(at<0)return out;
        for(String field:state.substring(at+1,state.length()-1).split(",")){String[] kv=field.split("=",2);if(kv.length==2)out.put(kv[0],kv[1]);}
        return out;
    }
    public void requireCell(String dim,int x,int y,int z,String block) {
        if (!pending()) throw new IllegalArgumentException("The displayed build plan is not accepted or has already ended");
        if (targets==null) {
            targets=new HashMap<>();
            for(var c:cells) targets.put(c.x+","+c.y+","+c.z,c.after);
        }
        if (!Objects.equals(dimension,dim) || !Objects.equals(targets.get(x+","+y+","+z),block))
            throw new IllegalArgumentException("Build differs from accepted plan "+id+" at "+x+","+y+","+z+". Use construction.start; changing the design requires player confirmation");
    }
    public String bounds() {
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(var c:cells){x0=Math.min(x0,c.x);y0=Math.min(y0,c.y);z0=Math.min(z0,c.z);x1=Math.max(x1,c.x);y1=Math.max(y1,c.y);z1=Math.max(z1,c.z);}
        return x0+","+y0+","+z0+" ~ "+x1+","+y1+","+z1;
    }
    public JsonObject summary() {
        JsonObject out=new JsonObject();out.addProperty("id",id);out.addProperty("revision",revision);out.addProperty("dimension",dimension);
        out.addProperty("bounds",bounds());out.addProperty("blocks",cells.size());out.addProperty("accepted",accepted);
        out.addProperty("status",status);out.addProperty("reason",reason);out.addProperty("quest_id",questId);
        if(!approvalReasons.isEmpty())out.addProperty("approval_reason",String.join("; ",approvalReasons));
        out.addProperty("build_objective",buildObjective);out.addProperty("runner_script_id",runnerScriptId);return out;
    }
    public static BuildPlan revision(BuildPlan old,List<ScriptRecord.BlockChange> cells) {
        if(old.started) throw new IllegalArgumentException("Construction has already started; cancel it explicitly before replacing its design");
        BuildPlan next=capture(cells,old.revision+1);
        next.questId=old.questId;next.buildObjective=old.buildObjective;next.runnerScriptId=old.runnerScriptId;
        return next;
    }
}
