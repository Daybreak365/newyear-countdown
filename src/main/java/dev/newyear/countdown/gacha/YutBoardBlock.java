package dev.newyear.countdown.gacha;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

/**
 * 윷판 (바닥에 까는 얇은 블록). 판 앞쪽 붉은 천(던지는 자리)을 우클릭하면 윷가락 4개를 던지고,
 * 말판의 점(원)을 우클릭해 말을 놓거나 집어서 다른 점으로 옮긴다. 규칙/이동은 사람이 직접 한다.
 */
public class YutBoardBlock extends BlockWithEntity {
    public static final MapCodec<YutBoardBlock> CODEC = createCodec(YutBoardBlock::new);
    public static final net.minecraft.state.property.DirectionProperty FACING = HorizontalFacingBlock.FACING;
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 2, 16);

    public YutBoardBlock(Settings settings) {
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
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;   // BlockWithEntity 는 기본이 INVISIBLE 이라 블록 모델을 쓰려면 바꿔야 한다
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return SHAPE;
    }

    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return !world.getBlockState(pos.down()).isAir();
    }

    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                   WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canPlaceAt(world, pos)) return Blocks.AIR.getDefaultState();
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new YutBoardBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient) return null;
        return validateTicker(type, Souvenirs.YUT_BE, YutBoardBlockEntity::tick);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (hit.getSide() != Direction.UP) return ActionResult.SUCCESS;
        if (world.getBlockEntity(pos) instanceof YutBoardBlockEntity be && player instanceof ServerPlayerEntity sp) {
            // 월드 좌표의 클릭 지점을 판 기준(앞쪽이 +z)으로 되돌린다
            double dx = hit.getPos().x - pos.getX() - 0.5;
            double dz = hit.getPos().z - pos.getZ() - 0.5;
            int turns = switch (state.get(FACING)) {
                case SOUTH -> 0;
                case WEST -> 1;
                case NORTH -> 2;
                default -> 3;
            };
            for (int i = 0; i < turns; i++) {
                double nx = dz, nz = -dx;
                dx = nx;
                dz = nz;
            }
            be.interact(sp, dx + 0.5, dz + 0.5);
        }
        return ActionResult.SUCCESS;
    }
}
