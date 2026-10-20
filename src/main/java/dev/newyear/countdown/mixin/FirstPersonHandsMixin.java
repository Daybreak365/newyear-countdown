package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.SebaePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1인칭으로 세배하는 동안에는 화면 앞의 손/아이템을 그리지 않는다 (시점이 바닥까지 내려가므로). */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$hideWhileBowing(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && SebaePose.isBowing(mc.player.getUUID())) ci.cancel();
    }
}
