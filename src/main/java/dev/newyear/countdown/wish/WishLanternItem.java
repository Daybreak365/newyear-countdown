package dev.newyear.countdown.wish;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** 소원 연등. 우클릭하면 소원 쓰기 화면이 열린다. */
public class WishLanternItem extends Item {
    public WishLanternItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            ServerPlayNetworking.send(sp, new WishPackets.OpenS2C());
        }
        return TypedActionResult.success(stack, world.isClient());
    }
}
