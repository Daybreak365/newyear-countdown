package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.BellPose;
import dev.newyear.countdown.client.Limbs;
import dev.newyear.countdown.client.SebaePose;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 보신각 사용 중인 플레이어의 팔 자세, 세배(큰절) 자세를 적용한다. 팔꿈치를 굽히면 손에 든 아이템도 아래팔을 따라간다. */
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

    @Inject(method = "setArmAngle", at = @At("TAIL"))
    private void newyearcountdown$forearm(Arm arm, MatrixStack matrices, CallbackInfo ci) {
        PlayerEntityModel<?> self = (PlayerEntityModel<?>) (Object) this;
        ModelPart lower = Limbs.lower(arm == Arm.RIGHT ? self.rightArm : self.leftArm);
        if (lower == null) return;
        // 아이템은 팔 기준 10px 아래(손)에 그려지므로, 팔꿈치(4px)에서 꺾은 뒤 4px 되돌려 손 위치를 맞춘다
        lower.rotate(matrices);
        matrices.translate(0f, -4f / 16f, 0f);
    }
}
