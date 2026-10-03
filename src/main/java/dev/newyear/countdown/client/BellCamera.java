package dev.newyear.countdown.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 타종 중 카메라 연출.
 *
 * 카메라 위치/회전을 Camera.update 직후에 직접 덮어쓴다(CameraMixin).
 * 들어갈 때와 나갈 때는 시작 시점의 실제 카메라에서 목표 시점까지 부드럽게 이동(이징)해서,
 * 시점이 순간 이동하거나 떨리지 않는다. 이동 구간 동안은 원래 카메라 값과 무관하게 직접 계산한 값만 쓴다.
 */
public final class BellCamera {
    private enum Phase { IDLE, IN, ACTIVE, OUT }

    private static final double TRANSITION_SECONDS = 0.9;

    private static Phase phase = Phase.IDLE;
    private static long phaseStartNanos;
    private static boolean captured;
    private static double[] from = new double[5];      // x, y, z, yaw, pitch
    private static double[] target = new double[5];
    private static Perspective savedPerspective = Perspective.FIRST_PERSON;
    private static boolean perspectiveChanged;

    private BellCamera() {}

    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    /** 타종 시작: 목표 시점(카메라 위치와 바라볼 지점)을 정하고 이동을 시작한다. */
    public static void begin(Vec3d camPos, Vec3d lookAt) {
        Vec3d d = lookAt.subtract(camPos).normalize();
        target = new double[]{
                camPos.x, camPos.y, camPos.z,
                Math.toDegrees(Math.atan2(-d.x, d.z)),
                -Math.toDegrees(Math.asin(d.y))
        };
        MinecraftClient mc = MinecraftClient.getInstance();
        if (phase == Phase.IDLE) {
            savedPerspective = mc.options.getPerspective();
            perspectiveChanged = false;
        }
        phase = Phase.IN;
        captured = false;
        phaseStartNanos = System.nanoTime();
    }

    /** 타종 종료: 현재 시점에서 플레이어 원래 시점으로 부드럽게 돌아간다. */
    public static void end() {
        if (phase == Phase.IDLE) return;
        phase = Phase.OUT;
        captured = false;
        phaseStartNanos = System.nanoTime();
    }

    /** 타종 중 F5 로 시점이 바뀌지 않도록 3인칭(뒤)으로 고정한다. */
    public static void enforcePerspective() {
        if (phase != Phase.ACTIVE && !(phase == Phase.IN && perspectiveChanged)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK) {
            mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        }
        perspectiveChanged = true;
    }

    /** 접속 종료 등으로 즉시 초기화. */
    public static void reset() {
        if (phase != Phase.IDLE) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (perspectiveChanged && mc.options != null) mc.options.setPerspective(savedPerspective);
        }
        phase = Phase.IDLE;
        perspectiveChanged = false;
    }

    /**
     * Camera.update 이후 호출. 덮어쓸 {x, y, z, yaw, pitch} 를 돌려주거나, 건드리지 않을 땐 null.
     */
    public static double[] compute(Vec3d origPos, float origYaw, float origPitch, float tickDelta) {
        if (phase == Phase.IDLE) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        double t = (System.nanoTime() - phaseStartNanos) / 1.0e9 / TRANSITION_SECONDS;

        switch (phase) {
            case IN -> {
                if (!captured) { // 원래 카메라(1인칭 눈 위치)를 시작점으로 기록
                    from = new double[]{origPos.x, origPos.y, origPos.z, origYaw, origPitch};
                    captured = true;
                }
                double e = ease(t);
                // 카메라가 머리에서 충분히 멀어진 뒤에 3인칭으로 바꿔 플레이어 모델이 그려지게 한다
                if (e > 0.3 && !perspectiveChanged) {
                    mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                    perspectiveChanged = true;
                }
                enforcePerspective();
                if (t >= 1.0) phase = Phase.ACTIVE;
                return blend(from, target, e);
            }
            case ACTIVE -> {
                enforcePerspective();
                return target.clone();
            }
            case OUT -> {
                if (!captured) {
                    from = target.clone();
                    captured = true;
                }
                double[] dest = destination(mc, origPos, origYaw, origPitch, tickDelta);
                double e = ease(t);
                if (e > 0.7 && perspectiveChanged) { // 거의 돌아온 뒤에 원래 시점 모드로 복구
                    mc.options.setPerspective(savedPerspective);
                    perspectiveChanged = false;
                }
                if (t >= 1.0) {
                    phase = Phase.IDLE;
                    return dest;
                }
                return blend(from, dest, e);
            }
            default -> {
                return null;
            }
        }
    }

    /** 돌아갈 곳: 원래 시점이 1인칭이었다면 플레이어 눈 위치(원래 카메라 모드와 무관하게 안정적), 아니면 현재 카메라. */
    private static double[] destination(MinecraftClient mc, Vec3d origPos, float origYaw, float origPitch, float td) {
        ClientPlayerEntity p = mc.player;
        if (savedPerspective == Perspective.FIRST_PERSON && p != null) {
            Vec3d eye = p.getCameraPosVec(td);
            return new double[]{eye.x, eye.y, eye.z, p.getYaw(td), p.getPitch(td)};
        }
        return new double[]{origPos.x, origPos.y, origPos.z, origYaw, origPitch};
    }

    private static double ease(double t) {
        double c = MathHelper.clamp(t, 0.0, 1.0);
        return c * c * (3 - 2 * c);
    }

    private static double[] blend(double[] a, double[] b, double e) {
        double yawDelta = MathHelper.wrapDegrees(b[3] - a[3]);
        return new double[]{
                a[0] + (b[0] - a[0]) * e,
                a[1] + (b[1] - a[1]) * e,
                a[2] + (b[2] - a[2]) * e,
                a[3] + yawDelta * e,
                a[4] + (b[4] - a[4]) * e
        };
    }
}
