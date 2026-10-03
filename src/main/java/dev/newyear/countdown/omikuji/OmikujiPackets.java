package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

public final class OmikujiPackets {
    private OmikujiPackets() {}

    /** 뽑기 연출 시작: 이 뽑기대에서 이 운세(index)가 나온다. */
    public record AnimS2C(BlockPos pos, int result) implements CustomPayload {
        public static final Id<AnimS2C> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "omikuji_anim"));
        public static final PacketCodec<RegistryByteBuf, AnimS2C> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, AnimS2C::pos,
                PacketCodecs.INTEGER, AnimS2C::result,
                AnimS2C::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 뽑은 사람에게: 방금 뽑은 운세 결과(종이 UI 를 연다). 서버에 이미 기록된 뒤에 보낸다. */
    public record ResultS2C(int result, int number, long time) implements CustomPayload {
        public static final Id<ResultS2C> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "omikuji_result"));
        public static final PacketCodec<RegistryByteBuf, ResultS2C> CODEC = PacketCodec.tuple(
                PacketCodecs.INTEGER, ResultS2C::result,
                PacketCodecs.INTEGER, ResultS2C::number,
                PacketCodecs.VAR_LONG, ResultS2C::time,
                ResultS2C::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 접속 시: 이 서버(월드)에서 내가 뽑은 전체 기록. 0번이 첫 뽑기. */
    public record BookS2C(List<Integer> results, List<Integer> numbers, List<Long> times) implements CustomPayload {
        public static final Id<BookS2C> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "omikuji_book"));
        public static final PacketCodec<RegistryByteBuf, BookS2C> CODEC = PacketCodec.tuple(
                PacketCodecs.INTEGER.collect(PacketCodecs.toList()), BookS2C::results,
                PacketCodecs.INTEGER.collect(PacketCodecs.toList()), BookS2C::numbers,
                PacketCodecs.VAR_LONG.collect(PacketCodecs.toList()), BookS2C::times,
                BookS2C::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ResultS2C.ID, ResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BookS2C.ID, BookS2C.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendBook(handler.getPlayer(), server));
        PayloadTypeRegistry.playS2C().register(AnimS2C.ID, AnimS2C.CODEC);
    }

    /** 이 서버(월드)에 저장된 내 기록을 클라이언트에 맞춘다. */
    public static void sendBook(ServerPlayerEntity player, MinecraftServer server) {
        List<Integer> results = new ArrayList<>(), numbers = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (OmikujiData.Entry e : OmikujiData.get(server).of(player.getUuid())) {
            results.add(e.result);
            numbers.add(e.number);
            times.add(e.time);
        }
        ServerPlayNetworking.send(player, new BookS2C(results, numbers, times));
    }
}
