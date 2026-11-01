package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.GameEvents;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.Args;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

public final class RuntimeHooks {
    private RuntimeHooks() {}
    public static float damage(LivingEntity victim, DamageSource source, float amount) {
        WishEngine eng = WishEngine.get();
        if (eng == null || victim.level().isClientSide() || !Float.isFinite(amount)) return amount;
        Object target = GameEvents.handle(victim);
        JsObject ev = new JsObject().put("target",target).put("amount",(double)amount)
            .put("cause",source.getMsgId()).put("attacker",source.getEntity()==null?null:GameEvents.handle(source.getEntity()));
        if (victim instanceof ServerPlayer) ev.put("player",target);
        JsObject result = eng.scripts().intercept("before_damage",ev,new ScriptManager.Ctx(victim instanceof ServerPlayer?victim.getUUID():null,victim.getUUID(),null,null,null),target);
        return Boolean.TRUE.equals(result.get("cancel")) ? 0 : ((Number)result.get("amount")).floatValue();
    }
    public static boolean death(LivingEntity victim, DamageSource source) {
        WishEngine eng = WishEngine.get();
        if (eng == null || victim.level().isClientSide()) return false;
        Object target = GameEvents.handle(victim);
        JsObject ev = new JsObject().put("target",target).put("cause",source.getMsgId())
                .put("pos",Args.posObj(victim.getX(),victim.getY(),victim.getZ()));
        if (victim instanceof ServerPlayer) ev.put("player",target);
        JsObject result = eng.scripts().intercept("before_death",ev,new ScriptManager.Ctx(victim instanceof ServerPlayer?victim.getUUID():null,victim.getUUID(),null,null,null),target);
        if (!Boolean.TRUE.equals(result.get("cancel"))) return false;
        if (victim.getHealth() <= 0) victim.setHealth(1);
        return true;
    }
}
