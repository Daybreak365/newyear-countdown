package dev.newyear.countdown.bell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 서버에 로드된 보신각 목록 (새해 자동 타종용). */
public final class BellRegistry {
    private static final Set<BosingakBellBlockEntity> LOADED = ConcurrentHashMap.newKeySet();

    private BellRegistry() {}

    public static void init() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, world) -> {
            if (be instanceof BosingakBellBlockEntity bell) LOADED.add(bell);
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, world) -> {
            if (be instanceof BosingakBellBlockEntity bell) LOADED.remove(bell);
        });
    }

    public static void startAutoRingAll(int strikes) {
        for (BosingakBellBlockEntity bell : LOADED) bell.startAutoRing(strikes);
    }
}
