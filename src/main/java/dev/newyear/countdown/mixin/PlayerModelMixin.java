package dev.newyear.countdown.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.newyear.countdown.client.Limbs;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 플레이어 모델의 팔다리(와 소매·바지)를 두 마디로 나누고, 손 아이템 위치를 아래팔에 맞춘다. */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
    @Inject(method = "createMesh", at = @At("RETURN"))
    private static void newyearcountdown$splitLimbs(CubeDeformation scale, boolean slim, CallbackInfoReturnable<MeshDefinition> cir) {
        Limbs.splitPlayer(cir.getReturnValue(), scale, slim);
    }

    @Inject(method = "translateToHand(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("TAIL"))
    private void newyearcountdown$forearm(AvatarRenderState state, HumanoidArm arm, PoseStack poseStack, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        ModelPart lower = Limbs.lower(model.getArm(arm));
        if (lower == null) return;
        lower.translateAndRotate(poseStack);
        poseStack.translate(0f, -4f / 16f, 0f);
    }
}
