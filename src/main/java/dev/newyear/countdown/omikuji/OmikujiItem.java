package dev.newyear.countdown.omikuji;

import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class OmikujiItem extends BlockItem {
    public OmikujiItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos master = context.getBlockPos();
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        if (!OmikujiLayout.canPlace(world, master, facing)) {
            if (!world.isClient && context.getPlayer() != null) {
                context.getPlayer().sendMessage(Text.translatable("bell.newyearcountdown.no_space"), true);
            }
            return ActionResult.FAIL;
        }
        if (world.isClient) return ActionResult.SUCCESS;
        OmikujiLayout.place(world, master, facing);
        world.playSound(null, master, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0f, 0.9f);
        context.getStack().decrementUnlessCreative(1, context.getPlayer());
        return ActionResult.SUCCESS;
    }
}
