package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.Limbs;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 갑옷 모델도 팔다리를 두 마디로 나눠, 무릎·팔꿈치를 굽힌 플레이어를 그대로 따라가게 한다. */
@Mixin(ArmorEntityModel.class)
public abstract class ArmorModelDataMixin {
    @Inject(method = "getModelData", at = @At("RETURN"))
    private static void newyearcountdown$splitLimbs(Dilation dilation, CallbackInfoReturnable<ModelData> cir) {
        Limbs.splitArmor(cir.getReturnValue().getRoot(), dilation);
    }
}
