package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.BellPose;
import dev.newyear.countdown.client.SebaePose;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 보신각 사용 중인 플레이어의 팔 자세, 세배(큰절) 자세를 적용한다. */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin<T extends LivingEntity> {
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void newyearcountdown$gripPose(T entity, float limbAngle, float limbDistance, float animationProgress,
                                           float headYaw, float headPitch, CallbackInfo ci) {
        if (entity instanceof AbstractClientPlayerEntity player) {
            BellPose.apply((PlayerEntityModel<?>) (Object) this, player);
            SebaePose.apply((PlayerEntityModel<?>) (Object) this, player);
        }
    }
}
