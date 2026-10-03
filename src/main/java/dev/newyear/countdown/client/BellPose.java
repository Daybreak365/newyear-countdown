package dev.newyear.countdown.client;

import dev.newyear.countdown.bell.BellUsers;
import dev.newyear.countdown.bell.BosingakBellBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * 당목을 두 손으로 잡고 미는 팔 자세. 보신각을 쓰는 플레이어(자신 포함)에게 적용된다.
 * 몸은 당목을 따라 좌우로 이동하므로, 팔은 정면으로 뻗어 당목을 잡은 채 당목의 속도에 맞춰 살짝 당기고 민다.
 */
public final class BellPose {
    private BellPose() {}

    public static void apply(PlayerEntityModel<?> model, AbstractClientPlayerEntity player) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockPos pos = (player == mc.player && BellSession.isActive()) ? BellSession.pos() : BellUsers.posOf(player.getUuid());
        if (pos == null || mc.world == null) return;

        float vel = 0f;
        BlockEntity be = mc.world.getBlockEntity(pos);
        if (be instanceof BosingakBellBlockEntity bell) {
            vel = bell.strikerAngle - bell.prevStrikerAngle; // 틱당 각도 변화 (앞으로 밀면 +)
        }
        // 팔을 거의 수평(약간 위)으로 뻗어 당목을 잡고, 밀 때는 팔이 곧게 펴지고 당길 때는 접힌다
        float pitch = -1.81f + MathHelper.clamp(vel * 3.0f, -0.3f, 0.3f);

        model.rightArm.pitch = pitch;
        model.leftArm.pitch = pitch;
        model.rightArm.yaw = -0.05f;
        model.leftArm.yaw = 0.05f;
        model.rightArm.roll = 0f;
        model.leftArm.roll = 0f;
        // 다리는 걷는 모션 없이 가만히 (몸이 당목을 따라 움직일 때 다리가 떨리는 것 방지)
        model.rightLeg.pitch = 0f;
        model.leftLeg.pitch = 0f;
        model.rightLeg.yaw = 0f;
        model.leftLeg.yaw = 0f;
        model.rightPants.copyTransform(model.rightLeg);
        model.leftPants.copyTransform(model.leftLeg);
        model.rightSleeve.copyTransform(model.rightArm);
        model.leftSleeve.copyTransform(model.leftArm);
    }
}
