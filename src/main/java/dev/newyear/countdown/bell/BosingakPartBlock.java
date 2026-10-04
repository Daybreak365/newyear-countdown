package dev.newyear.countdown.bell;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 보신각 구조물의 부속 블록 (보이지 않음, 렌더링은 마스터의 렌더러가 담당).
 * 자신이 구조물의 어느 칸인지를 블록 상태(ox, oy, oz)와 방향(facing)에 담고 있어서 별도 블록 엔티티가 필요 없다.
 */
public class BosingakPartBlock extends Block {
    public static final IntegerProperty OX = IntegerProperty.create("ox", 0, 12);   // x + 7
    public static final IntegerProperty OY = IntegerProperty.create("oy", 0, 6);    // y
    public static final IntegerProperty OZ = IntegerProperty.create("oz", 0, 4);    // z + 2
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape PILLAR_SHAPE = Block.box(2, 0, 2, 14, 16, 14);

    public BosingakPartBlock(Properties settings) {
        super(settings);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OX, OY, OZ, FACING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        int x = state.getValue(OX) - 7, y = state.getValue(OY), z = state.getValue(OZ) - 2;
        if (BellLayout.isPillar(x, y, z)) return PILLAR_SHAPE;
        if (BellLayout.isRoof(x, y, z)) return RoofShapes.get(x, y, z, state.getValue(FACING));
        return Shapes.empty();
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state, boolean includeData) {
        return new ItemStack(ModBlocks.BOSINGAK_BELL_ITEM);
    }

    /** 당목 칸을 우클릭하면 마스터에게 타종 시작을 요청한다. 다른 부분은 그냥 지나간다. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (!BellLayout.isLane(state.getValue(OX) - 7, state.getValue(OY), state.getValue(OZ) - 2)) return InteractionResult.PASS;
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        BlockPos master = BellLayout.masterOf(pos, state);
        if (world.getBlockEntity(master) instanceof BosingakBellBlockEntity bell && player instanceof ServerPlayer sp) {
            bell.onUsedBy(sp);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
        BellLayout.collapseByPlayer(world, BellLayout.masterOf(pos, state), state.getValue(FACING), player);
        return super.playerWillDestroy(world, pos, state, player);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        BellLayout.collapse(world, BellLayout.masterOf(pos, state), state.getValue(FACING), true);
        super.affectNeighborsAfterRemoval(state, world, pos, moved);
    }
}
