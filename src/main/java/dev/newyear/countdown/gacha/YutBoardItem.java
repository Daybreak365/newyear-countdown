package dev.newyear.countdown.gacha;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/** 윷 세트: 바닥에 3x3 윷판을 깐다. 클릭한 칸이 가운데가 되고, 앞쪽(던지는 자리)이 설치한 사람 쪽을 향한다. */
public class YutBoardItem extends BlockItem {
    public YutBoardItem(Block block, Properties settings) {
        super(block, settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable("item.newyearcountdown.yut_set.desc").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, tooltip, type);
    }

    private static BlockState stateFor(Block block, Direction facing, int part) {
        return block.defaultBlockState().setValue(YutBoardBlock.FACING, facing).setValue(YutBoardBlock.PART, part);
    }

    /** 3x3 칸이 모두 비어 있고(교체 가능) 아래가 단단한지. */
    public static boolean canPlace(Level world, BlockPos center, Direction facing) {
        for (int part = 0; part < 9; part++) {
            BlockPos p = YutBoardBlock.partPos(center, facing, part);
            if (p.getY() < world.getMinY() || p.getY() >= world.getMaxY() + 1) return false;
            if (!world.getBlockState(p).canBeReplaced()) return false;
            BlockPos below = p.below();
            if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) return false;
        }
        return true;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level world = context.getLevel();
        BlockPos center = context.getClickedPos();
        Player player = context.getPlayer();
        Direction facing = context.getHorizontalDirection().getOpposite();

        if (!canPlace(world, center, facing)) {
            if (!world.isClientSide() && player != null) {
                player.sendOverlayMessage(Component.translatable("yut.newyearcountdown.no_space"));
            }
            return InteractionResult.FAIL;
        }
        CollisionContext shape = player != null ? CollisionContext.of(player) : CollisionContext.empty();
        for (int part = 0; part < 9; part++) {
            BlockPos p = YutBoardBlock.partPos(center, facing, part);
            if (!world.isUnobstructed(stateFor(getBlock(), facing, part), p, shape)) return InteractionResult.FAIL;
        }
        if (world.isClientSide()) return InteractionResult.SUCCESS;

        for (int part = 0; part < 9; part++) {
            world.setBlock(YutBoardBlock.partPos(center, facing, part), stateFor(getBlock(), facing, part), Block.UPDATE_ALL);
        }
        SoundType sounds = getBlock().defaultBlockState().getSoundType();
        world.playSound(null, center, sounds.getPlaceSound(), SoundSource.BLOCKS, 1.0f, sounds.getPitch() * 0.9f);
        context.getItemInHand().consume(1, player);
        return InteractionResult.SUCCESS;
    }
}
