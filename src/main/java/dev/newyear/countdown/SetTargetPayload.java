package dev.newyear.countdown;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 클라이언트 → 서버: 디버그 목표 시각 변경 요청 (OP 전용).
 * mode 0 = 절대 시각(epoch ms), 1 = 서버 현재 시각 + value 초, 2 = 초기화.
 */
public record SetTargetPayload(int mode, long value) implements CustomPacketPayload {
    public static final int ABSOLUTE = 0, RELATIVE_SECONDS = 1, RESET = 2;

    public static final Type<SetTargetPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "set_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTargetPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTargetPayload::mode,
            ByteBufCodecs.VAR_LONG, SetTargetPayload::value,
            SetTargetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
