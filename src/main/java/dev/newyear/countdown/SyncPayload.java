package dev.newyear.countdown;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 서버 → 클라이언트: 현재 목표 시각, 서버 시계, 디버그 여부, 타임존. */
public record SyncPayload(long targetMs, long serverNowMs, boolean debug, String zone) implements CustomPacketPayload {
    public static final Type<SyncPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SyncPayload::targetMs,
            ByteBufCodecs.VAR_LONG, SyncPayload::serverNowMs,
            ByteBufCodecs.BOOL, SyncPayload::debug,
            ByteBufCodecs.STRING_UTF8, SyncPayload::zone,
            SyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
