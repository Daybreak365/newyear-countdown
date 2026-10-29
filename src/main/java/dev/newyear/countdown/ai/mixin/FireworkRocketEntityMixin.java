package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.ai.GameEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 소원 연출용 폭죽(fx.firework)은 터져도 주변에 피해를 주지 않는다. */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    @Inject(method = "dealExplosionDamage", at = @At("HEAD"), cancellable = true)
    private void wishai$noDamage(ServerLevel level, CallbackInfo ci) {
        if (GameEvents.isHarmlessFirework((FireworkRocketEntity) (Object) this)) ci.cancel();
    }
}
