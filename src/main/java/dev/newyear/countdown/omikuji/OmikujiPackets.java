package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;

public final class OmikujiPackets {
    private OmikujiPackets() {}

    /** 뽑기 연출 시작: 이 뽑기대에서 이 운세(index)가 나온다. */
    public record AnimS2C(BlockPos pos, int result) implements CustomPacketPayload {
        public static final Type<AnimS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "omikuji_anim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AnimS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, AnimS2C::pos,
                ByteBufCodecs.INT, AnimS2C::result,
                AnimS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 뽑은 사람에게: 방금 뽑은 운세 결과(종이 UI 를 연다). 서버에 이미 기록된 뒤에 보낸다. */
    public record ResultS2C(int result, int number, long time) implements CustomPacketPayload {
        public static final Type<ResultS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "omikuji_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ResultS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, ResultS2C::result,
                ByteBufCodecs.INT, ResultS2C::number,
                ByteBufCodecs.VAR_LONG, ResultS2C::time,
                ResultS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 접속 시: 이 서버(월드)에서 내가 뽑은 전체 기록. 0번이 첫 뽑기. */
    public record BookS2C(List<Integer> results, List<Integer> numbers, List<Long> times) implements CustomPacketPayload {
        public static final Type<BookS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "omikuji_book"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT.apply(ByteBufCodecs.list()), BookS2C::results,
                ByteBufCodecs.INT.apply(ByteBufCodecs.list()), BookS2C::numbers,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), BookS2C::times,
                BookS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ResultS2C.ID, ResultS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BookS2C.ID, BookS2C.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendBook(handler.getPlayer(), server));
        PayloadTypeRegistry.clientboundPlay().register(AnimS2C.ID, AnimS2C.CODEC);
    }

    /** 이 서버(월드)에 저장된 내 기록을 클라이언트에 맞춘다. */
    public static void sendBook(ServerPlayer player, MinecraftServer server) {
        List<Integer> results = new ArrayList<>(), numbers = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (OmikujiData.Entry e : OmikujiData.get(server).of(player.getUUID())) {
            results.add(e.result);
            numbers.add(e.number);
            times.add(e.time);
        }
        ServerPlayNetworking.send(player, new BookS2C(results, numbers, times));
    }
}
