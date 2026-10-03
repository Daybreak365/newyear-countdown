package dev.newyear.countdown.client;

import dev.newyear.countdown.sebae.Sebae;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 세배(큰절) 자세. 남자 큰절 순서를 따른다:
 * 공수한 두 손을 이마로 올림 → 왼 무릎, 오른 무릎 순으로 꿇음 → 발뒤꿈치 위에 앉음 →
 * 두 손을 앞 바닥에 짚고 이마를 손등 가까이 숙임 → 잠시 머묾 → 상체를 들고 손을 이마로 →
 * 오른 무릎부터 세우며 일어남 → 손을 내림.
 * 다리는 한 마디라서, 꿇은 다리는 엉덩이에서 앞으로 눕힌 짧은 허벅지(정강이는 그 밑에 접혀 있다고 본다)로,
 * 아직 서 있는 다리는 발이 바닥에 닿는 길이로 줄여 무릎이 굽은 것처럼 보이게 한다. 엎드릴 때 상체는 허벅지 위로 접힌다.
 * 플레이어 모델은 모든 플레이어가 함께 쓰므로, 절하지 않는 플레이어는 우리가 건드린 값을 기본값으로 되돌린다.
 */
public final class SebaePose {
    /** 절을 시작한 월드 시간 (서버가 알려 준 플레이어). */
    private static final Map<UUID, Long> START = new HashMap<>();

    private SebaePose() {}

    public static void start(UUID id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) START.put(id, mc.world.getTime());
    }

    public static void stop(UUID id) {
        START.remove(id);
    }

    public static void clear() {
        START.clear();
    }

    private static float smooth(float x) {
        x = MathHelper.clamp(x, 0f, 1f);
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

    /** 한 순간의 자세 값 (모델 픽셀, +y 가 아래, -z 가 앞). */
    private record Frame(float raise, float floor, float drop, float leftKneel, float rightKneel, float spine, float headExtra) {}

    private static Frame frame(float t) {
        float raise = track(t, 0, 0, 10, 1, 106, 1, 116, 0);          // 공수한 손을 이마로
        float floor = track(t, 40, 0, 54, 1, 76, 1, 88, 0);           // 두 손을 바닥에
        float drop = track(t, 10, 0, 22, 5, 32, 9, 88, 9, 96, 5, 108, 0);   // 엉덩이를 낮춰 발뒤꿈치 위에 앉음
        float leftKneel = track(t, 12, 0, 24, 1, 94, 1, 106, 0);      // 왼 무릎 먼저 꿇고, 일어날 땐 나중에
        float rightKneel = track(t, 20, 0, 32, 1, 88, 1, 96, 0);
        float lean = track(t, 10, 0, 18, 0.18f, 32, 0.05f, 40, 0, 88, 0, 98, 0.22f, 110, 0);
        float bow = track(t, 40, 0, 56, 1.32f, 74, 1.32f, 88, 0);
        float breath = t > 58 && t < 74 ? 0.025f * MathHelper.sin((t - 56f) * 0.35f) : 0f;
        float headExtra = 0.18f * raise * (1f - floor) + 0.08f * floor;
        return new Frame(raise, floor, drop, leftKneel, rightKneel, lean + bow + breath, headExtra);
    }

    private static float age(long start) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (mc.world.getTime() - start) + mc.getRenderTickCounter().getTickDelta(true);
    }

    /**
     * 1인칭 카메라 보정: {아래로 내릴 양, 앞으로 옮길 양(블록), 아래를 보게 할 정도 0..1}. 절하지 않으면 null.
     */
    public static float[] cameraOffset(UUID id) {
        Long start = START.get(id);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (start == null || mc.world == null) return null;
        float age = age(start);
        if (age >= Sebae.TOTAL) return null;
        Frame f = frame(age);
        float a = f.spine, hp = a + f.headExtra;
        float neckY = 12f + f.drop - 12f * MathHelper.cos(a);
        float neckZ = -12f * MathHelper.sin(a);
        // 눈은 목에서 머리 방향으로 4px
        float eyeY = neckY - 4f * MathHelper.cos(hp);
        float eyeZ = neckZ - 4f * MathHelper.sin(hp);
        float px = 0.9375f / 16f;
        return new float[]{(eyeY + 4f) * px, -eyeZ * px, MathHelper.clamp(a / 1.32f, 0f, 1f)};
    }

    public static void apply(PlayerEntityModel<?> model, AbstractClientPlayerEntity player) {
        Long start = START.get(player.getUuid());
        if (start == null) {
            model.head.pivotZ = 0f;
            model.body.pivotZ = 0f;
            for (ModelPart leg : new ModelPart[]{model.rightLeg, model.leftLeg, model.rightPants, model.leftPants}) {
                leg.yScale = 1f;
            }
            return;
        }
        float age = age(start);
        if (age >= Sebae.TOTAL + 2) {
            START.remove(player.getUuid());
            return;
        }
        Frame f = frame(age);
        float a = f.spine;
        float hipY = 12f + f.drop;
        float neckY = hipY - 12f * MathHelper.cos(a);
        float neckZ = -12f * MathHelper.sin(a);

        model.body.pivotY = neckY;
        model.body.pivotZ = neckZ;
        model.body.pitch = a;
        model.body.yaw = 0f;
        model.head.pivotY = neckY;
        model.head.pivotZ = neckZ;
        model.head.pitch = a + f.headExtra;
        model.head.yaw = model.head.yaw * (1f - Math.max(f.raise, f.floor));
        model.head.roll = 0f;

        // 어깨는 몸통 윗부분(목에서 2px 아래)을 따라간다
        float shY = neckY + 2f * MathHelper.cos(a);
        float shZ = neckZ + 2f * MathHelper.sin(a);
        // 바닥(y=24)에 손이 닿는 팔 각도
        float reach = (float) Math.acos(MathHelper.clamp((24f - shY) / 10.5f, 0f, 1f));
        for (int side = -1; side <= 1; side += 2) {
            ModelPart arm = side < 0 ? model.rightArm : model.leftArm;
            float up = (-2.3f + a) * f.raise;                          // 손을 이마 앞에서 맞잡음
            arm.pivotX = 5f * side;
            arm.pivotY = shY;
            arm.pivotZ = shZ;
            arm.pitch = MathHelper.lerp(f.floor, up, -reach);
            arm.yaw = side * (0.38f * f.raise * (1f - f.floor) + 0.28f * f.floor);
            arm.roll = side * (-0.22f * f.raise * (1f - f.floor) + 0.10f * f.floor);
        }

        // 다리: 꿇은 다리는 앞으로 눕힌 허벅지(9px), 서 있는 다리는 발이 바닥에 닿도록 줄인다
        for (int side = -1; side <= 1; side += 2) {
            ModelPart leg = side < 0 ? model.rightLeg : model.leftLeg;
            float w = side < 0 ? f.rightKneel : f.leftKneel;
            float pitch = -1.45f * w;
            float c = Math.max(MathHelper.cos(pitch), 0.01f);
            leg.pivotX = 1.9f * side;
            leg.pivotY = hipY;
            leg.pivotZ = 0f;
            leg.pitch = pitch;
            leg.yaw = 0f;
            leg.roll = 0f;
            leg.yScale = MathHelper.clamp((24f - hipY) / c / 12f, 0.25f, MathHelper.lerp(w, 1f, 0.75f));
        }
        model.hat.copyTransform(model.head);
        model.jacket.copyTransform(model.body);
        model.rightSleeve.copyTransform(model.rightArm);
        model.leftSleeve.copyTransform(model.leftArm);
        model.rightPants.copyTransform(model.rightLeg);
        model.leftPants.copyTransform(model.leftLeg);
        model.rightPants.yScale = model.rightLeg.yScale;
        model.leftPants.yScale = model.leftLeg.yScale;
    }
}
