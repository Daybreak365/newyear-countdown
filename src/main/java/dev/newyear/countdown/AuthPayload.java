package dev.newyear.countdown;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 클라이언트 → 서버: 잠금 해제 비밀번호. 서버가 직접 검증하므로 클라이언트만 고쳐서는 우회할 수 없다. */
public record AuthPayload(String password) implements CustomPayload {
    public static final Id<AuthPayload> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "auth"));
    public static final PacketCodec<ByteBuf, AuthPayload> CODEC = PacketCodecs.STRING.xmap(AuthPayload::new, AuthPayload::password);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
