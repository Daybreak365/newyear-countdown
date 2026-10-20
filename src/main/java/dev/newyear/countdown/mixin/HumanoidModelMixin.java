package dev.newyear.countdown.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.newyear.countdown.client.BellPose;
import dev.newyear.countdown.client.Limbs;
import dev.newyear.countdown.client.SebaePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 플레이어(와 그 갑옷) 모델에 보신각 타종 자세·세배(큰절) 자세를 적용한다.
 * 갑옷 모델도 같은 렌더 상태로 setupAnim 을 거치므로 몸과 똑같이 움직인다.
 * 어른 갑옷 메시는 팔다리를 두 마디로 나눠 무릎·팔꿈치를 따라 접히게 한다.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void newyearcountdown$pose(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(avatar.id) instanceof Player player)) return;
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        BellPose.apply(model, player);
        SebaePose.apply(model, player);
    }

    /** 팔꿈치를 굽히면 손에 든 아이템도 아래팔 끝을 따라간다. */
    @Inject(method = "translateToHand", at = @At("TAIL"))
    private void newyearcountdown$forearm(HumanoidRenderState state, HumanoidArm arm, PoseStack poseStack, CallbackInfo ci) {
        newyearcountdown$applyForearm((HumanoidModel<?>) (Object) this, arm, poseStack);
    }

    @Inject(method = "createArmorMeshSet(Lnet/minecraft/client/model/geom/builders/CubeDeformation;Lnet/minecraft/client/model/geom/builders/CubeDeformation;)Lnet/minecraft/client/renderer/entity/ArmorModelSet;",
            at = @At("RETURN"))
    private static void newyearcountdown$splitArmor(CubeDeformation inner, CubeDeformation outer, CallbackInfoReturnable<ArmorModelSet<MeshDefinition>> cir) {
        ArmorModelSet<MeshDefinition> set = cir.getReturnValue();
        Limbs.splitArmor(set.chest(), set.legs(), set.feet(), inner, outer);
    }

    private static void newyearcountdown$applyForearm(HumanoidModel<?> model, HumanoidArm arm, PoseStack poseStack) {
        ModelPart lower = Limbs.lower(model.getArm(arm));
        if (lower == null) return;
        // 아이템은 팔 기준 10px 아래(손)에 그려지므로, 팔꿈치(4px)에서 꺾은 뒤 4px 되돌려 손 위치를 맞춘다
        lower.translateAndRotate(poseStack);
        poseStack.translate(0f, -4f / 16f, 0f);
    }
}
