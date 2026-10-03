package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.SebaePose;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 세배하는 플레이어의 이름표를 머리 높이에 맞춰 내린다. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Unique
    private float newyearcountdown$labelDrop;

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("HEAD"), require = 0)
    private void newyearcountdown$lowerLabel(AbstractClientPlayerEntity player, Text text, MatrixStack matrices,
                                            VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        float[] off = SebaePose.cameraOffset(player.getUuid());
        newyearcountdown$labelDrop = off == null ? 0f : off[0];
        if (newyearcountdown$labelDrop != 0f) matrices.translate(0f, -newyearcountdown$labelDrop, 0f);
    }

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("RETURN"), require = 0)
    private void newyearcountdown$restoreLabel(AbstractClientPlayerEntity player, Text text, MatrixStack matrices,
                                              VertexConsumerProvider vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        if (newyearcountdown$labelDrop != 0f) matrices.translate(0f, newyearcountdown$labelDrop, 0f);
        newyearcountdown$labelDrop = 0f;
    }
}
