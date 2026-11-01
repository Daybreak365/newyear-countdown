package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.ai.GameEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** 점프(서버는 이동 패킷에서 점프를 감지해 jumpFromGround 를 부른다)와 상태 효과 변화를 스크립트 이벤트로 알린다. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "hurtServer")
    private boolean wishai$damage(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source,
                                  float amount, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
        var engine=dev.newyear.countdown.ai.WishEngine.get();
        if(engine==null||!engine.aiEnabled()||!engine.scripts().hasListeners("before_damage"))return original.call(level,source,amount);
        float changed = dev.newyear.countdown.ai.script.RuntimeHooks.damage((LivingEntity)(Object)this,source,amount);
        return changed > 0 && original.call(level,source,changed);
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void wishai$jump(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer sp) GameEvents.onJump(sp);
    }

    @Inject(method = "onEffectAdded", at = @At("TAIL"))
    private void wishai$effectAdded(MobEffectInstance effect, Entity source, CallbackInfo ci) {
        GameEvents.onEffectAdded((LivingEntity) (Object) this, effect);
    }

    @Inject(method = "onEffectsRemoved", at = @At("TAIL"))
    private void wishai$effectsRemoved(Collection<MobEffectInstance> effects, CallbackInfo ci) {
        GameEvents.onEffectsRemoved((LivingEntity) (Object) this, effects);
    }
}
