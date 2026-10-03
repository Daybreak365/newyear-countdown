package dev.newyear.countdown.omikuji;

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

/** 오미쿠지 뽑기대의 부속 블록 (보이지 않음). 어디를 우클릭해도 뽑기가 시작된다. */
public class OmikujiPartBlock extends Block {
    public static final MapCodec<OmikujiPartBlock> CODEC = createCodec(OmikujiPartBlock::new);
    public static final IntProperty OX = IntProperty.of("ox", 0, 2);
    public static final IntProperty OY = IntProperty.of("oy", 0, 3);
    public static final IntProperty OZ = IntProperty.of("oz", 0, 2);
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    public OmikujiPartBlock(Settings settings) {
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

    private static final VoxelShape DECK = Block.createCuboidShape(0, 0, 0, 16, 5, 16);

    /** 바닥은 낮은 단(걸어 올라갈 수 있음), 통과 기둥은 꽉 찬 칸, 지붕은 계단식 모양. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int x = state.get(OX) - 1, y = state.get(OY), z = state.get(OZ) - 1;
        if (y == 3) return OmikujiRoofShapes.get(x, z);
        if (y == 0) return (x == -1 && z == 1) ? VoxelShapes.fullCube() : DECK; // 새전함 칸
        if (x == 0 && z == 0) return y == 1 ? VoxelShapes.fullCube() : DECK;
        return VoxelShapes.fullCube(); // 모서리 기둥
    }

    @Override
    public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
        return new ItemStack(OmikujiBlocks.OMIKUJI_ITEM);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        BlockPos master = OmikujiLayout.masterOf(pos, state);
        if (world.getBlockEntity(master) instanceof OmikujiBlockEntity be && player instanceof ServerPlayerEntity sp) {
            be.draw(sp);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        OmikujiLayout.collapseByPlayer(world, OmikujiLayout.masterOf(pos, state), state.get(FACING), player);
        return super.onBreak(world, pos, state, player);
    }

    @Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            OmikujiLayout.collapse(world, OmikujiLayout.masterOf(pos, state), state.get(FACING), true);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
