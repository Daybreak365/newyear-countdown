package dev.newyear.countdown.bell;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 보신각 마스터 블록(대종 바로 아래 칸). 모습은 블록 엔티티 렌더러가 구조물 전체를 그리고,
 * 이 블록 자체는 보이지 않는다. 부속 블록들(BosingakPartBlock)과 함께 하나의 블록처럼 동작한다.
 * 정면(facing)은 설치한 사람 쪽을 향한다.
 */
public class BosingakBellBlock extends BlockWithEntity {
    public static final MapCodec<BosingakBellBlock> CODEC = createCodec(BosingakBellBlock::new);
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    public BosingakBellBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new BosingakBellBlockEntity(pos, state);
    }

    // BlockWithEntity 의 기본 렌더 타입이 INVISIBLE 이라 따로 지정하지 않는다 (모든 모습은 렌더러가 그림).

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public <T extends BlockEntity> net.minecraft.block.entity.BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return validateTicker(type, ModBlocks.BELL_BE, BosingakBellBlockEntity::tick);
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        BellLayout.collapseByPlayer(world, pos, state.get(FACING), player);
        return super.onBreak(world, pos, state, player);
    }

    @Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BellLayout.collapse(world, pos, state.get(FACING), true);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
