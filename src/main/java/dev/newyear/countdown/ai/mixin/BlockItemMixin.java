package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.ai.GameEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 블록 설치 (Fabric API 에 설치 이벤트가 없어 직접 건다). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void wishai$placed(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        if (ctx.getLevel().isClientSide() || ctx.getPlayer() == null || !cir.getReturnValue().consumesAction()) return;
        GameEvents.onBlockPlace(ctx.getPlayer(), ctx.getClickedPos(), ctx.getLevel().getBlockState(ctx.getClickedPos()));
    }
}
