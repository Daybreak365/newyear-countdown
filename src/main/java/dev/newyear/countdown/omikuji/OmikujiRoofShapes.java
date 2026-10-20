package dev.newyear.countdown.omikuji;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 오미쿠지 지붕 칸의 충돌 모양: 보이는 계단식 지붕(0.14 단차)과 같다. 좌우 대칭이라 방향 회전이 필요 없다.
 */
public final class OmikujiRoofShapes {
    private static final double CX = 0.0;
    // {x 반폭, z 반폭, y 아래, y 위}: OmikujiModel 의 지붕과 같은 값
    private static final double[][] BOXES = {
            {1.50, 1.50, 3.10, 3.30},
            {1.42, 1.42, 3.30, 3.44},
            {1.14, 1.14, 3.44, 3.58},
            {0.86, 0.86, 3.58, 3.72},
            {0.58, 0.58, 3.72, 3.86},
            {0.30, 0.30, 3.86, 4.00},
    };

    private static final Map<Long, VoxelShape> CACHE = new HashMap<>();

    private OmikujiRoofShapes() {}

    public static synchronized VoxelShape get(int lx, int lz) {
        long key = (lx + 8) * 16L + (lz + 8);
        return CACHE.computeIfAbsent(key, k -> build(lx, 3, lz));
    }

    private static VoxelShape build(int lx, int ly, int lz) {
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
            out = Shapes.or(out, Shapes.box(ax, ay, az, bx, by, bz));
        }
        return out;
    }
}
