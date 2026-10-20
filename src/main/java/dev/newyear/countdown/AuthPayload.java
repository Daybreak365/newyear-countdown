package dev.newyear.countdown;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 클라이언트 → 서버: 잠금 해제 비밀번호. 서버가 직접 검증하므로 클라이언트만 고쳐서는 우회할 수 없다. */
public record AuthPayload(String password) implements CustomPacketPayload {
    public static final Type<AuthPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "auth"));
    public static final StreamCodec<ByteBuf, AuthPayload> CODEC = ByteBufCodecs.STRING_UTF8.map(AuthPayload::new, AuthPayload::password);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
