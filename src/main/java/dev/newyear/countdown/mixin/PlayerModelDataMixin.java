package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.Limbs;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 플레이어 모델의 팔다리를 두 마디(무릎·팔꿈치)로 나눈다. */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerModelDataMixin {
    @Inject(method = "getTexturedModelData", at = @At("RETURN"))
    private static void newyearcountdown$splitLimbs(Dilation dilation, boolean slim, CallbackInfoReturnable<ModelData> cir) {
        Limbs.splitPlayer(cir.getReturnValue().getRoot(), dilation, slim);
    }
}
