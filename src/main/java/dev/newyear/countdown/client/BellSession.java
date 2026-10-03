package dev.newyear.countdown.client;

import dev.newyear.countdown.bell.BellPackets;
import dev.newyear.countdown.bell.BosingakBellBlockEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 타종 조작 세션 (클라이언트).
 *
 * - 입력: 마우스 좌우 이동량(EntityMixin 이 가로챔)을 당목(진자)에 힘으로 준다. 플레이어 시선은 건드리지 않는다.
 * - 이동: 플레이어는 당목 바로 뒤에서 당목을 잡은 채 함께 좌우로 움직인다(팔 자세는 BellPose).
 * - 카메라: BellCamera 가 보신각 정면의 고정 3인칭 시점으로 부드럽게 전환한다.
 */
public final class BellSession {
    private static final float GRAVITY_OVER_L = 9.8f / BosingakBellBlockEntity.ROPE_L;
    private static final float DAMPING = 0.12f;
    private static final float MOUSE_GAIN = 0.11f;   // 마우스 1도당 각속도(rad/s) 증가량
    private static final float MAX_OMEGA = 4.0f;
    private static final int SUBSTEPS = 5;
    private static final float DT = 0.05f / SUBSTEPS;

    private static boolean active;
    private static BlockPos pos;
    private static float theta, omega;
    private static double lookX;
    private static float lockYaw;
    private static int cooldown;
    private static int strikes;
    private static boolean armed = true;
    private static float backLimit = BosingakBellBlockEntity.THETA_MIN;
    private static int limitRefresh;
    private static boolean holdBack;

    private BellSession() {}

    public static boolean isActive() {
        return active;
    }

    public static BlockPos pos() {
        return pos;
    }

    /** EntityMixin 에서 호출: 이번 틱에 누적된 마우스 좌우 이동량(도). 오른쪽이 +. */
    public static void addLook(double degrees) {
        lookX += degrees;
    }

    private static BosingakBellBlockEntity bell(MinecraftClient mc) {
        if (mc.world == null || pos == null) return null;
        BlockEntity be = mc.world.getBlockEntity(pos);
        return be instanceof BosingakBellBlockEntity b ? b : null;
    }

    public static void start(MinecraftClient mc, BlockPos p) {
        ClientPlayerEntity player = mc.player;
        if (mc.world == null || player == null) return;
        if (!(mc.world.getBlockEntity(p) instanceof BosingakBellBlockEntity b)) return;

        active = true;
        pos = p.toImmutable();
        theta = 0;
        omega = 0;
        cooldown = 0;
        strikes = 0;
        limitRefresh = 0;
        backLimit = BosingakBellBlockEntity.THETA_MIN;
        armed = true;
        lookX = 0;
        lockYaw = b.standYaw(); // 당목 축(+X) 방향: 통나무 뒤에서 앞으로 미는 자세
        b.localControlled = true;
        b.strikerAngle = b.prevStrikerAngle = 0f;
        applyFacing(player);

        // 보신각 정면, 종과 당목 사이를 바라보는 고정 카메라
        BellCamera.begin(b.structToWorld(-1.9, 2.6, 8.4), b.structToWorld(-1.9, 1.5, 0.0));
    }

    private static void applyFacing(ClientPlayerEntity p) {
        p.setYaw(lockYaw);
        p.setPitch(0f);
        p.prevYaw = lockYaw;
        p.prevPitch = 0f;
        p.setHeadYaw(lockYaw);
        p.setBodyYaw(lockYaw);
        p.prevHeadYaw = lockYaw;
        p.prevBodyYaw = lockYaw;
    }

    public static void stop(MinecraftClient mc, boolean notifyServer) {
        if (!active) return;
        active = false;
        BosingakBellBlockEntity b = bell(mc);
        if (b != null) {
            b.localControlled = false;
            b.strikerTarget = 0f;
        }
        BellCamera.end();
        if (notifyServer && ClientPlayNetworking.canSend(BellPackets.LeaveC2S.ID)) {
            ClientPlayNetworking.send(new BellPackets.LeaveC2S(pos));
        }
    }

    /** 매 클라이언트 틱 시작 시 호출: 입력 처리와 진자 물리. */
    public static void tick(MinecraftClient mc) {
        if (!active) return;
        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        BosingakBellBlockEntity b = bell(mc);
        if (player == null || world == null || b == null || mc.currentScreen != null || !player.isAlive()
                || player.squaredDistanceTo(b.standPos(0f)) > 400) {
            stop(mc, true);
            return;
        }
        GameOptions o = mc.options;
        if (o.sneakKey.isPressed()) { // Shift: 나가기
            stop(mc, true);
            return;
        }

        // ---- 이동/공격/설치 입력 차단 ----
        for (KeyBinding k : new KeyBinding[]{o.forwardKey, o.backKey, o.leftKey, o.rightKey, o.jumpKey, o.sprintKey, o.attackKey, o.useKey}) {
            k.setPressed(false);
            while (k.wasPressed()) { /* 쌓인 입력 비우기 */ }
        }
        applyFacing(player);
        BellCamera.enforcePerspective(); // F5 로 시점이 바뀌는 것 방지

        // ---- 진자 물리 ----
        float dx = (float) lookX;
        lookX = 0;
        omega = MathHelper.clamp(omega + dx * MOUSE_GAIN, -MAX_OMEGA, MAX_OMEGA);
        float prev = theta;
        if (cooldown > 0) cooldown--;
        // 뒤쪽 한계(줄 한계 또는 벽 앞)는 미리 계산해 둔다 (프레임마다 충돌 검사를 하면 경계에서 떨린다)
        if (--limitRefresh <= 0) {
            limitRefresh = 10;
            backLimit = computeBackLimit(player, world, b);
        }
        boolean atBack = theta <= backLimit + 1e-3f;
        // 뒤로 당긴 채 가만히 있으면 붙잡고 있는다. 오른쪽으로 밀면 놓아준다.
        holdBack = atBack && dx <= 0.05f;
        if (holdBack) {
            theta = backLimit;
            omega = 0f;
        }
        for (int i = 0; i < SUBSTEPS && !holdBack; i++) {
            float alpha = -GRAVITY_OVER_L * MathHelper.sin(theta) - DAMPING * omega;
            omega += alpha * DT;
            theta += omega * DT;
            if (theta < backLimit) {
                theta = backLimit;
                omega = Math.max(0f, omega);
            }
            if (theta < -0.05f) armed = true;                  // 충분히 뒤로 당겼다 → 다음 타종 준비
            if (theta >= BosingakBellBlockEntity.THETA_C) {   // 당목이 종에 닿음
                theta = BosingakBellBlockEntity.THETA_C;
                if (armed && omega > 0.35f && cooldown == 0) {
                    float strength = MathHelper.clamp((omega - 0.35f) / 2.4f, 0.08f, 1.0f);
                    if (ClientPlayNetworking.canSend(BellPackets.StrikeC2S.ID)) {
                        ClientPlayNetworking.send(new BellPackets.StrikeC2S(pos, strength));
                    }
                    strikes++;
                    cooldown = 6;
                    armed = false;
                    omega = -Math.abs(omega) * 0.28f; // 반동
                } else {
                    omega = Math.min(omega, 0f);      // 이미 친 뒤 계속 밀어도 다시 치지 않고 그냥 닿아 있는다
                }
            }
        }

        // ---- 표시/전송 ----
        b.prevStrikerAngle = prev;
        b.strikerAngle = theta;
        if (ClientPlayNetworking.canSend(BellPackets.SwingC2S.ID)) {
            ClientPlayNetworking.send(new BellPackets.SwingC2S(pos, theta));
        }
    }

    /** 0 에서 뒤쪽으로 한 칸씩 나아가며 막히기 직전 각도를 찾는다 (여유 0.03rad). */
    private static float computeBackLimit(ClientPlayerEntity player, ClientWorld world, BosingakBellBlockEntity b) {
        float th = 0f;
        while (th > BosingakBellBlockEntity.THETA_MIN) {
            float next = th - 0.01f;
            if (blocked(player, world, b, next - 0.03f)) break;
            th = next;
        }
        return Math.max(th, BosingakBellBlockEntity.THETA_MIN);
    }

    private static boolean blocked(ClientPlayerEntity player, ClientWorld world, BosingakBellBlockEntity b, float th) {
        Vec3d w = b.standPos(th);
        var box = player.getDimensions(player.getPose()).getBoxAt(w.x, player.getY(), w.z).contract(0.02, 0.0, 0.02);
        return !world.isSpaceEmpty(player, box);
    }

    /** 틱 끝에 호출: 플레이어가 당목을 잡은 채 함께 움직이도록 위치를 맞춘다 (엔티티 틱 이후라 보간이 매끄럽다). */
    public static void follow(MinecraftClient mc) {
        if (!active) return;
        ClientPlayerEntity player = mc.player;
        BosingakBellBlockEntity b = bell(mc);
        if (player == null || b == null) return;

        Vec3d want = b.standPos(theta);
        Vec3d cur = player.getPos();
        if (Math.hypot(cur.x - want.x, cur.z - want.z) > 3.5) return; // 서버 이동(텔레포트)이 아직 안 끝남
        player.setPosition(want.x, cur.y, want.z); // 높이는 유지
        Vec3d v = player.getVelocity();
        player.setVelocity(0, v.y, 0);
    }

    /** 조작 안내 HUD. */
    public static void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        var tr = mc.textRenderer;
        ctx.drawCenteredTextWithShadow(tr, Text.translatable("bell.newyearcountdown.hint"), w / 2, h - 62, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(tr, Text.translatable("bell.newyearcountdown.exit"), w / 2, h - 50, 0xFFA0A4B0);

        // 당김 게이지: 왼쪽으로 당길수록 왼쪽, 종에 닿는 지점이 오른쪽 끝
        int bw = 140, x0 = w / 2 - bw / 2, y0 = h - 36;
        ctx.fill(x0 - 1, y0 - 1, x0 + bw + 1, y0 + 5, 0xFF000000);
        ctx.fill(x0, y0, x0 + bw, y0 + 4, 0xAA202830);
        float f = (theta - BosingakBellBlockEntity.THETA_MIN) / (BosingakBellBlockEntity.THETA_C - BosingakBellBlockEntity.THETA_MIN);
        int mx = x0 + Math.round(MathHelper.clamp(f, 0f, 1f) * (bw - 3));
        ctx.fill(mx, y0 - 2, mx + 3, y0 + 6, 0xFFFFC857);
        ctx.fill(x0 + bw - 2, y0, x0 + bw, y0 + 4, 0xFFFF6B6B);
        ctx.drawCenteredTextWithShadow(tr, Text.translatable("bell.newyearcountdown.count", strikes), w / 2, h - 24, 0xFFFFC857);
    }
}
