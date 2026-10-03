package dev.newyear.countdown.gacha;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
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
 * 놓을 수 있는 양 인형. 모델은 models/block/sheep_plush.json (blockstate 가 방향별로 돌린다).
 * 우클릭하면 인형 곁이 "새해를 같이 맞이할 자리"가 되어 리스폰 지점으로 등록된다.
 */
public class SheepPlushBlock extends HorizontalFacingBlock {
    public static final MapCodec<SheepPlushBlock> CODEC = createCodec(SheepPlushBlock::new);
    private static final VoxelShape SHAPE = Block.createCuboidShape(2.5, 0, 2.5, 13.5, 13, 13.5);

    public SheepPlushBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalFacingBlock> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (world instanceof ServerWorld sw && player instanceof ServerPlayerEntity sp) {
            sw.playSound(null, pos, SoundEvents.ENTITY_SHEEP_AMBIENT, SoundCategory.BLOCKS, 0.7f, 1.8f);
            sw.spawnParticles(ParticleTypes.HEART, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 4, 0.25, 0.15, 0.25, 0.02);
            BlockPos spot = standingSpot(world, pos, state.get(FACING));
            sp.setSpawnPoint(world.getRegistryKey(), spot, sp.getYaw(), true, true);
        }
        return ActionResult.SUCCESS;
    }

    /** 인형 앞/옆/위 중 서 있을 수 있는 칸을 리스폰 지점으로 쓴다(인형 몸 안에서 깨어나지 않게). */
    private static BlockPos standingSpot(World world, BlockPos pos, Direction facing) {
        Direction[] order = {facing, facing.rotateYClockwise(), facing.rotateYCounterclockwise(), facing.getOpposite()};
        for (Direction d : order) {
            BlockPos p = pos.offset(d);
            if (world.getBlockState(p).getCollisionShape(world, p).isEmpty()
                    && world.getBlockState(p.up()).getCollisionShape(world, p.up()).isEmpty()
                    && !world.getBlockState(p.down()).isAir()) {
                return p;
            }
        }
        return pos.up();
    }
}
