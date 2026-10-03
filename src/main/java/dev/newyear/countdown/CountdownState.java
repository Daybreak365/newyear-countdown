package dev.newyear.countdown;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 서버가 들고 있는 카운트다운 상태. 디버그 오버라이드는 메모리에만 저장되어 재시작하면 사라진다. */
public final class CountdownState {
    private static Long debugTargetMs = null;
    /** 비밀번호를 통과한 플레이어 (접속 동안만 유효). */
    private static final Set<UUID> AUTHORIZED = ConcurrentHashMap.newKeySet();

    public static void authorize(UUID id) { AUTHORIZED.add(id); }
    public static void deauthorize(UUID id) { AUTHORIZED.remove(id); }
    public static boolean isAuthorized(UUID id) { return AUTHORIZED.contains(id); }

    private CountdownState() {}

    public static long targetMs() {
        return debugTargetMs != null ? debugTargetMs : CountdownConfig.get().targetMs();
    }

    public static boolean isDebug() {
        return debugTargetMs != null;
    }

    public static SyncPayload snapshot() {
        return new SyncPayload(targetMs(), System.currentTimeMillis(), isDebug(), CountdownConfig.get().zoneId().getId());
    }

    public static void setDebugTarget(long ms, MinecraftServer server) {
        debugTargetMs = ms;
        broadcast(server);
    }

    public static void resetDebug(MinecraftServer server) {
        debugTargetMs = null;
        broadcast(server);
    }

    public static void apply(SetTargetPayload p, MinecraftServer server) {
        switch (p.mode()) {
            case SetTargetPayload.ABSOLUTE -> setDebugTarget(p.value(), server);
            case SetTargetPayload.RELATIVE_SECONDS -> setDebugTarget(System.currentTimeMillis() + p.value() * 1000L, server);
            default -> resetDebug(server);
        }
    }

    public static void broadcast(MinecraftServer server) {
        SyncPayload snap = snapshot();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, snap);
        }
    }
}
