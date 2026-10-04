package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.DebugAuth;
import dev.newyear.countdown.ModReg;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 오미쿠지 관리자 콘솔: 뽑기대 뒤편의 작은 버튼 → 비밀번호 → 서버의 모든 유저가 뽑은 기록 목록.
 * 비밀번호는 서버가 직접 확인하고(DebugAuth), 기록은 확인된 요청에만 보낸다.
 */
public final class OmikujiAdmin {
    private OmikujiAdmin() {}

    /** 버튼 위치 (구조물 좌표계: 마스터 칸 바닥 중심 원점, 정면 +Z). OmikujiModel.drawAdminButton 과 같은 값. */
    public static final double BUTTON_Y = 0.53, BUTTON_Z = -1.47;

    /** 서버 → 클라이언트: 관리자 콘솔(비밀번호 화면)을 연다. */
    public record OpenS2C() implements CustomPacketPayload {
        public static final Type<OpenS2C> ID = new Type<>(ModReg.id("omikuji_admin_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenS2C> CODEC = StreamCodec.unit(new OpenS2C());
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 클라이언트 → 서버: 비밀번호와 함께 전체 기록 요청. */
    public record RequestC2S(String password) implements CustomPacketPayload {
        public static final Type<RequestC2S> ID = new Type<>(ModReg.id("omikuji_admin_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), RequestC2S::password, RequestC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 한 유저의 기록. 0번이 첫 뽑기. */
    public record UserRecord(String name, List<Integer> results, List<Integer> numbers, List<Long> times) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UserRecord> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, UserRecord::name,
                ByteBufCodecs.INT.apply(ByteBufCodecs.list()), UserRecord::results,
                ByteBufCodecs.INT.apply(ByteBufCodecs.list()), UserRecord::numbers,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), UserRecord::times,
                UserRecord::new);
    }

    /** 서버 → 클라이언트: 비밀번호가 맞으면 전체 기록, 틀리면 ok=false. */
    public record ListS2C(boolean ok, List<UserRecord> users) implements CustomPacketPayload {
        public static final Type<ListS2C> ID = new Type<>(ModReg.id("omikuji_admin_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ListS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, ListS2C::ok,
                UserRecord.CODEC.apply(ByteBufCodecs.list()), ListS2C::users,
                ListS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(OpenS2C.ID, OpenS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ListS2C.ID, ListS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RequestC2S.ID, RequestC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestC2S.ID, (payload, context) -> handle(context.player(), payload.password()));
    }

    /** 클릭 지점이 뒤편 버튼인지 (마스터 위치·방향 기준으로 구조물 좌표로 바꿔 본다). */
    public static boolean isButton(BlockPos master, Direction facing, Vec3 hit) {
        double dx = hit.x - (master.getX() + 0.5), dy = hit.y - master.getY(), dz = hit.z - (master.getZ() + 0.5);
        float yaw = facing.toYRot() * Mth.DEG_TO_RAD;
        double c = Math.cos(yaw), s = Math.sin(yaw);
        double lx = dx * c + dz * s, lz = -dx * s + dz * c;
        return Math.abs(lx) < 0.16 && Math.abs(dy - BUTTON_Y) < 0.16 && lz < BUTTON_Z + 0.12;
    }

    public static void open(ServerPlayer player) {
        ServerPlayNetworking.send(player, new OpenS2C());
    }

    private static void handle(ServerPlayer player, String password) {
        if (!DebugAuth.matches(password)) {
            ServerPlayNetworking.send(player, new ListS2C(false, List.of()));
            return;
        }
        MinecraftServer server = player.level().getServer();
        List<UserRecord> users = new ArrayList<>();
        for (Map.Entry<UUID, List<OmikujiData.Entry>> e : OmikujiData.get(server).all().entrySet()) {
            List<Integer> results = new ArrayList<>(), numbers = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            for (OmikujiData.Entry en : e.getValue()) {
                results.add(en.result);
                numbers.add(en.number);
                times.add(en.time);
            }
            if (!results.isEmpty()) users.add(new UserRecord(nameOf(server, e.getKey()), results, numbers, times));
        }
        users.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        ServerPlayNetworking.send(player, new ListS2C(true, users));
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().name();
        return server.services().nameToIdCache().get(id).map(NameAndId::name).orElse(id.toString().substring(0, 8));
    }
}
