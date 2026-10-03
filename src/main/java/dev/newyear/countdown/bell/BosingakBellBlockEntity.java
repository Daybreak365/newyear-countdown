package dev.newyear.countdown.bell;

import dev.newyear.countdown.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

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
        return getCachedState().get(BosingakBellBlock.FACING).asRotation();
    }

    /** 구조물 좌표(S) → 월드 좌표. */
    public Vec3d structToWorld(double sx, double sy, double sz) {
        double th = Math.toRadians(getYaw());
        double c = Math.cos(th), s = Math.sin(th);
        return new Vec3d(pos.getX() + 0.5 + sx * c - sz * s, pos.getY() + sy, pos.getZ() + 0.5 + sx * s + sz * c);
    }

    /** 타종자가 서는 방향(yaw): 당목 축을 따라 종 쪽(+X)을 바라본다. */
    public float standYaw() {
        return getYaw() - 90f;
    }

    /** 타종자의 X: 당목 뒤끝 손잡이를 두 손으로 잡는 거리만큼 뒤에 선다 (당목과 함께 앞뒤로 움직임). */
    public static float standX(float theta) {
        return logCenterX(theta) - LOG_HALF_LEN - GRIP_GAP - HAND_REACH;
    }

    public Vec3d standPos(float theta) {
        return structToWorld(standX(theta), 0, 0);
    }

    /** 당목이 θ 만큼 흔들렸을 때의 중심 X (구조물 좌표). */
    public static float logCenterX(float theta) {
        return X_LOG + ROPE_L * MathHelper.sin(theta);
    }

    // ================= 저장 / 동기화 =================

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        UUID old = user;
        user = nbt.containsUuid("User") ? nbt.getUuid("User") : null;
        if (old != null && !old.equals(user)) BellUsers.remove(old, pos);
        if (user != null) BellUsers.set(user, pos);
    }

    /** 클라이언트에 현재 사용자 정보를 보낸다 (팔 자세 표시용). 디스크에는 저장하지 않는다. */
    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = new NbtCompound();
        if (user != null) nbt.putUuid("User", user);
        return nbt;
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    private void syncUser() {
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_LISTENERS);
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (user != null) {
            BellUsers.remove(user, pos);
            if (world instanceof ServerWorld) release(true);
        }
    }

    // ================= 틱 =================

    public static void tick(World world, BlockPos pos, BlockState state, BosingakBellBlockEntity be) {
        be.ticks++;
        if (world.isClient) {
            if (!be.localControlled) {
                be.prevStrikerAngle = be.strikerAngle;
                be.strikerAngle += (be.strikerTarget - be.strikerAngle) * 0.6f;
            }
            return;
        }
        be.serverTick((ServerWorld) world);
    }

    private void serverTick(ServerWorld world) {
        if (user != null) {
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(user);
            Vec3d stand = standPos(0f);
            if (p == null || p.isRemoved() || !p.isAlive() || p.squaredDistanceTo(stand) > 400 || ++idleTicks > IDLE_TIMEOUT_TICKS) {
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
    public void onUsedBy(ServerPlayerEntity sp) {
        if (autoRemaining > 0 || autoTick > 0) {
            sp.sendMessage(Text.translatable("bell.newyearcountdown.auto"), true);
            return;
        }
        if (user != null && !user.equals(sp.getUuid())) {
            ServerPlayerEntity current = sp.getServer().getPlayerManager().getPlayer(user);
            if (current != null) {
                sp.sendMessage(Text.translatable("bell.newyearcountdown.busy"), true);
                return;
            }
            user = null; // 접속이 끊긴 사용자는 자리를 비운 것으로 처리
        }
        engage(sp);
    }

    private void engage(ServerPlayerEntity sp) {
        user = sp.getUuid();
        idleTicks = 0;
        strikerAngle = 0;
        syncUser();
        // 당목 뒤끝으로 옮기고 종 쪽을 바라보게 한다
        Vec3d stand = standPos(0f);
        sp.networkHandler.requestTeleport(stand.x, stand.y, stand.z, standYaw(), 0f);
        ServerPlayNetworking.send(sp, new BellPackets.EngageS2C(pos));
    }

    public boolean isUser(ServerPlayerEntity p) {
        return user != null && user.equals(p.getUuid());
    }

    /** 사용 권한 해제. force=true 면 사용자에게 강제 종료를 알린다. */
    public void release(boolean force) {
        if (user == null || !(world instanceof ServerWorld sw)) return;
        ServerPlayerEntity p = sw.getServer().getPlayerManager().getPlayer(user);
        if (force && p != null) ServerPlayNetworking.send(p, new BellPackets.ForceExitS2C(pos));
        BellUsers.remove(user, pos);
        user = null;
        syncUser();
        setAngleAndBroadcast(0f, null);
    }

    // ================= 사용자 입력 =================

    public void onSwing(float angle) {
        idleTicks = 0;
        float a = MathHelper.clamp(angle, THETA_MIN - 0.05f, THETA_C + 0.05f);
        ServerPlayerEntity except = null;
        if (user != null && world instanceof ServerWorld sw) except = sw.getServer().getPlayerManager().getPlayer(user);
        setAngleAndBroadcast(a, except);
    }

    public void onStrikeRequest(float strength) {
        if (ticks - lastStrikeTick < 6) return; // 연타 방지
        lastStrikeTick = ticks;
        ring(MathHelper.clamp(strength, 0.05f, 1f));
    }

    private void setAngleAndBroadcast(float angle, ServerPlayerEntity except) {
        strikerAngle = angle;
        if (!(world instanceof ServerWorld sw)) return;
        for (ServerPlayerEntity p : PlayerLookup.tracking(sw, pos)) {
            if (except != null && p.getUuid().equals(except.getUuid())) continue;
            ServerPlayNetworking.send(p, new BellPackets.AngleS2C(pos, angle));
        }
    }

    // ================= 타종 =================

    /** 종을 울린다: 소리 + 근처 플레이어에게 흔들림 연출. */
    public void ring(float strength) {
        if (!(world instanceof ServerWorld sw)) return;
        Vec3d at = structToWorld(0, 1.8, 0);
        float volume = 0.35f + 0.65f * strength;
        float pitch = 0.98f + sw.random.nextFloat() * 0.04f;
        sw.playSound(null, at.x, at.y, at.z, ModSounds.BELL_STRIKE, SoundCategory.BLOCKS, volume, pitch);
        for (ServerPlayerEntity p : PlayerLookup.tracking(sw, pos)) {
            ServerPlayNetworking.send(p, new BellPackets.RingS2C(pos, strength));
        }
    }

    /** 클라이언트: 울림 수신 시 호출 (흔들림 시작). */
    public void onRingClient(float strength) {
        swayAmp = 0.02f + 0.07f * strength;
        swayStart = world == null ? 0 : world.getTime();
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
