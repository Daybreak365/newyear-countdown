package dev.newyear.countdown.bell;

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
 * 보신각 구조물의 칸(블록) 배치.
 *
 * 구조물은 마스터 블록(대종 바로 아래 칸) 하나와 여러 개의 부속 블록으로 이뤄지지만, 하나의 블록처럼 동작한다:
 * 설치할 때 전체 공간이 비어 있어야 하고, 어느 부분을 부수든 전체가 한 번에 사라지며 아이템은 한 번만 나온다.
 * 칸 좌표 (x, y, z) 는 마스터 칸 기준의 구조물 좌표이고, 블록 방향(facing)에 맞춰 회전된다. 정면은 +Z.
 */
public final class BellLayout {
    // 구조물이 차지하는 공간 (설치 가능 여부 검사 범위)
    public static final int MIN_X = -7, MAX_X = 5;
    public static final int MIN_Y = 0, MAX_Y = 6;
    public static final int MIN_Z = -2, MAX_Z = 2;

    private static final int PILLAR_L = (int) BosingakBellBlockEntity.PILLAR_L;
    private static final int PILLAR_R = (int) BosingakBellBlockEntity.PILLAR_R;

    /** 실제로 블록이 놓이는 칸들 (마스터 제외). 지붕도 블록으로 놓인다. */
    public static final List<int[]> PART_CELLS = new ArrayList<>();

    private static final ThreadLocal<Boolean> COLLAPSING = ThreadLocal.withInitial(() -> false);

    static {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    if (!(x == 0 && y == 0 && z == 0) && isPartCell(x, y, z)) PART_CELLS.add(new int[]{x, y, z});
                }
            }
        }
    }

    private BellLayout() {}

    private static boolean isPartCell(int x, int y, int z) {
        if (x >= -1 && x <= 1 && z >= -1 && z <= 1 && y >= 0 && y <= 3) return true;     // 대종 몸통(3x3 기둥)
        if (x == 0 && z == 0 && y == 4) return true;                                     // 걸쇠
        if (z == 0 && (x == PILLAR_L || x == PILLAR_R) && y >= 0 && y <= 4) return true; // 기둥
        if (z == 0 && y == 4 && x >= MIN_X && x <= MAX_X) return true;                   // 대들보
        if (y == 3 && (x == PILLAR_L || x == PILLAR_R) && z >= -1 && z <= 1) return true; // 앞뒤 도리
        if (isRoof(x, y, z)) return true;                                                // 지붕(천장)
        return isLane(x, y, z);                                                          // 당목이 움직이는 줄
    }

    /** 지붕 칸: 아래층(y5)은 전체, 위층(y6)은 가운데 용마루 쪽만. */
    public static boolean isRoof(int x, int y, int z) {
        if (y == 5) return true;
        return y == 6 && x >= -4 && x <= 2 && z >= -1 && z <= 1;
    }

    /** 당목이 오가는 칸: 여기를 우클릭하면 타종 모드가 시작된다. */
    public static boolean isLane(int x, int y, int z) {
        return y == 1 && z == 0 && x >= -5 && x <= -1;
    }

    /** 기둥 칸: 통과할 수 없다. */
    public static boolean isPillar(int x, int y, int z) {
        return z == 0 && (x == PILLAR_L || x == PILLAR_R) && y >= 0 && y <= 3;
    }

    // ---------------- 좌표 변환 ----------------

    /** 구조물 칸 → 월드 블록 위치 (facing 은 정면 방향, 로컬 +Z). */
    public static BlockPos cell(BlockPos master, Direction facing, int lx, int ly, int lz) {
        int dx, dz;
        switch (facing) {
            case SOUTH -> { dx = lx; dz = lz; }
            case WEST -> { dx = -lz; dz = lx; }
            case NORTH -> { dx = -lx; dz = -lz; }
            default -> { dx = lz; dz = -lx; } // EAST
        }
        return master.offset(dx, ly, dz);
    }

    public static BlockPos masterOf(BlockPos partPos, BlockState part) {
        int lx = part.getValue(BosingakPartBlock.OX) - 7;
        int ly = part.getValue(BosingakPartBlock.OY);
        int lz = part.getValue(BosingakPartBlock.OZ) - 2;
        BlockPos offset = cell(BlockPos.ZERO, part.getValue(BosingakPartBlock.FACING), lx, ly, lz);
        return partPos.subtract(offset);
    }

    // ---------------- 설치 / 철거 ----------------

    /** 전체 공간이 비어(대체 가능) 있고 높이 제한 안에 있는지. */
    public static boolean canPlace(Level world, BlockPos master, Direction facing) {
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    BlockPos p = cell(master, facing, x, y, z);
                    if (world.isOutsideBuildHeight(p)) return false;
                    if (!world.getBlockState(p).canBeReplaced()) return false;
                    if (!world.getFluidState(p).isEmpty()) return false; // 물/용암 속에는 설치 불가
                }
            }
        }
        return true;
    }

    public static void place(Level world, BlockPos master, Direction facing) {
        world.setBlock(master, ModBlocks.BOSINGAK_BELL.defaultBlockState().setValue(BosingakBellBlock.FACING, facing), Block.UPDATE_ALL);
        for (int[] c : PART_CELLS) {
            BlockState part = ModBlocks.BOSINGAK_PART.defaultBlockState()
                    .setValue(BosingakPartBlock.OX, c[0] + 7)
                    .setValue(BosingakPartBlock.OY, c[1])
                    .setValue(BosingakPartBlock.OZ, c[2] + 2)
                    .setValue(BosingakPartBlock.FACING, facing);
            world.setBlock(cell(master, facing, c[0], c[1], c[2]), part, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 구조물 전체를 한 번에 제거한다. 마스터가 아직 남아 있었다면 아이템을 한 번만 떨어뜨린다.
     * 어느 칸이 먼저 부서지든(플레이어, 폭발 등) 여기로 모인다.
     */
    public static void collapse(Level world, BlockPos master, Direction facing, boolean drop) {
        if (COLLAPSING.get()) return;
        COLLAPSING.set(true);
        try {
            boolean hadMaster = world.getBlockState(master).is(ModBlocks.BOSINGAK_BELL);
            if (hadMaster) world.setBlock(master, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int[] c : PART_CELLS) {
                BlockPos p = cell(master, facing, c[0], c[1], c[2]);
                BlockState s = world.getBlockState(p);
                if (s.is(ModBlocks.BOSINGAK_PART) && s.getValue(BosingakPartBlock.FACING) == facing
                        && s.getValue(BosingakPartBlock.OX) == c[0] + 7 && s.getValue(BosingakPartBlock.OY) == c[1]
                        && s.getValue(BosingakPartBlock.OZ) == c[2] + 2) {
                    world.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (hadMaster && drop && world instanceof ServerLevel) {
                Block.popResource(world, master, new ItemStack(ModBlocks.BOSINGAK_BELL_ITEM));
            }
        } finally {
            COLLAPSING.set(false);
        }
    }

    /** 플레이어가 부술 때: 크리에이티브면 아이템을 주지 않는다. */
    public static void collapseByPlayer(Level world, BlockPos master, Direction facing, Player player) {
        collapse(world, master, facing, !player.isCreative());
    }
}
