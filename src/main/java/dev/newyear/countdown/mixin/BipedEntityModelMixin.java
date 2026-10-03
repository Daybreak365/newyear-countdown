package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.Limbs;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 갑옷 모델로 자세를 복사할 때 무릎·팔꿈치 굽힘도 함께 복사한다. */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin<T extends LivingEntity> {
    @Inject(method = "copyBipedStateTo", at = @At("TAIL"))
    private void newyearcountdown$copyBend(BipedEntityModel<T> model, CallbackInfo ci) {
        BipedEntityModel<?> self = (BipedEntityModel<?>) (Object) this;
        Limbs.bend(model.rightArm, Limbs.bendOf(self.rightArm));
        Limbs.bend(model.leftArm, Limbs.bendOf(self.leftArm));
        Limbs.bend(model.rightLeg, Limbs.bendOf(self.rightLeg));
        Limbs.bend(model.leftLeg, Limbs.bendOf(self.leftLeg));
    }
}
