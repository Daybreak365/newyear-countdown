package dev.newyear.countdown.gacha;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 윷판 (바닥에 까는 얇은 블록). 판 앞쪽 붉은 천(던지는 자리)을 우클릭하면 윷가락 4개를 던지고,
 * 말판의 점(원)을 우클릭해 말을 놓거나 집어서 다른 점으로 옮긴다. 규칙/이동은 사람이 직접 한다.
 */
public class YutBoardBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    /** 3x3 중 몇 번째 조각인지 (0~8, 판 기준 가로 x, 세로 z → z*3+x). 4 = 가운데 = 블록 엔티티가 있는 칸. */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty PART = net.minecraft.world.level.block.state.properties.IntegerProperty.create("part", 0, 8);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);

    public YutBoardBlock(Properties settings) {
        super(settings);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(PART, 4));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;   // BlockWithEntity 는 기본이 INVISIBLE 이라 블록 모델을 쓰려면 바꿔야 한다
    }

    /** 3x3 설치는 YutBoardItem 이 직접 한다. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
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
        return center.offset(o[0], 0, o[1]);
    }

    public static BlockPos centerOf(BlockPos pos, BlockState state) {
        int[] o = offsetOf(state.getValue(FACING), state.getValue(PART));
        return pos.offset(-o[0], 0, -o[1]);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
        return !world.getBlockState(pos.below()).isAir();
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader world, ScheduledTickAccess ticks, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction == Direction.DOWN && !state.canSurvive(world, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, world, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == 4 ? new YutBoardBlockEntity(pos, state) : null;
    }

    /** 어느 조각을 부숴도 판 전체가 한 번에 치워지고 아이템은 한 번만 나온다. */
    @Override
    public BlockState playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
        if (!world.isClientSide()) {
            BlockPos center = centerOf(pos, state);
            for (int part = 0; part < 9; part++) {
                BlockPos p = partPos(center, state.getValue(FACING), part);
                if (p.equals(pos) || !world.getBlockState(p).is(this)) continue;
                // 가운데 칸만 전리품(아이템)이 있다. 다른 조각은 아무것도 떨구지 않는다
                world.destroyBlock(p, part == 4 && !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(world, pos, state, player);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (world.isClientSide()) return null;
        return createTickerHelper(type, Souvenirs.YUT_BE, YutBoardBlockEntity::tick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        if (hit.getDirection() != Direction.UP) return InteractionResult.SUCCESS;
        BlockPos center = centerOf(pos, state);
        if (world.getBlockEntity(center) instanceof YutBoardBlockEntity be && player instanceof ServerPlayer sp) {
            // 월드 좌표의 클릭 지점을 판 기준(앞쪽이 +z, 3x3 전체를 0~1)으로 되돌린다
            double dx = hit.getLocation().x - center.getX() - 0.5;
            double dz = hit.getLocation().z - center.getZ() - 0.5;
            for (int i = 0; i < turns(state.getValue(FACING)); i++) {
                double nx = dz, nz = -dx;
                dx = nx;
                dz = nz;
            }
            be.interact(sp, dx / 3.0 + 0.5, dz / 3.0 + 0.5);
        }
        return InteractionResult.SUCCESS;
    }
}
