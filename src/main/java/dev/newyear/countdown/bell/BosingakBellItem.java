package dev.newyear.countdown.bell;

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

/** 보신각 설치 아이템. 구조물이 들어갈 공간 전체가 비어 있을 때만 설치되며, 설치 전에는 홀로그램으로 미리 보인다. */
public class BosingakBellItem extends BlockItem {
    public BosingakBellItem(Block block, Properties settings) {
        super(block, settings);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level world = context.getLevel();
        BlockPos master = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().getOpposite();

        if (!BellLayout.canPlace(world, master, facing)) {
            if (!world.isClientSide() && context.getPlayer() != null) {
                context.getPlayer().sendOverlayMessage(Component.translatable("bell.newyearcountdown.no_space"));
            }
            return InteractionResult.FAIL;
        }
        if (world.isClientSide()) return InteractionResult.SUCCESS; // 실제 설치는 서버가 하고 클라이언트엔 블록 변경으로 전달된다

        BellLayout.place(world, master, facing);
        world.playSound(null, master, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0f, 0.8f);
        context.getItemInHand().consume(1, context.getPlayer());
        return InteractionResult.SUCCESS;
    }
}
