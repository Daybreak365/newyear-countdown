package dev.newyear.countdown.gacha;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
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

/**
 * 가챠 머신 설치 아이템. 2칸 높이 블록이라 두 칸을 직접 확인하고 한꺼번에 놓는다.
 * 놓을 수 없으면 이유를 액션바로 알려 주고, 들고 있는 동안 설치될 모습이 홀로그램으로 보인다(BellPreview).
 */
public class GachaMachineItem extends BlockItem {
    public GachaMachineItem(Block block, Settings settings) {
        super(block, settings);
    }

    /** 아래 칸과 위 칸이 비어 있고(교체 가능), 아래에 단단한 블록이 있는지. 엔티티 충돌은 보지 않는다(미리보기용). */
    public static boolean canPlace(World world, BlockPos pos) {
        if (pos.getY() < world.getBottomY() || pos.getY() >= world.getTopY() - 1) return false;
        if (!world.getBlockState(pos).isReplaceable() || !world.getBlockState(pos.up()).isReplaceable()) return false;
        BlockPos below = pos.down();
        return world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP);
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        PlayerEntity player = context.getPlayer();

        if (!canPlace(world, pos)) {
            if (!world.isClient && player != null) {
                player.sendMessage(Text.translatable("gacha.newyearcountdown.no_space"), true);
            }
            return ActionResult.FAIL;
        }
        BlockState lower = GachaBlocks.GACHA_MACHINE.getDefaultState()
                .with(GachaMachineBlock.FACING, context.getHorizontalPlayerFacing().getOpposite())
                .with(GachaMachineBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.with(GachaMachineBlock.HALF, DoubleBlockHalf.UPPER);
        ShapeContext shape = player != null ? ShapeContext.of(player) : ShapeContext.absent();
        if (!world.canPlace(lower, pos, shape)) {   // 서 있는 사람/동물이 겹치면 안 됨
            if (!world.isClient && player != null) {
                player.sendMessage(Text.translatable("gacha.newyearcountdown.blocked"), true);
            }
            return ActionResult.FAIL;
        }
        if (world.isClient) return ActionResult.SUCCESS;   // 실제 설치는 서버가 하고 클라이언트엔 블록 변경으로 전달된다

        world.setBlockState(pos, lower, Block.NOTIFY_ALL);
        world.setBlockState(pos.up(), upper, Block.NOTIFY_ALL);
        BlockSoundGroup sounds = lower.getSoundGroup();
        world.playSound(null, pos, sounds.getPlaceSound(), SoundCategory.BLOCKS, (sounds.getVolume() + 1.0f) / 2.0f, sounds.getPitch() * 0.8f);
        context.getStack().decrementUnlessCreative(1, player);
        return ActionResult.SUCCESS;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("block.newyearcountdown.gacha_machine.desc").formatted(Formatting.GRAY));
        super.appendTooltip(stack, context, tooltip, type);
    }
}
