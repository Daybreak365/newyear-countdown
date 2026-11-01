package dev.newyear.countdown.ai.script;

import com.google.gson.*;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.*;
import dev.newyear.countdown.ai.script.lang.Values.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** Persistent particle projectiles with swept collision. Hit handlers implement the gameplay. */
public final class RuntimeProjectiles {
    private RuntimeProjectiles() {}
    public static ApiObject api(ScriptInstance inst) {
        return new ApiObject("projectiles")
            .fn("fire","(position,velocity,{gravity:0,seconds:5,target:Entity|Player,homing:0..1}) → id; swept collision emits projectile_hit. No automatic damage. Max 16, speed <=3 blocks/tick.",(in,s,a)->{
                double[] at=Args.pos(in,Args.get(a,0),"position"),v=Args.pos(in,Args.get(a,1),"velocity");Policy.checkRange(in,inst,at);
                double speed=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);if(speed<0.001||speed>3)throw in.error("Projectile speed must be 0.001..3");
                JsObject opts=Args.opts(a,2);double gravity=Args.optNum(opts,"gravity",0),seconds=Args.optNum(opts,"seconds",5),homing=Args.optNum(opts,"homing",0);
                if(!Double.isFinite(gravity)||Math.abs(gravity)>0.2||!Double.isFinite(seconds)||seconds<=0||seconds>30||!Double.isFinite(homing)||homing<0||homing>1)throw in.error("Invalid projectile options");
                String id="p-"+UUID.randomUUID().toString().substring(0,8);if(inst.dry){inst.effect("projectile "+id);return id;}
                JsonObject shots=RuntimeState.section(inst.rec,"projectiles");if(shots.size()>=16)throw in.error("Projectile limit reached");
                JsonObject shot=new JsonObject();for(int i=0;i<3;i++){shot.addProperty("p"+i,at[i]);shot.addProperty("v"+i,v[i]);}shot.addProperty("gravity",gravity);shot.addProperty("remaining",(int)(seconds*20));shot.addProperty("homing",homing);shot.addProperty("speed",speed);
                Object target=opts.get("target");if(target instanceof EntityRef er)shot.addProperty("target",er.id.toString());else if(target instanceof PlayerRef pr)shot.addProperty("target",pr.id.toString());shots.add(id,shot);return id;
            })
            .fn("remove","(id) remove your projectile",(in,s,a)->{if(!inst.dry)RuntimeState.section(inst.rec,"projectiles").remove(Args.str(in,a,0,"id"));return true;});
    }
    public static void tick(WishEngine eng) {
        for(ScriptRecord rec:new ArrayList<>(eng.state().scripts.values())){
            JsonObject shots=RuntimeState.section(rec,"projectiles");if(!rec.state.live())continue;
            ServerLevel level=McUtil.level(eng.server(),rec.dimension);if(level==null)continue;
            for(String id:new ArrayList<>(shots.keySet())){
                JsonObject shot=shots.getAsJsonObject(id);int remaining=shot.get("remaining").getAsInt();if(remaining<=0){shots.remove(id);continue;}
                Vec3 from=new Vec3(shot.get("p0").getAsDouble(),shot.get("p1").getAsDouble(),shot.get("p2").getAsDouble());Vec3 velocity=new Vec3(shot.get("v0").getAsDouble(),shot.get("v1").getAsDouble(),shot.get("v2").getAsDouble());
                if(shot.has("target")){Entity target=level.getEntity(UUID.fromString(shot.get("target").getAsString()));if(target!=null){double h=shot.get("homing").getAsDouble();velocity=velocity.scale(1-h).add(target.position().add(0,target.getBbHeight()/2,0).subtract(from).normalize().scale(shot.get("speed").getAsDouble()*h));}}
                Vec3 to=from.add(velocity);if(!level.isLoaded(net.minecraft.core.BlockPos.containing(to))||rec.anchor!=null&&to.distanceToSqr(new Vec3(rec.anchor[0],rec.anchor[1],rec.anchor[2]))>Math.pow(dev.newyear.countdown.ai.AiConfig.get().scriptMaxRadius,2)){shots.remove(id);continue;}
                HitResult block=level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()));
                Vec3 hit=block.getType()==HitResult.Type.MISS?to:block.getLocation();Entity closest=null;double distance=from.distanceToSqr(hit);
                for(Entity entity:level.getEntities((Entity)null,new AABB(from,to).inflate(0.4),e->e.isAlive()&&e.isPickable()&&!e.getUUID().toString().equals(rec.owner))){var point=entity.getBoundingBox().inflate(0.2).clip(from,to);if(point.isPresent()&&from.distanceToSqr(point.get())<=distance){hit=point.get();distance=from.distanceToSqr(hit);closest=entity;}}
                level.sendParticles(ParticleTypes.END_ROD,hit.x,hit.y,hit.z,2,0.02,0.02,0.02,0);
                if(closest!=null||block.getType()!=HitResult.Type.MISS){shots.remove(id);JsObject ev=new JsObject().put("id",id).put("pos",Args.posObj(hit.x,hit.y,hit.z)).put("owner",rec.owner==null?null:new PlayerRef(UUID.fromString(rec.owner),rec.ownerName)).put("target",closest==null?null:dev.newyear.countdown.ai.GameEvents.handle(closest));eng.scripts().emit("projectile_hit",ev,new ScriptManager.Ctx(rec.owner==null?null:UUID.fromString(rec.owner),closest==null?null:closest.getUUID(),null,null,rec.id));}
                else {shot.addProperty("remaining",remaining-1);shot.addProperty("p0",to.x);shot.addProperty("p1",to.y);shot.addProperty("p2",to.z);shot.addProperty("v0",velocity.x);shot.addProperty("v1",velocity.y-shot.get("gravity").getAsDouble());shot.addProperty("v2",velocity.z);}
            }
            ScriptInstance inst=eng.scripts().instance(rec.id);if(inst!=null)eng.scripts().stopIfIdleOneShot(inst);
        }
    }
}
