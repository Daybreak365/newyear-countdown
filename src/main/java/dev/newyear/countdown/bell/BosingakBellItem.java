package dev.newyear.countdown.bell;

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

/** 보신각 설치 아이템. 구조물이 들어갈 공간 전체가 비어 있을 때만 설치되며, 설치 전에는 홀로그램으로 미리 보인다. */
public class BosingakBellItem extends BlockItem {
    public BosingakBellItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos master = context.getBlockPos();
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();

        if (!BellLayout.canPlace(world, master, facing)) {
            if (!world.isClient && context.getPlayer() != null) {
                context.getPlayer().sendMessage(Text.translatable("bell.newyearcountdown.no_space"), true);
            }
            return ActionResult.FAIL;
        }
        if (world.isClient) return ActionResult.SUCCESS; // 실제 설치는 서버가 하고 클라이언트엔 블록 변경으로 전달된다

        BellLayout.place(world, master, facing);
        world.playSound(null, master, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.0f, 0.8f);
        context.getStack().decrementUnlessCreative(1, context.getPlayer());
        return ActionResult.SUCCESS;
    }
}
