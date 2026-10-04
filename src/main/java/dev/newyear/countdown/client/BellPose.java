package dev.newyear.countdown.client;

import dev.newyear.countdown.bell.BellUsers;
import dev.newyear.countdown.bell.BosingakBellBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 당목을 두 손으로 잡고 미는 팔 자세. 보신각을 쓰는 플레이어(자신 포함)에게 적용된다.
 * 몸은 당목을 따라 좌우로 이동하므로, 팔은 정면으로 뻗어 당목을 잡은 채 당목의 속도에 맞춰 살짝 당기고 민다.
 */
public final class BellPose {
    private BellPose() {}

    public static void apply(HumanoidModel<?> model, Player player) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = (player == mc.player && BellSession.isActive()) ? BellSession.pos() : BellUsers.posOf(player.getUUID());
        if (pos == null || mc.level == null) return;

        float vel = 0f;
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (be instanceof BosingakBellBlockEntity bell) {
            vel = bell.strikerAngle - bell.prevStrikerAngle; // 틱당 각도 변화 (앞으로 밀면 +)
        }
        // 팔을 거의 수평(약간 위)으로 뻗어 당목을 잡고, 밀 때는 팔이 곧게 펴지고 당길 때는 접힌다
        float pitch = -1.81f + Mth.clamp(vel * 3.0f, -0.3f, 0.3f);

        model.rightArm.xRot = pitch;
        model.leftArm.xRot = pitch;
        model.rightArm.yRot = -0.05f;
        model.leftArm.yRot = 0.05f;
        model.rightArm.zRot = 0f;
        model.leftArm.zRot = 0f;
        // 다리는 걷는 모션 없이 가만히 (몸이 당목을 따라 움직일 때 다리가 떨리는 것 방지)
        model.rightLeg.xRot = 0f;
        model.leftLeg.xRot = 0f;
        model.rightLeg.yRot = 0f;
        model.leftLeg.yRot = 0f;
    }
}
