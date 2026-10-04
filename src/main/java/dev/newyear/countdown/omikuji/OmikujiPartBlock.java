package dev.newyear.countdown.omikuji;

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

/** 오미쿠지 뽑기대의 부속 블록 (보이지 않음). 어디를 우클릭해도 뽑기가 시작된다. */
public class OmikujiPartBlock extends Block {
    public static final IntegerProperty OX = IntegerProperty.create("ox", 0, 2);
    public static final IntegerProperty OY = IntegerProperty.create("oy", 0, 3);
    public static final IntegerProperty OZ = IntegerProperty.create("oz", 0, 2);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    public OmikujiPartBlock(Properties settings) {
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

    private static final VoxelShape DECK = Block.box(0, 0, 0, 16, 5, 16);

    /** 바닥은 낮은 단(걸어 올라갈 수 있음), 통과 기둥은 꽉 찬 칸, 지붕은 계단식 모양. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        int x = state.getValue(OX) - 1, y = state.getValue(OY), z = state.getValue(OZ) - 1;
        if (y == 3) return OmikujiRoofShapes.get(x, z);
        if (y == 0) return (x == -1 && z == 1) ? Shapes.block() : DECK; // 새전함 칸
        if (x == 0 && z == 0) return y == 1 ? Shapes.block() : DECK;
        return Shapes.block(); // 모서리 기둥
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state, boolean includeData) {
        return new ItemStack(OmikujiBlocks.OMIKUJI_ITEM);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        BlockPos master = OmikujiLayout.masterOf(pos, state);
        if (player instanceof ServerPlayer sp && OmikujiAdmin.isButton(master, state.getValue(FACING), hit.getLocation())) {
            OmikujiAdmin.open(sp);   // 뒤편의 관리자 버튼
            return InteractionResult.SUCCESS;
        }
        if (world.getBlockEntity(master) instanceof OmikujiBlockEntity be && player instanceof ServerPlayer sp) {
            be.draw(sp);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
        OmikujiLayout.collapseByPlayer(world, OmikujiLayout.masterOf(pos, state), state.getValue(FACING), player);
        return super.playerWillDestroy(world, pos, state, player);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        OmikujiLayout.collapse(world, OmikujiLayout.masterOf(pos, state), state.getValue(FACING), true);
        super.affectNeighborsAfterRemoval(state, world, pos, moved);
    }
}
