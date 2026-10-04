package dev.newyear.countdown.omikuji;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public class OmikujiItem extends BlockItem {
    public OmikujiItem(Block block, Properties settings) {
        super(block, settings);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level world = context.getLevel();
        BlockPos master = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().getOpposite();
        if (!OmikujiLayout.canPlace(world, master, facing)) {
            if (!world.isClientSide() && context.getPlayer() != null) {
                context.getPlayer().sendOverlayMessage(Component.translatable("bell.newyearcountdown.no_space"));
            }
            return InteractionResult.FAIL;
        }
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        OmikujiLayout.place(world, master, facing);
        world.playSound(null, master, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 0.9f);
        context.getItemInHand().consume(1, context.getPlayer());
        return InteractionResult.SUCCESS;
    }
}
