package dev.newyear.countdown.wish;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class WishPackets {
    public static final int MAX_LEN = 60;

    private WishPackets() {}

    /** 서버 → 클라이언트: 소원 쓰기 화면을 연다 (연등 아이템을 우클릭했을 때). */
    public record OpenS2C() implements CustomPayload {
        public static final Id<OpenS2C> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "wish_open"));
        public static final PacketCodec<RegistryByteBuf, OpenS2C> CODEC = PacketCodec.unit(new OpenS2C());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 클라이언트 → 서버: 소원 내용과 함께 연등을 날린다. */
    public record WishC2S(String text) implements CustomPayload {
        public static final Id<WishC2S> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "wish_send"));
        public static final PacketCodec<RegistryByteBuf, WishC2S> CODEC =
                PacketCodecs.string(200).xmap(WishC2S::new, WishC2S::text).cast();
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 서버 → 클라이언트: 이 서버(월드)에서 내가 빈 소원 전체 기록 (시간순). */
    public record BookS2C(List<String> texts, List<Long> times) implements CustomPayload {
        public static final Id<BookS2C> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "wish_book"));
        public static final PacketCodec<RegistryByteBuf, BookS2C> CODEC = PacketCodec.tuple(
                PacketCodecs.string(200).collect(PacketCodecs.toList()), BookS2C::texts,
                PacketCodecs.VAR_LONG.collect(PacketCodecs.toList()), BookS2C::times,
                BookS2C::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(OpenS2C.ID, OpenS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BookS2C.ID, BookS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(WishC2S.ID, WishC2S.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendBook(handler.getPlayer(), server));
        ServerPlayNetworking.registerGlobalReceiver(WishC2S.ID, (payload, ctx) -> WishLaunch.handle(ctx.player(), payload.text()));
    }

    public static void sendBook(ServerPlayerEntity player, MinecraftServer server) {
        List<String> texts = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (WishData.Entry e : WishData.get(server).of(player.getUuid())) {
            texts.add(e.text);
            times.add(e.time);
        }
        ServerPlayNetworking.send(player, new BookS2C(texts, times));
    }
}
