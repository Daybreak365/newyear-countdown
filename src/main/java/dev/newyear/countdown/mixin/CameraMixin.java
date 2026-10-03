package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.BellCamera;
import dev.newyear.countdown.client.SebaePose;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 타종 중에는 카메라를 우리가 계산한 위치/방향으로 덮어쓴다. 1인칭 세배 중에는 눈 높이를 절하는 머리 위치로 내린다. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("RETURN"))
    private void newyearcountdown$overrideCamera(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                                 boolean inverseView, float tickDelta, CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        if (!BellCamera.isActive()) {
            if (thirdPerson || focusedEntity == null) return;
            float[] off = SebaePose.cameraOffset(focusedEntity.getUuid());
            if (off == null) return;
            float yaw = self.getYaw();
            Vec3d p = self.getPos();
            double fx = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE) * off[1];
            double fz = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE) * off[1];
            setRotation(yaw, MathHelper.lerp(off[2] * 0.8f, self.getPitch(), 75f));
            setPos(p.x + fx, p.y - off[0], p.z + fz);
            return;
        }
        double[] pose = BellCamera.compute(self.getPos(), self.getYaw(), self.getPitch(), tickDelta);
        if (pose == null) return;
        setRotation((float) pose[3], (float) pose[4]);
        setPos(pose[0], pose[1], pose[2]);
    }
}
