package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.Limbs;
import dev.newyear.countdown.client.SebaePose;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1인칭 손은 팔꿈치를 편 채로 그리고, 세배하는 플레이어의 이름표는 머리 높이로 내린다. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(method = "renderHand", at = @At("HEAD"))
    private void newyearcountdown$straightArm(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin,
                                             ModelPart arm, boolean hasSleeve, CallbackInfo ci) {
        // 모델은 모든 플레이어가 함께 쓰므로, 마지막으로 그린 플레이어의 굽힘이 1인칭 손에 남지 않게 한다
        Limbs.bend(arm, 0f);
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void newyearcountdown$lowerNameTag(net.minecraft.world.entity.Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        if (state.nameTagAttachment == null) return;
        float[] off = SebaePose.cameraOffset(entity.getUUID());
        if (off != null) state.nameTagAttachment = state.nameTagAttachment.subtract(0, off[0], 0);
    }
}
