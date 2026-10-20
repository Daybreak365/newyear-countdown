package dev.newyear.countdown.wish;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;

public final class WishPackets {
    public static final int MAX_LEN = 60;

    private WishPackets() {}

    /** 서버 → 클라이언트: 소원 쓰기 화면을 연다 (연등 아이템을 우클릭했을 때). */
    public record OpenS2C() implements CustomPacketPayload {
        public static final Type<OpenS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "wish_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenS2C> CODEC = StreamCodec.unit(new OpenS2C());
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 소원 내용과 함께 연등을 날린다. */
    public record WishC2S(String text) implements CustomPacketPayload {
        public static final Type<WishC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "wish_send"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WishC2S> CODEC =
                ByteBufCodecs.stringUtf8(200).map(WishC2S::new, WishC2S::text).cast();
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버 → 클라이언트: 이 서버(월드)에서 내가 빈 소원 전체 기록 (시간순). */
    public record BookS2C(List<String> texts, List<Long> times) implements CustomPacketPayload {
        public static final Type<BookS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "wish_book"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(200).apply(ByteBufCodecs.list()), BookS2C::texts,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), BookS2C::times,
                BookS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(OpenS2C.ID, OpenS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BookS2C.ID, BookS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WishC2S.ID, WishC2S.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendBook(handler.getPlayer(), server));
        ServerPlayNetworking.registerGlobalReceiver(WishC2S.ID, (payload, ctx) -> WishLaunch.handle(ctx.player(), payload.text()));
    }

    public static void sendBook(ServerPlayer player, MinecraftServer server) {
        List<String> texts = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (WishData.Entry e : WishData.get(server).of(player.getUUID())) {
            texts.add(e.text);
            times.add(e.time);
        }
        ServerPlayNetworking.send(player, new BookS2C(texts, times));
    }
}
