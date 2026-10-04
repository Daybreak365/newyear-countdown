package dev.newyear.countdown.bell;

import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import dev.newyear.countdown.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * 보신각 블록 엔티티: 게임 상태(사용자, 당목 각도, 종 흔들림)와 구조물 좌표계를 가진다.
 *
 * 구조물 좌표계(S): 원점은 이 블록(대종 바로 아래 칸)의 바닥 중앙, +X 는 종 쪽(화면 오른쪽), +Z 는 정면.
 * 이 블록이 구조물 전체(부속 블록들)의 마스터이다. 사용자는 한 번에 한 명이며, 서버가 권한을 관리한다.
 */
public class BosingakBellBlockEntity extends BlockEntity {
    // ---- 치수/배치 (렌더러·카메라와 공유) ----
    public static final float PILLAR_L = -6.0f;          // 왼쪽 기둥 중심 X
    public static final float PILLAR_R = 4.0f;           // 오른쪽 기둥 중심 X
    public static final float HANG_Y = 4.2f;             // 매다는 보 아래 높이
    public static final float BELL_BOTTOM_Y = 0.5f;
    public static final float ROPE_L = 2.65f;            // 당목 줄 길이
    public static final float X_LOG = -2.44f;            // 당목 중심 X (정지 상태)
    public static final float LOG_HALF_LEN = 1.2f;
    public static final float THETA_C = 0.1516f;         // 당목 끝이 종에 닿는 각도(rad)
    public static final float THETA_MIN = -0.34f;        // 최대로 뒤로 당긴 각도
    public static final float GRIP_GAP = 0.12f;          // 당목 뒤끝에서 손잡이 막대까지
    public static final float HAND_REACH = 0.72f;        // 타종자 몸 중심에서 손(손잡이)까지

    private static final int IDLE_TIMEOUT_TICKS = 200;
    private static final int AUTO_CYCLE = 70;
    private static final int AUTO_STRIKE_AT = 41;

    // ---- 서버 상태 ----
    private UUID user;
    private int idleTicks;
    private int ticks;
    private int lastStrikeTick = -100;
    private int autoRemaining;
    private int autoTick;

    // ---- 표시 상태 (클라이언트에서 사용) ----
    public float strikerAngle, prevStrikerAngle, strikerTarget;
    public float swayAmp;
    public long swayStart = -1000;
    /** 이 클라이언트의 플레이어가 직접 조작 중이면 true (서버 각도로 덮어쓰지 않음). */
    public boolean localControlled;

    public BosingakBellBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.BELL_BE, pos, state);
    }

    // ================= 좌표 변환 =================

    /** 블록이 향한 방향(정면)에 해당하는 yaw (도). 정면=로컬 +Z. */
    public float getYaw() {
        return getBlockState().getValue(BosingakBellBlock.FACING).toYRot();
    }

    /** 구조물 좌표(S) → 월드 좌표. */
    public Vec3 structToWorld(double sx, double sy, double sz) {
        double th = Math.toRadians(getYaw());
        double c = Math.cos(th), s = Math.sin(th);
        return new Vec3(worldPosition.getX() + 0.5 + sx * c - sz * s, worldPosition.getY() + sy, worldPosition.getZ() + 0.5 + sx * s + sz * c);
    }

    /** 타종자가 서는 방향(yaw): 당목 축을 따라 종 쪽(+X)을 바라본다. */
    public float standYaw() {
        return getYaw() - 90f;
    }

    /** 타종자의 X: 당목 뒤끝 손잡이를 두 손으로 잡는 거리만큼 뒤에 선다 (당목과 함께 앞뒤로 움직임). */
    public static float standX(float theta) {
        return logCenterX(theta) - LOG_HALF_LEN - GRIP_GAP - HAND_REACH;
    }

    public Vec3 standPos(float theta) {
        return structToWorld(standX(theta), 0, 0);
    }

    /** 당목이 θ 만큼 흔들렸을 때의 중심 X (구조물 좌표). */
    public static float logCenterX(float theta) {
        return X_LOG + ROPE_L * Mth.sin(theta);
    }

    // ================= 저장 / 동기화 =================

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        UUID old = user;
        user = in.getIntArray("User").filter(a -> a.length == 4).map(UUIDUtil::uuidFromIntArray).orElse(null);
        if (old != null && !old.equals(user)) BellUsers.remove(old, worldPosition);
        if (user != null) BellUsers.set(user, worldPosition);
    }

    /** 클라이언트에 현재 사용자 정보를 보낸다 (팔 자세 표시용). 디스크에는 저장하지 않는다. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        CompoundTag nbt = new CompoundTag();
        if (user != null) nbt.putIntArray("User", UUIDUtil.uuidToIntArray(user));
        return nbt;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void syncUser() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (user != null) {
            BellUsers.remove(user, worldPosition);
            if (level instanceof ServerLevel) release(true);
        }
    }

    // ================= 틱 =================

    public static void tick(Level world, BlockPos pos, BlockState state, BosingakBellBlockEntity be) {
        be.ticks++;
        if (world.isClientSide()) {
            if (!be.localControlled) {
                be.prevStrikerAngle = be.strikerAngle;
                be.strikerAngle += (be.strikerTarget - be.strikerAngle) * 0.6f;
            }
            return;
        }
        be.serverTick((ServerLevel) world);
    }

    private void serverTick(ServerLevel world) {
        if (user != null) {
            ServerPlayer p = world.getServer().getPlayerList().getPlayer(user);
            Vec3 stand = standPos(0f);
            if (p == null || p.isRemoved() || !p.isAlive() || p.distanceToSqr(stand) > 400 || ++idleTicks > IDLE_TIMEOUT_TICKS) {
                release(true);
            }
        }
        if (autoRemaining > 0 || autoTick > 0) {
            if (user != null) release(true); // 자동 타종이 우선
            stepAutoRing();
        }
    }

    // ================= 상호작용 =================

    /** 당목을 우클릭했을 때 (서버). */
    public void onUsedBy(ServerPlayer sp) {
        if (autoRemaining > 0 || autoTick > 0) {
            sp.sendOverlayMessage(Component.translatable("bell.newyearcountdown.auto"));
            return;
        }
        if (user != null && !user.equals(sp.getUUID())) {
            ServerPlayer current = sp.level().getServer().getPlayerList().getPlayer(user);
            if (current != null) {
                sp.sendOverlayMessage(Component.translatable("bell.newyearcountdown.busy"));
                return;
            }
            user = null; // 접속이 끊긴 사용자는 자리를 비운 것으로 처리
        }
        engage(sp);
    }

    private void engage(ServerPlayer sp) {
        user = sp.getUUID();
        idleTicks = 0;
        strikerAngle = 0;
        syncUser();
        // 당목 뒤끝으로 옮기고 종 쪽을 바라보게 한다
        Vec3 stand = standPos(0f);
        sp.connection.teleport(stand.x, stand.y, stand.z, standYaw(), 0f);
        ServerPlayNetworking.send(sp, new BellPackets.EngageS2C(worldPosition));
    }

    public boolean isUser(ServerPlayer p) {
        return user != null && user.equals(p.getUUID());
    }

    /** 사용 권한 해제. force=true 면 사용자에게 강제 종료를 알린다. */
    public void release(boolean force) {
        if (user == null || !(level instanceof ServerLevel sw)) return;
        ServerPlayer p = sw.getServer().getPlayerList().getPlayer(user);
        if (force && p != null) ServerPlayNetworking.send(p, new BellPackets.ForceExitS2C(worldPosition));
        BellUsers.remove(user, worldPosition);
        user = null;
        syncUser();
        setAngleAndBroadcast(0f, null);
    }

    // ================= 사용자 입력 =================

    public void onSwing(float angle) {
        idleTicks = 0;
        float a = Mth.clamp(angle, THETA_MIN - 0.05f, THETA_C + 0.05f);
        ServerPlayer except = null;
        if (user != null && level instanceof ServerLevel sw) except = sw.getServer().getPlayerList().getPlayer(user);
        setAngleAndBroadcast(a, except);
    }

    public void onStrikeRequest(float strength) {
        if (ticks - lastStrikeTick < 6) return; // 연타 방지
        lastStrikeTick = ticks;
        ring(Mth.clamp(strength, 0.05f, 1f));
    }

    private void setAngleAndBroadcast(float angle, ServerPlayer except) {
        strikerAngle = angle;
        if (!(level instanceof ServerLevel sw)) return;
        for (ServerPlayer p : PlayerLookup.tracking(sw, worldPosition)) {
            if (except != null && p.getUUID().equals(except.getUUID())) continue;
            ServerPlayNetworking.send(p, new BellPackets.AngleS2C(worldPosition, angle));
        }
    }

    // ================= 타종 =================

    /** 종을 울린다: 소리 + 근처 플레이어에게 흔들림 연출. */
    public void ring(float strength) {
        if (!(level instanceof ServerLevel sw)) return;
        Vec3 at = structToWorld(0, 1.8, 0);
        float volume = 0.35f + 0.65f * strength;
        float pitch = 0.98f + sw.getRandom().nextFloat() * 0.04f;
        sw.playSound(null, at.x, at.y, at.z, ModSounds.BELL_STRIKE, SoundSource.BLOCKS, volume, pitch);
        for (ServerPlayer p : PlayerLookup.tracking(sw, worldPosition)) {
            ServerPlayNetworking.send(p, new BellPackets.RingS2C(worldPosition, strength));
        }
    }

    /** 클라이언트: 울림 수신 시 호출 (흔들림 시작). */
    public void onRingClient(float strength) {
        swayAmp = 0.02f + 0.07f * strength;
        swayStart = level == null ? 0 : level.getGameTime();
    }

    // ================= 자동 타종 (새해 33타) =================

    public void startAutoRing(int strikes) {
        if (user != null) release(true);
        autoRemaining = strikes;
        autoTick = 0;
    }

    private void stepAutoRing() {
        autoTick++;
        int t = autoTick;
        float a;
        if (t < 30) {                      // 천천히 뒤로 당김
            a = lerp(0f, THETA_MIN * 0.9f, smooth(t / 30f));
        } else if (t < AUTO_STRIKE_AT) {   // 앞으로 가속
            float f = (t - 30) / (float) (AUTO_STRIKE_AT - 30);
            a = lerp(THETA_MIN * 0.9f, THETA_C, f * f);
        } else {                           // 반동 후 제자리
            float f = (t - AUTO_STRIKE_AT) / 18f;
            a = THETA_C * (1f - smooth(Math.min(1f, f)));
        }
        if (t == AUTO_STRIKE_AT) {
            ring(0.9f);
            autoRemaining--;
        }
        setAngleAndBroadcast(a, null);
        if (t >= AUTO_CYCLE) {
            autoTick = 0;
            if (autoRemaining <= 0) setAngleAndBroadcast(0f, null);
        }
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static float smooth(float f) {
        return f * f * (3f - 2f * f);
    }
}
