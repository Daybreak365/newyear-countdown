package dev.newyear.countdown.bell;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/** 누가 어느 보신각을 쓰고 있는지 (클라이언트가 팔 자세 등을 그릴 때 참조). */
public final class BellUsers {
    private static final Map<UUID, BlockPos> USERS = new ConcurrentHashMap<>();

    private BellUsers() {}

    public static void set(UUID user, BlockPos pos) {
        USERS.put(user, pos.immutable());
    }

    public static void remove(UUID user, BlockPos pos) {
        USERS.remove(user, pos);
    }

    public static BlockPos posOf(UUID user) {
        return USERS.get(user);
    }

    public static void clear() {
        USERS.clear();
    }
}
