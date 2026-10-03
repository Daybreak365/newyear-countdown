package dev.newyear.countdown.gacha;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.List;

/** 윷 세트: 바닥에 3x3 윷판을 깐다. 클릭한 칸이 가운데가 되고, 앞쪽(던지는 자리)이 설치한 사람 쪽을 향한다. */
public class YutBoardItem extends BlockItem {
    public YutBoardItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public String getTranslationKey() {
        return "item.newyearcountdown.yut_set";
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.newyearcountdown.yut_set.desc").formatted(Formatting.GRAY));
        super.appendTooltip(stack, context, tooltip, type);
    }

    private static BlockState stateFor(Block block, Direction facing, int part) {
        return block.getDefaultState().with(YutBoardBlock.FACING, facing).with(YutBoardBlock.PART, part);
    }

    /** 3x3 칸이 모두 비어 있고(교체 가능) 아래가 단단한지. */
    public static boolean canPlace(World world, BlockPos center, Direction facing) {
        for (int part = 0; part < 9; part++) {
            BlockPos p = YutBoardBlock.partPos(center, facing, part);
            if (p.getY() < world.getBottomY() || p.getY() >= world.getTopY()) return false;
            if (!world.getBlockState(p).isReplaceable()) return false;
            BlockPos below = p.down();
            if (!world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) return false;
        }
        return true;
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos center = context.getBlockPos();
        PlayerEntity player = context.getPlayer();
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();

        if (!canPlace(world, center, facing)) {
            if (!world.isClient && player != null) {
                player.sendMessage(Text.translatable("yut.newyearcountdown.no_space"), true);
            }
            return ActionResult.FAIL;
        }
        ShapeContext shape = player != null ? ShapeContext.of(player) : ShapeContext.absent();
        for (int part = 0; part < 9; part++) {
            BlockPos p = YutBoardBlock.partPos(center, facing, part);
            if (!world.canPlace(stateFor(getBlock(), facing, part), p, shape)) return ActionResult.FAIL;
        }
        if (world.isClient) return ActionResult.SUCCESS;

        for (int part = 0; part < 9; part++) {
            world.setBlockState(YutBoardBlock.partPos(center, facing, part), stateFor(getBlock(), facing, part), Block.NOTIFY_ALL);
        }
        BlockSoundGroup sounds = getBlock().getDefaultState().getSoundGroup();
        world.playSound(null, center, sounds.getPlaceSound(), SoundCategory.BLOCKS, 1.0f, sounds.getPitch() * 0.9f);
        context.getStack().decrementUnlessCreative(1, player);
        return ActionResult.SUCCESS;
    }
}
