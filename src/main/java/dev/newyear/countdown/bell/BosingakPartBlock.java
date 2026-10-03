package dev.newyear.countdown.bell;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * 보신각 구조물의 부속 블록 (보이지 않음, 렌더링은 마스터의 렌더러가 담당).
 * 자신이 구조물의 어느 칸인지를 블록 상태(ox, oy, oz)와 방향(facing)에 담고 있어서 별도 블록 엔티티가 필요 없다.
 */
public class BosingakPartBlock extends Block {
    public static final MapCodec<BosingakPartBlock> CODEC = createCodec(BosingakPartBlock::new);
    public static final IntProperty OX = IntProperty.of("ox", 0, 12);   // x + 7
    public static final IntProperty OY = IntProperty.of("oy", 0, 6);    // y
    public static final IntProperty OZ = IntProperty.of("oz", 0, 4);    // z + 2
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    private static final VoxelShape PILLAR_SHAPE = Block.createCuboidShape(2, 0, 2, 14, 16, 14);

    public BosingakPartBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(OX, OY, OZ, FACING);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int x = state.get(OX) - 7, y = state.get(OY), z = state.get(OZ) - 2;
        if (BellLayout.isPillar(x, y, z)) return PILLAR_SHAPE;
        if (BellLayout.isRoof(x, y, z)) return RoofShapes.get(x, y, z, state.get(FACING));
        return VoxelShapes.empty();
    }

    @Override
    public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
        return new ItemStack(ModBlocks.BOSINGAK_BELL_ITEM);
    }

    /** 당목 칸을 우클릭하면 마스터에게 타종 시작을 요청한다. 다른 부분은 그냥 지나간다. */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!BellLayout.isLane(state.get(OX) - 7, state.get(OY), state.get(OZ) - 2)) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;
        BlockPos master = BellLayout.masterOf(pos, state);
        if (world.getBlockEntity(master) instanceof BosingakBellBlockEntity bell && player instanceof ServerPlayerEntity sp) {
            bell.onUsedBy(sp);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        BellLayout.collapseByPlayer(world, BellLayout.masterOf(pos, state), state.get(FACING), player);
        return super.onBreak(world, pos, state, player);
    }

    @Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BellLayout.collapse(world, BellLayout.masterOf(pos, state), state.get(FACING), true);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
