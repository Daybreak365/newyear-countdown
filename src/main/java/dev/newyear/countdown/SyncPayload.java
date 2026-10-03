package dev.newyear.countdown;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 현재 목표 시각, 서버 시계, 디버그 여부, 타임존. */
public record SyncPayload(long targetMs, long serverNowMs, boolean debug, String zone) implements CustomPayload {
    public static final Id<SyncPayload> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "sync"));
    public static final PacketCodec<RegistryByteBuf, SyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, SyncPayload::targetMs,
            PacketCodecs.VAR_LONG, SyncPayload::serverNowMs,
            PacketCodecs.BOOL, SyncPayload::debug,
            PacketCodecs.STRING, SyncPayload::zone,
            SyncPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
