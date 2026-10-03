package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.bell.BellLayout;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 오미쿠지 뽑기대: 가로 3 x 깊이 3 x 높이 4 칸짜리 (작은 사당 모양) 구조물 하나가 블록 하나처럼 동작한다.
 * 구조물 좌표는 마스터 칸 기준이고 정면은 +Z. (회전 규칙은 보신각과 같다)
 */
public final class OmikujiLayout {
    public static final int MIN_X = -1, MAX_X = 1, MIN_Y = 0, MAX_Y = 3, MIN_Z = -1, MAX_Z = 1;

    /** 실제로 블록이 놓이는 칸들 (마스터 제외): 바닥(y=0), 가운데 뽑기통, 모서리 기둥, 지붕(y=3). */
    public static final List<int[]> PART_CELLS = new ArrayList<>();
    private static final ThreadLocal<Boolean> COLLAPSING = ThreadLocal.withInitial(() -> false);

    static {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (!(x == 0 && z == 0)) PART_CELLS.add(new int[]{x, 0, z});
            }
        }
        PART_CELLS.add(new int[]{0, 1, 0});   // 가운데 뽑기통
        PART_CELLS.add(new int[]{0, 2, 0});
        for (int sx = -1; sx <= 1; sx += 2) {  // 네 모서리 기둥
            for (int sz = -1; sz <= 1; sz += 2) {
                PART_CELLS.add(new int[]{sx, 1, sz});
                PART_CELLS.add(new int[]{sx, 2, sz});
            }
        }
        for (int x = MIN_X; x <= MAX_X; x++) { // 지붕
            for (int z = MIN_Z; z <= MAX_Z; z++) PART_CELLS.add(new int[]{x, 3, z});
        }
    }

    private OmikujiLayout() {}

    public static BlockPos masterOf(BlockPos partPos, BlockState part) {
        int lx = part.get(OmikujiPartBlock.OX) - 1;
        int ly = part.get(OmikujiPartBlock.OY);
        int lz = part.get(OmikujiPartBlock.OZ) - 1;
        return partPos.subtract(BellLayout.cell(BlockPos.ORIGIN, part.get(OmikujiPartBlock.FACING), lx, ly, lz));
    }

    public static boolean canPlace(World world, BlockPos master, Direction facing) {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    BlockPos p = BellLayout.cell(master, facing, x, y, z);
                    if (world.isOutOfHeightLimit(p)) return false;
                    if (!world.getBlockState(p).isReplaceable()) return false;
                    if (!world.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    public static void place(World world, BlockPos master, Direction facing) {
        world.setBlockState(master, OmikujiBlocks.OMIKUJI_BOX.getDefaultState().with(OmikujiBlock.FACING, facing), Block.NOTIFY_ALL);
        for (int[] c : PART_CELLS) {
            BlockState part = OmikujiBlocks.OMIKUJI_PART.getDefaultState()
                    .with(OmikujiPartBlock.OX, c[0] + 1)
                    .with(OmikujiPartBlock.OY, c[1])
                    .with(OmikujiPartBlock.OZ, c[2] + 1)
                    .with(OmikujiPartBlock.FACING, facing);
            world.setBlockState(BellLayout.cell(master, facing, c[0], c[1], c[2]), part, Block.NOTIFY_LISTENERS);
        }
    }

    public static void collapse(World world, BlockPos master, Direction facing, boolean drop) {
        if (COLLAPSING.get()) return;
        COLLAPSING.set(true);
        try {
            boolean hadMaster = world.getBlockState(master).isOf(OmikujiBlocks.OMIKUJI_BOX);
            if (hadMaster) world.setBlockState(master, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            for (int[] c : PART_CELLS) {
                BlockPos p = BellLayout.cell(master, facing, c[0], c[1], c[2]);
                BlockState s = world.getBlockState(p);
                if (s.isOf(OmikujiBlocks.OMIKUJI_PART) && s.get(OmikujiPartBlock.FACING) == facing
                        && s.get(OmikujiPartBlock.OX) == c[0] + 1 && s.get(OmikujiPartBlock.OY) == c[1]
                        && s.get(OmikujiPartBlock.OZ) == c[2] + 1) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
            if (hadMaster && drop && world instanceof ServerWorld) {
                Block.dropStack(world, master, new ItemStack(OmikujiBlocks.OMIKUJI_ITEM));
            }
        } finally {
            COLLAPSING.set(false);
        }
    }

    public static void collapseByPlayer(World world, BlockPos master, Direction facing, PlayerEntity player) {
        collapse(world, master, facing, !player.isCreative());
    }
}
