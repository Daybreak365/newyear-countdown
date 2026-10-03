package dev.newyear.countdown.bell;

import dev.newyear.countdown.NewYearCountdown;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** 보신각 관련 네트워크 패킷 모음. 모든 패킷은 보신각 블록 위치로 대상을 지정한다. */
public final class BellPackets {
    private BellPackets() {}

    private static Identifier id(String path) {
        return Identifier.of(NewYearCountdown.MOD_ID, path);
    }

    // ---------- 서버 → 클라이언트 ----------

    /** 상호작용 시작: 이 보신각을 타종 모드로 조작하라. */
    public record EngageS2C(BlockPos pos) implements CustomPayload {
        public static final Id<EngageS2C> ID = new Id<>(id("bell_engage"));
        public static final PacketCodec<ByteBuf, EngageS2C> CODEC = BlockPos.PACKET_CODEC.xmap(EngageS2C::new, EngageS2C::pos);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 서버가 사용 권한을 회수함 (시간 초과, 멀어짐 등). */
    public record ForceExitS2C(BlockPos pos) implements CustomPayload {
        public static final Id<ForceExitS2C> ID = new Id<>(id("bell_force_exit"));
        public static final PacketCodec<ByteBuf, ForceExitS2C> CODEC = BlockPos.PACKET_CODEC.xmap(ForceExitS2C::new, ForceExitS2C::pos);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 다른 사람(또는 자동 타종)이 움직이는 당목의 각도. */
    public record AngleS2C(BlockPos pos, float angle) implements CustomPayload {
        public static final Id<AngleS2C> ID = new Id<>(id("bell_angle"));
        public static final PacketCodec<RegistryByteBuf, AngleS2C> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, AngleS2C::pos,
                PacketCodecs.FLOAT, AngleS2C::angle,
                AngleS2C::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 종이 울림: 종 흔들림 연출용. */
    public record RingS2C(BlockPos pos, float strength) implements CustomPayload {
        public static final Id<RingS2C> ID = new Id<>(id("bell_ring"));
        public static final PacketCodec<RegistryByteBuf, RingS2C> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, RingS2C::pos,
                PacketCodecs.FLOAT, RingS2C::strength,
                RingS2C::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    // ---------- 클라이언트 → 서버 ----------

    public record SwingC2S(BlockPos pos, float angle) implements CustomPayload {
        public static final Id<SwingC2S> ID = new Id<>(id("bell_swing"));
        public static final PacketCodec<RegistryByteBuf, SwingC2S> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, SwingC2S::pos,
                PacketCodecs.FLOAT, SwingC2S::angle,
                SwingC2S::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record StrikeC2S(BlockPos pos, float strength) implements CustomPayload {
        public static final Id<StrikeC2S> ID = new Id<>(id("bell_strike"));
        public static final PacketCodec<RegistryByteBuf, StrikeC2S> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, StrikeC2S::pos,
                PacketCodecs.FLOAT, StrikeC2S::strength,
                StrikeC2S::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record LeaveC2S(BlockPos pos) implements CustomPayload {
        public static final Id<LeaveC2S> ID = new Id<>(id("bell_leave"));
        public static final PacketCodec<ByteBuf, LeaveC2S> CODEC = BlockPos.PACKET_CODEC.xmap(LeaveC2S::new, LeaveC2S::pos);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    // ---------- 등록 ----------

    public static void register() {
        PayloadTypeRegistry.playS2C().register(EngageS2C.ID, EngageS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ForceExitS2C.ID, ForceExitS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(AngleS2C.ID, AngleS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RingS2C.ID, RingS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(SwingC2S.ID, SwingC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(StrikeC2S.ID, StrikeC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveC2S.ID, LeaveC2S.CODEC);

        // 모든 요청은 "현재 사용자" 본인에게서 온 것만 받는다.
        ServerPlayNetworking.registerGlobalReceiver(SwingC2S.ID, (payload, ctx) -> {
            BosingakBellBlockEntity bell = find(ctx.player(), payload.pos());
            if (bell != null && bell.isUser(ctx.player())) bell.onSwing(payload.angle());
        });
        ServerPlayNetworking.registerGlobalReceiver(StrikeC2S.ID, (payload, ctx) -> {
            BosingakBellBlockEntity bell = find(ctx.player(), payload.pos());
            if (bell != null && bell.isUser(ctx.player())) bell.onStrikeRequest(payload.strength());
        });
        ServerPlayNetworking.registerGlobalReceiver(LeaveC2S.ID, (payload, ctx) -> {
            BosingakBellBlockEntity bell = find(ctx.player(), payload.pos());
            if (bell != null && bell.isUser(ctx.player())) bell.release(false);
        });
    }

    private static BosingakBellBlockEntity find(ServerPlayerEntity player, BlockPos pos) {
        if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 2500) return null;
        ServerWorld world = player.getServerWorld();
        if (!world.isChunkLoaded(pos)) return null;
        return world.getBlockEntity(pos) instanceof BosingakBellBlockEntity b ? b : null;
    }
}
