package dev.newyear.countdown.ai.mixin;

import dev.newyear.countdown.ai.GameEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 아이템 줍기. 던진 사람이 다른 플레이어면 "선물(item_give)" 이벤트도 된다. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow public abstract ItemStack getItem();
    @Shadow public abstract Entity getOwner();

    @Unique private int wishai$before;
    @Unique private ItemStack wishai$stack = ItemStack.EMPTY;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void wishai$beforeTouch(Player player, CallbackInfo ci) {
        ItemStack st = getItem();
        wishai$before = st.getCount();
        wishai$stack = st.copy();
    }

    @Inject(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;onItemPickup(Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void wishai$picked(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) return;
        ItemEntity self = (ItemEntity) (Object) this;
        int picked = self.isRemoved() ? wishai$before : wishai$before - getItem().getCount();
        if (picked <= 0) return;
        GameEvents.onItemPickup(sp, wishai$stack, picked, getOwner());
    }
}
