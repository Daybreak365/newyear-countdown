package dev.newyear.countdown.wish;

import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 소원 연등. 우클릭하면 소원 쓰기 화면이 열린다. */
public class WishLanternItem extends Item {
    public WishLanternItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack stack = user.getItemInHand(hand);
        if (!world.isClientSide() && user instanceof ServerPlayer sp) {
            ServerPlayNetworking.send(sp, new WishPackets.OpenS2C());
        }
        return InteractionResult.SUCCESS;
    }
}
