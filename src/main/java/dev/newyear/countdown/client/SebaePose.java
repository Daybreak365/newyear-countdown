package dev.newyear.countdown.client;

import dev.newyear.countdown.sebae.Sebae;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;

/**
 * 세배(큰절) 자세. 남자 큰절 순서를 따른다:
 * 공수한 두 손을 이마로 올림 → 오른발을 딛고 왼 무릎부터 꿇음 → 오른 무릎도 꿇음 → 발뒤꿈치 위에 앉음 →
 * 두 손과 팔꿈치를 바닥에 대고 이마를 손등 가까이 숙임 → 잠시 머묾 → 상체를 들고 손을 이마로 →
 * 무릎을 펴 꿇어앉은 자세로 → 오른 무릎부터 세우며 일어남 → 손을 내림.
 * 팔다리는 Limbs 가 두 마디로 나눠 두어, 무릎과 팔꿈치가 실제로 접힌다(정강이가 바닥에 눕고, 아래팔이 바닥에 닿는다).
 * 모델은 setupAnim 마다 기본 자세로 돌아가므로(겉층은 팔다리의 자식), 절하는 플레이어에게만 값을 덮어쓰면 된다.
 */
public final class SebaePose {
    /** 절을 시작한 월드 시간 (서버가 알려 준 플레이어). */
    private static final Map<UUID, Long> START = new HashMap<>();
    /** 꿇어앉았을 때 허벅지가 앞으로 기우는 각도. */
    private static final float SEAT = 1.16f;
    /** 엎드렸을 때 상체 각도. */
    private static final float BOW = 1.42f;

    private SebaePose() {}

    public static void start(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) START.put(id, mc.level.getGameTime());
    }

    public static void stop(UUID id) {
        START.remove(id);
    }

    public static void clear() {
        START.clear();
    }

    public static boolean isBowing(UUID id) {
        Long start = START.get(id);
        return start != null && Minecraft.getInstance().level != null && age(start) < Sebae.TOTAL;
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    /** (시간, 값) 쌍을 차례로 받아 키프레임 사이를 부드럽게 잇는다. */
    private static float track(float t, float... kv) {
        if (t <= kv[0]) return kv[1];
        for (int i = 0; i + 3 < kv.length; i += 2) {
            float t0 = kv[i], v0 = kv[i + 1], t1 = kv[i + 2], v1 = kv[i + 3];
            if (t <= t1) return t1 > t0 ? v0 + (v1 - v0) * smooth((t - t0) / (t1 - t0)) : v1;
        }
        return kv[kv.length - 1];
    }

    /**
     * 한 순간의 자세 (모델 픽셀, +y 가 아래, -z 가 앞).
     * hipY 엉덩이 높이, thighR/L 허벅지 각도, spine 상체 각도, raise 손을 이마로, floor 손을 바닥에, bow 엎드림 0..1.
     */
    private record Frame(float hipY, float thighR, float thighL, float spine, float raise, float floor, float bow) {}

    private static Frame frame(float t) {
        float raise = track(t, 0, 0, 10, 1, 106, 1, 116, 0);
        float floor = track(t, 40, 0, 54, 1, 76, 1, 88, 0);
        float hip0 = track(t, 10, 12, 20, 16, 104, 16, 114, 12);       // 서 있음 12 → 무릎 꿇음 16
        float plantR = track(t, 10, 1, 20, 1, 28, 0, 96, 0, 104, 1);  // 1: 오른발을 딛고 무릎을 세움
        float seat = track(t, 28, 0, 40, SEAT, 88, SEAT, 96, 0);       // 발뒤꿈치 위로 앉기
        float lean = track(t, 10, 0, 16, 0.15f, 28, 0.05f, 40, 0, 88, 0, 100, 0.2f, 112, 0);
        float bow = track(t, 40, 0, 56, 1, 74, 1, 88, 0);
        float breath = t > 58 && t < 74 ? 0.02f * Mth.sin((t - 56f) * 0.35f) : 0f;

        float k = -seat;
        float hipY = hip0 + 6f * (1f - Mth.cos(k));             // 앉을수록 무릎은 바닥에 둔 채 엉덩이가 내려감
        float planted = -(float) Math.acos(Mth.clamp((18f - hipY) / 6f, -1f, 1f));   // 정강이를 세우고 발을 딛는 허벅지 각
        float thighR = Mth.lerp(plantR, k, planted);
        return new Frame(hipY, thighR, k, lean + BOW * bow + breath, raise, floor, bow);
    }

    /** 무릎 높이에서 정강이 끝이 바닥(y=24)을 뚫지 않는 가장 작은 뒤로 접힘(절대 각도). */
    private static float shinAngle(float kneeY) {
        if (kneeY + 6f <= 24f) return 0f;
        float s = Mth.HALF_PI;
        for (int i = 0; i < 30; i++) {
            s = (float) Math.acos(Mth.clamp((24f - kneeY - 2f * Mth.sin(s)) / 6f, -1f, 1f));
        }
        return s;
    }

    private static float age(long start) {
        Minecraft mc = Minecraft.getInstance();
        return (mc.level.getGameTime() - start) + mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    private static float headPitch(Frame f) {
        return f.spine + 0.12f * f.raise * (1f - f.floor) + 0.12f * f.bow;
    }

    /**
     * 1인칭 카메라 보정: {아래로 내릴 양, 앞으로 옮길 양(블록), 아래를 보게 할 정도 0..1}. 절하지 않으면 null.
     */
    public static float[] cameraOffset(UUID id) {
        Long start = START.get(id);
        Minecraft mc = Minecraft.getInstance();
        if (start == null || mc.level == null) return null;
        float age = age(start);
        if (age >= Sebae.TOTAL) return null;
        Frame f = frame(age);
        float a = f.spine, hp = headPitch(f);
        float neckY = f.hipY - 12f * Mth.cos(a);
        float neckZ = -12f * Mth.sin(a);
        // 눈은 목에서 머리 방향으로 4px
        float eyeY = neckY - 4f * Mth.cos(hp);
        float eyeZ = neckZ - 4f * Mth.sin(hp);
        float px = 0.9375f / 16f;
        return new float[]{(eyeY + 4f) * px, -eyeZ * px, f.bow};
    }

    public static void apply(HumanoidModel<?> model, Player player) {
        Long start = START.get(player.getUUID());
        if (start == null) return;
        float age = age(start);
        if (age >= Sebae.TOTAL + 2) {
            START.remove(player.getUUID());
            return;
        }
        Frame f = frame(age);
        float a = f.spine;
        float hipY = f.hipY;
        float neckY = hipY - 12f * Mth.cos(a);
        float neckZ = -12f * Mth.sin(a);

        model.body.y = neckY;
        model.body.z = neckZ;
        model.body.xRot = a;
        model.body.yRot = 0f;
        model.body.zRot = 0f;
        model.head.y = neckY;
        model.head.z = neckZ;
        model.head.xRot = headPitch(f);
        model.head.yRot = model.head.yRot * (1f - Math.max(f.raise, f.floor));
        model.head.zRot = 0f;

        // 팔: 어깨는 몸통 윗부분(목에서 2px 아래)을 따라간다
        float shY = neckY + 2f * Mth.cos(a);
        float shZ = neckZ + 2f * Mth.sin(a);
        float up = f.raise * (1f - f.floor);
        // 바닥 짚기: 팔꿈치를 바닥(축 y=22)에 대고, 아래팔은 앞으로 눕힌다
        float d = 22f - shY;
        float upperFloor = d < 4f ? -(float) Math.acos(Mth.clamp(d / 4f, -1f, 1f)) : 0f;
        float elbowY = shY + 4f * Mth.cos(upperFloor);
        float foreFloor = elbowY < 21.9f
                ? -(float) Math.acos(Mth.clamp((22f - elbowY) / 6f, -1f, 1f))
                : -Mth.HALF_PI;
        for (int side = -1; side <= 1; side += 2) {
            ModelPart arm = side < 0 ? model.rightArm : model.leftArm;
            arm.x = 5f * side;
            arm.y = shY;
            arm.z = shZ;
            // 공수: 두 손을 이마 앞에서 포갬
            arm.xRot = Mth.lerp(f.floor, (-1.9f + a) * up, upperFloor);
            arm.yRot = side * Mth.lerp(f.floor, -0.5f * up, 0.22f);
            arm.zRot = side * Mth.lerp(f.floor, -1.0f * up, 0f);
            Limbs.bend(arm, Mth.lerp(f.floor, -0.6f * up, foreFloor - upperFloor));
        }

        // 다리: 허벅지 각도에 따라 무릎 위치가 정해지고, 정강이는 바닥을 뚫지 않게 뒤로 접힌다
        for (int side = -1; side <= 1; side += 2) {
            ModelPart leg = side < 0 ? model.rightLeg : model.leftLeg;
            float thigh = side < 0 ? f.thighR : f.thighL;
            float shin = shinAngle(hipY + 6f * Mth.cos(thigh));
            leg.x = 1.9f * side;
            leg.y = hipY;
            leg.z = 0f;
            leg.xRot = thigh;
            leg.yRot = 0f;
            leg.zRot = 0f;
            Limbs.bend(leg, shin - thigh);
        }
    }
}
