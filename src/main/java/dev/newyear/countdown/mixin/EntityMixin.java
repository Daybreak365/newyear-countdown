package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.BellSession;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 타종 중에는 마우스 이동이 플레이어 시선을 돌리지 않고 당목을 움직이는 입력으로만 쓰이게 한다.
 * (시선을 돌렸다가 되돌리는 방식은 3인칭에서 캐릭터가 덜덜 떨려 보이는 원인이었다.)
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$captureLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if (BellSession.isActive() && (Object) this == Minecraft.getInstance().player) {
            BellSession.addLook(cursorDeltaX * 0.15);
            ci.cancel();
        }
    }
}
