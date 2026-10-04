package dev.newyear.countdown.bell;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 지붕 칸의 충돌 모양: 렌더링되는 계단식 지붕(0.28 블록 단차)과 같은 모양이라 걸어서 올라갈 수 있다.
 * 구조물 좌표의 상자들을 칸 안으로 잘라낸 뒤, 블록 방향에 맞춰 회전한다.
 */
public final class RoofShapes {
    // {x 반폭, z 반폭, y 아래, y 위} (지붕 중심 x = -1). BellModel 의 지붕과 같은 값이어야 한다.
    private static final double CX = -1.0;
    private static final double[][] BOXES = {
            {6.5, 2.0, 4.70, 4.94},
            {6.1, 1.8, 4.94, 5.22},
            {5.4, 1.5, 5.22, 5.50},
            {4.5, 1.2, 5.50, 5.78},
            {3.5, 0.9, 5.78, 6.06},
            {2.5, 0.55, 6.06, 6.34},
            {3.0, 0.3, 6.34, 6.52},
    };

    private static final Map<Long, VoxelShape> CACHE = new HashMap<>();

    private RoofShapes() {}

    public static synchronized VoxelShape get(int lx, int ly, int lz, Direction facing) {
        long key = (((long) (lx + 8) * 16 + ly) * 16 + (lz + 8)) * 8 + facing.get2DDataValue();
        return CACHE.computeIfAbsent(key, k -> build(lx, ly, lz, facing));
    }

    private static VoxelShape build(int lx, int ly, int lz, Direction facing) {
        VoxelShape out = Shapes.empty();
        for (double[] b : BOXES) {
            double x0 = Math.max(CX - b[0], lx - 0.5), x1 = Math.min(CX + b[0], lx + 0.5);
            double z0 = Math.max(-b[1], lz - 0.5), z1 = Math.min(b[1], lz + 0.5);
            double y0 = Math.max(b[2], ly), y1 = Math.min(b[3], ly + 1);
            if (x1 - x0 < 1e-6 || z1 - z0 < 1e-6 || y1 - y0 < 1e-6) continue;
            // 칸 안 상대 좌표 (0..1) 로 변환
            double ax = x0 - (lx - 0.5), bx = x1 - (lx - 0.5);
            double az = z0 - (lz - 0.5), bz = z1 - (lz - 0.5);
            double ay = y0 - ly, by = y1 - ly;
            // 방향에 맞춰 회전 (BellLayout.cell 과 같은 규칙, 칸 중심 기준)
            double[] r = rotate(ax, bx, az, bz, facing);
            out = Shapes.or(out, Shapes.box(r[0], ay, r[2], r[1], by, r[3]));
        }
        return out;
    }

    /** (x,z) 구간을 칸 중심(0.5) 기준으로 회전해 {minX, maxX, minZ, maxZ} 를 돌려준다. */
    private static double[] rotate(double x0, double x1, double z0, double z1, Direction f) {
        double cx0 = x0 - 0.5, cx1 = x1 - 0.5, cz0 = z0 - 0.5, cz1 = z1 - 0.5;
        double a0, a1, c0, c1;
        switch (f) {
            case SOUTH -> { a0 = cx0; a1 = cx1; c0 = cz0; c1 = cz1; }
            case WEST -> { a0 = -cz1; a1 = -cz0; c0 = cx0; c1 = cx1; }
            case NORTH -> { a0 = -cx1; a1 = -cx0; c0 = -cz1; c1 = -cz0; }
            default -> { a0 = cz0; a1 = cz1; c0 = -cx1; c1 = -cx0; } // EAST
        }
        return new double[]{a0 + 0.5, a1 + 0.5, c0 + 0.5, c1 + 0.5};
    }
}
