package dev.newyear.countdown.bell;

import dev.newyear.countdown.NewYearCountdown;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** 보신각 관련 네트워크 패킷 모음. 모든 패킷은 보신각 블록 위치로 대상을 지정한다. */
public final class BellPackets {
    private BellPackets() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, path);
    }

    // ---------- 서버 → 클라이언트 ----------

    /** 상호작용 시작: 이 보신각을 타종 모드로 조작하라. */
    public record EngageS2C(BlockPos pos) implements CustomPacketPayload {
        public static final Type<EngageS2C> ID = new Type<>(id("bell_engage"));
        public static final StreamCodec<ByteBuf, EngageS2C> CODEC = BlockPos.STREAM_CODEC.map(EngageS2C::new, EngageS2C::pos);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버가 사용 권한을 회수함 (시간 초과, 멀어짐 등). */
    public record ForceExitS2C(BlockPos pos) implements CustomPacketPayload {
        public static final Type<ForceExitS2C> ID = new Type<>(id("bell_force_exit"));
        public static final StreamCodec<ByteBuf, ForceExitS2C> CODEC = BlockPos.STREAM_CODEC.map(ForceExitS2C::new, ForceExitS2C::pos);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 다른 사람(또는 자동 타종)이 움직이는 당목의 각도. */
    public record AngleS2C(BlockPos pos, float angle) implements CustomPacketPayload {
        public static final Type<AngleS2C> ID = new Type<>(id("bell_angle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AngleS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, AngleS2C::pos,
                ByteBufCodecs.FLOAT, AngleS2C::angle,
                AngleS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 종이 울림: 종 흔들림 연출용. */
    public record RingS2C(BlockPos pos, float strength) implements CustomPacketPayload {
        public static final Type<RingS2C> ID = new Type<>(id("bell_ring"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RingS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RingS2C::pos,
                ByteBufCodecs.FLOAT, RingS2C::strength,
                RingS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ---------- 클라이언트 → 서버 ----------

    public record SwingC2S(BlockPos pos, float angle) implements CustomPacketPayload {
        public static final Type<SwingC2S> ID = new Type<>(id("bell_swing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SwingC2S> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SwingC2S::pos,
                ByteBufCodecs.FLOAT, SwingC2S::angle,
                SwingC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record StrikeC2S(BlockPos pos, float strength) implements CustomPacketPayload {
        public static final Type<StrikeC2S> ID = new Type<>(id("bell_strike"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StrikeC2S> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, StrikeC2S::pos,
                ByteBufCodecs.FLOAT, StrikeC2S::strength,
                StrikeC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record LeaveC2S(BlockPos pos) implements CustomPacketPayload {
        public static final Type<LeaveC2S> ID = new Type<>(id("bell_leave"));
        public static final StreamCodec<ByteBuf, LeaveC2S> CODEC = BlockPos.STREAM_CODEC.map(LeaveC2S::new, LeaveC2S::pos);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ---------- 등록 ----------

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(EngageS2C.ID, EngageS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ForceExitS2C.ID, ForceExitS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AngleS2C.ID, AngleS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RingS2C.ID, RingS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SwingC2S.ID, SwingC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StrikeC2S.ID, StrikeC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LeaveC2S.ID, LeaveC2S.CODEC);

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

    private static BosingakBellBlockEntity find(ServerPlayer player, BlockPos pos) {
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 2500) return null;
        ServerLevel world = player.level();
        if (!world.hasChunkAt(pos)) return null;
        return world.getBlockEntity(pos) instanceof BosingakBellBlockEntity b ? b : null;
    }
}
