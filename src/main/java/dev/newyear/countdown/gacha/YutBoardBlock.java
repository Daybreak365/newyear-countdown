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
    /** 3x3 중 몇 번째 조각인지 (0~8, 판 기준 가로 x, 세로 z → z*3+x). 4 = 가운데 = 블록 엔티티가 있는 칸. */
    public static final net.minecraft.state.property.IntProperty PART = net.minecraft.state.property.IntProperty.of("part", 0, 8);
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 2, 16);

    public YutBoardBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(PART, 4));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;   // BlockWithEntity 는 기본이 INVISIBLE 이라 블록 모델을 쓰려면 바꿔야 한다
    }

    /** 3x3 설치는 YutBoardItem 이 직접 한다. */
    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return null;
    }

    private static int turns(Direction f) {
        return switch (f) {
            case SOUTH -> 0;
            case WEST -> 1;
            case NORTH -> 2;
            default -> 3;
        };
    }

    /** 판 기준 조각 위치(-1~1)를 월드 방향으로 돌린 오프셋. */
    private static int[] offsetOf(Direction facing, int part) {
        int x = part % 3 - 1, z = part / 3 - 1;
        for (int i = 0; i < turns(facing); i++) {
            int nx = -z, nz = x;
            x = nx;
            z = nz;
        }
        return new int[]{x, z};
    }

    public static BlockPos partPos(BlockPos center, Direction facing, int part) {
        int[] o = offsetOf(facing, part);
        return center.add(o[0], 0, o[1]);
    }

    public static BlockPos centerOf(BlockPos pos, BlockState state) {
        int[] o = offsetOf(state.get(FACING), state.get(PART));
        return pos.add(-o[0], 0, -o[1]);
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
        return state.get(PART) == 4 ? new YutBoardBlockEntity(pos, state) : null;
    }

    /** 어느 조각을 부숴도 판 전체가 한 번에 치워지고 아이템은 한 번만 나온다. */
    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            BlockPos center = centerOf(pos, state);
            for (int part = 0; part < 9; part++) {
                BlockPos p = partPos(center, state.get(FACING), part);
                if (p.equals(pos) || !world.getBlockState(p).isOf(this)) continue;
                // 가운데 칸만 전리품(아이템)이 있다. 다른 조각은 아무것도 떨구지 않는다
                world.breakBlock(p, part == 4 && !player.isCreative(), player);
            }
        }
        return super.onBreak(world, pos, state, player);
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
        BlockPos center = centerOf(pos, state);
        if (world.getBlockEntity(center) instanceof YutBoardBlockEntity be && player instanceof ServerPlayerEntity sp) {
            // 월드 좌표의 클릭 지점을 판 기준(앞쪽이 +z, 3x3 전체를 0~1)으로 되돌린다
            double dx = hit.getPos().x - center.getX() - 0.5;
            double dz = hit.getPos().z - center.getZ() - 0.5;
            for (int i = 0; i < turns(state.get(FACING)); i++) {
                double nx = dz, nz = -dx;
                dx = nx;
                dz = nz;
            }
            be.interact(sp, dx / 3.0 + 0.5, dz / 3.0 + 0.5);
        }
        return ActionResult.SUCCESS;
    }
}
