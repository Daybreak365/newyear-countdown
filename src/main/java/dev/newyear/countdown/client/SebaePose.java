package dev.newyear.countdown.client;

import dev.newyear.countdown.sebae.Sebae;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 세배(큰절) 자세. 무릎을 꿇어 엉덩이를 낮추고(다리는 뒤로 접어 바닥에), 허리를 숙여 두 손을 앞 바닥에 짚는다.
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

    public static void apply(PlayerEntityModel<?> model, AbstractClientPlayerEntity player) {
        Long start = START.get(player.getUuid());
        if (start == null) {
            model.head.pivotZ = 0f;
            model.body.pivotZ = 0f;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        float age = (mc.world.getTime() - start) + mc.getRenderTickCounter().getTickDelta(true);
        if (age >= Sebae.TOTAL + 2) {
            START.remove(player.getUuid());
            return;
        }
        // kneel: 0 서 있음 → 1 무릎 꿇음,  bow: 0 허리 폄 → 1 엎드림
        float kneel = smooth(age / 10f) * (1f - smooth((age - 50f) / 10f));
        float bow = smooth((age - 10f) / 12f) * (1f - smooth((age - 40f) / 10f));

        float drop = 10f * kneel;                 // 엉덩이를 낮추는 양 (모델 픽셀, +y 가 아래)
        float hipY = 12f + drop;
        float a = bow * 1.25f;                    // 허리 숙인 각도
        float neckY = hipY - 12f * MathHelper.cos(a);
        float neckZ = -12f * MathHelper.sin(a);   // 앞(-z)으로

        model.body.pivotY = neckY;
        model.body.pivotZ = neckZ;
        model.body.pitch = a;
        model.body.yaw = 0f;
        model.head.pivotY = neckY;
        model.head.pivotZ = neckZ;
        model.head.pitch = a * 0.9f + 0.15f * bow;
        model.head.yaw = model.head.yaw * (1f - bow);

        // 어깨는 몸통 윗부분(목에서 2px 아래)을 따라간다
        float shY = neckY + 2f * MathHelper.cos(a);
        float shZ = neckZ + 2f * MathHelper.sin(a);
        float armPitch = MathHelper.lerp(bow, -0.15f * kneel, a - 2.35f);   // 엎드리면 두 손을 앞 바닥에
        for (int side = -1; side <= 1; side += 2) {
            var arm = side < 0 ? model.rightArm : model.leftArm;
            arm.pivotY = shY;
            arm.pivotZ = shZ;
            arm.pivotX = 5f * side;
            arm.pitch = armPitch;
            arm.yaw = -0.25f * side * bow;
            arm.roll = 0.05f * side;
        }
        // 다리: 무릎을 꿇으면 뒤로 접혀 바닥에 눕는다
        for (var leg : new net.minecraft.client.model.ModelPart[]{model.rightLeg, model.leftLeg}) {
            leg.pivotY = hipY;
            leg.pivotZ = 0f;
            leg.pitch = 1.45f * kneel;
            leg.yaw = 0f;
            leg.roll = 0f;
        }
        model.hat.copyTransform(model.head);
        model.jacket.copyTransform(model.body);
        model.rightSleeve.copyTransform(model.rightArm);
        model.leftSleeve.copyTransform(model.leftArm);
        model.rightPants.copyTransform(model.rightLeg);
        model.leftPants.copyTransform(model.leftLeg);
    }
}
