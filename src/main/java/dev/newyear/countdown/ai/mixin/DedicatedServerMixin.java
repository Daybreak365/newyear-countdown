package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.ai.WishEngine;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * pause-when-empty-seconds: 플레이어가 없어도 돌아야 하는 소원 스크립트(세계 규칙, 이름 타이머, 남은 시간이 흐르는 능력,
 * 마감이 있는 퀘스트)가 있으면 빈 서버를 재우지 않는다. 그런 스크립트가 없으면 서버 설정 그대로.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {
    @Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$stayAwakeForWishScripts(CallbackInfoReturnable<Integer> cir) {
        WishEngine e = WishEngine.get();
        if (e != null && e.needsTickingWhileEmpty()) cir.setReturnValue(0);
    }
}
