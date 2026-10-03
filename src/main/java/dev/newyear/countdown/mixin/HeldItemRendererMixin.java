package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.SebaePose;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1인칭으로 세배하는 동안에는 화면 앞의 손/아이템을 그리지 않는다 (시점이 바닥까지 내려가므로). */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Inject(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void newyearcountdown$hideWhileBowing(float tickDelta, MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers,
                                                 ClientPlayerEntity player, int light, CallbackInfo ci) {
        if (SebaePose.isBowing(player.getUuid())) ci.cancel();
    }
}
