package dev.newyear.countdown.ai;

import java.util.Arrays;
import java.util.UUID;

/** Runtime-only credential owned by the administrator's current connection. Never serialize this object. */
final class SessionApiKey {
    static final int MAX_LENGTH = 512;
    private char[] value = new char[0];
    private UUID owner;

    synchronized void set(UUID player, String key) {
        if (player == null) throw new IllegalArgumentException("관리자 접속 정보가 없습니다.");
        String next = key == null ? "" : key.trim();
        if (next.length() < 16 || next.length() > MAX_LENGTH || next.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("API 키는 공백 없는 16~512자의 영문·숫자·기호로 입력하세요.");
        clear();
        value = next.toCharArray();
        owner = player;
    }

    synchronized String get() { return new String(value); }
    synchronized boolean present() { return value.length > 0; }

    synchronized void clear() {
        Arrays.fill(value, '\0');
        value = new char[0];
        owner = null;
    }

    synchronized void onDisconnect(UUID player) {
        if (player != null && player.equals(owner)) clear();
    }
}
