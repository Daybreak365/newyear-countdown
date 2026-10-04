package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.bell.BellLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
        int lx = part.getValue(OmikujiPartBlock.OX) - 1;
        int ly = part.getValue(OmikujiPartBlock.OY);
        int lz = part.getValue(OmikujiPartBlock.OZ) - 1;
        return partPos.subtract(BellLayout.cell(BlockPos.ZERO, part.getValue(OmikujiPartBlock.FACING), lx, ly, lz));
    }

    public static boolean canPlace(Level world, BlockPos master, Direction facing) {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    BlockPos p = BellLayout.cell(master, facing, x, y, z);
                    if (world.isOutsideBuildHeight(p)) return false;
                    if (!world.getBlockState(p).canBeReplaced()) return false;
                    if (!world.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    public static void place(Level world, BlockPos master, Direction facing) {
        world.setBlock(master, OmikujiBlocks.OMIKUJI_BOX.defaultBlockState().setValue(OmikujiBlock.FACING, facing), Block.UPDATE_ALL);
        for (int[] c : PART_CELLS) {
            BlockState part = OmikujiBlocks.OMIKUJI_PART.defaultBlockState()
                    .setValue(OmikujiPartBlock.OX, c[0] + 1)
                    .setValue(OmikujiPartBlock.OY, c[1])
                    .setValue(OmikujiPartBlock.OZ, c[2] + 1)
                    .setValue(OmikujiPartBlock.FACING, facing);
            world.setBlock(BellLayout.cell(master, facing, c[0], c[1], c[2]), part, Block.UPDATE_CLIENTS);
        }
    }

    public static void collapse(Level world, BlockPos master, Direction facing, boolean drop) {
        if (COLLAPSING.get()) return;
        COLLAPSING.set(true);
        try {
            boolean hadMaster = world.getBlockState(master).is(OmikujiBlocks.OMIKUJI_BOX);
            if (hadMaster) world.setBlock(master, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int[] c : PART_CELLS) {
                BlockPos p = BellLayout.cell(master, facing, c[0], c[1], c[2]);
                BlockState s = world.getBlockState(p);
                if (s.is(OmikujiBlocks.OMIKUJI_PART) && s.getValue(OmikujiPartBlock.FACING) == facing
                        && s.getValue(OmikujiPartBlock.OX) == c[0] + 1 && s.getValue(OmikujiPartBlock.OY) == c[1]
                        && s.getValue(OmikujiPartBlock.OZ) == c[2] + 1) {
                    world.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (hadMaster && drop && world instanceof ServerLevel) {
                Block.popResource(world, master, new ItemStack(OmikujiBlocks.OMIKUJI_ITEM));
            }
        } finally {
            COLLAPSING.set(false);
        }
    }

    public static void collapseByPlayer(Level world, BlockPos master, Direction facing, Player player) {
        collapse(world, master, facing, !player.isCreative());
    }
}
