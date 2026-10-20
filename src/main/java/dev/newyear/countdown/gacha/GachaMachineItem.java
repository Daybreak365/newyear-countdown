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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 가챠 머신 설치 아이템. 2칸 높이 블록이라 두 칸을 직접 확인하고 한꺼번에 놓는다.
 * 놓을 수 없으면 이유를 액션바로 알려 주고, 들고 있는 동안 설치될 모습이 홀로그램으로 보인다(BellPreview).
 */
public class GachaMachineItem extends BlockItem {
    public GachaMachineItem(Block block, Properties settings) {
        super(block, settings);
    }

    /** 아래 칸과 위 칸이 비어 있고(교체 가능), 아래에 단단한 블록이 있는지. 엔티티 충돌은 보지 않는다(미리보기용). */
    public static boolean canPlace(Level world, BlockPos pos) {
        if (pos.getY() < world.getMinY() || pos.getY() >= world.getMaxY()) return false;
        if (!world.getBlockState(pos).canBeReplaced() || !world.getBlockState(pos.above()).canBeReplaced()) return false;
        BlockPos below = pos.below();
        return world.getBlockState(below).isFaceSturdy(world, below, Direction.UP);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        if (!canPlace(world, pos)) {
            if (!world.isClientSide() && player != null) {
                player.sendOverlayMessage(Component.translatable("gacha.newyearcountdown.no_space"));
            }
            return InteractionResult.FAIL;
        }
        BlockState lower = GachaBlocks.GACHA_MACHINE.defaultBlockState()
                .setValue(GachaMachineBlock.FACING, context.getHorizontalDirection().getOpposite())
                .setValue(GachaMachineBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(GachaMachineBlock.HALF, DoubleBlockHalf.UPPER);
        CollisionContext shape = player != null ? CollisionContext.of(player) : CollisionContext.empty();
        if (!world.isUnobstructed(lower, pos, shape)) {   // 서 있는 사람/동물이 겹치면 안 됨
            if (!world.isClientSide() && player != null) {
                player.sendOverlayMessage(Component.translatable("gacha.newyearcountdown.blocked"));
            }
            return InteractionResult.FAIL;
        }
        if (world.isClientSide()) return InteractionResult.SUCCESS;   // 실제 설치는 서버가 하고 클라이언트엔 블록 변경으로 전달된다

        world.setBlock(pos, lower, Block.UPDATE_ALL);
        world.setBlock(pos.above(), upper, Block.UPDATE_ALL);
        SoundType sounds = lower.getSoundType();
        world.playSound(null, pos, sounds.getPlaceSound(), SoundSource.BLOCKS, (sounds.getVolume() + 1.0f) / 2.0f, sounds.getPitch() * 0.8f);
        context.getItemInHand().consume(1, player);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable("block.newyearcountdown.gacha_machine.desc").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, tooltip, type);
    }
}
