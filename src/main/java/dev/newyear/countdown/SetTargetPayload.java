package dev.newyear.countdown;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 클라이언트 → 서버: 디버그 목표 시각 변경 요청 (OP 전용).
 * mode 0 = 절대 시각(epoch ms), 1 = 서버 현재 시각 + value 초, 2 = 초기화.
 */
public record SetTargetPayload(int mode, long value) implements CustomPayload {
    public static final int ABSOLUTE = 0, RELATIVE_SECONDS = 1, RESET = 2;

    public static final Id<SetTargetPayload> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "set_target"));
    public static final PacketCodec<RegistryByteBuf, SetTargetPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTargetPayload::mode,
            PacketCodecs.VAR_LONG, SetTargetPayload::value,
            SetTargetPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
