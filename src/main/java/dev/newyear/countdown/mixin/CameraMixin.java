package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.BellCamera;
import dev.newyear.countdown.client.SebaePose;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 타종 중에는 카메라를 우리가 계산한 위치/방향으로 덮어쓴다. 1인칭 세배 중에는 눈 높이를 절하는 머리 위치로 내린다. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private Entity entity;

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void newyearcountdown$overrideCamera(float partialTicks, CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        if (!BellCamera.isActive()) {
            if (detached || entity == null) return;
            float[] off = SebaePose.cameraOffset(entity.getUUID());
            if (off == null) return;
            float yaw = self.yRot();
            Vec3 p = self.position();
            double fx = -Mth.sin(yaw * Mth.DEG_TO_RAD) * off[1];
            double fz = Mth.cos(yaw * Mth.DEG_TO_RAD) * off[1];
            setRotation(yaw, Mth.lerp(off[2] * 0.8f, self.xRot(), 75f));
            setPosition(p.x + fx, p.y - off[0], p.z + fz);
            return;
        }
        double[] pose = BellCamera.compute(self.position(), self.yRot(), self.xRot(), partialTicks);
        if (pose == null) return;
        setRotation((float) pose[3], (float) pose[4]);
        setPosition(pose[0], pose[1], pose[2]);
    }
}
