package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.BellCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 타종 중에는 카메라를 우리가 계산한 위치/방향으로 덮어쓴다. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("RETURN"))
    private void newyearcountdown$overrideCamera(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                                 boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (!BellCamera.isActive()) return;
        Camera self = (Camera) (Object) this;
        double[] pose = BellCamera.compute(self.getPos(), self.getYaw(), self.getPitch(), tickDelta);
        if (pose == null) return;
        setRotation((float) pose[3], (float) pose[4]);
        setPos(pose[0], pose[1], pose[2]);
    }
}
