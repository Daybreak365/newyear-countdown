package dev.newyear.countdown.client;

import dev.newyear.countdown.AuthPayload;
import dev.newyear.countdown.DebugAuth;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 숨김 기능 잠금 상태 (게임을 켜고 있는 동안만 유지, 디스크에 저장하지 않음). */
public final class DebugAccess {
    private static String password = null;

    private DebugAccess() {}

    public static boolean unlocked() {
        return password != null;
    }

    public static boolean tryUnlock(String input) {
        if (!DebugAuth.matches(input)) return false;
        password = input;
        sendAuth();
        return true;
    }

    /** 서버에 접속 중이면 서버에도 인증을 알린다 (서버가 비밀번호를 직접 검증). */
    public static void sendAuth() {
        if (password != null && ClientPlayNetworking.canSend(AuthPayload.ID)) {
            ClientPlayNetworking.send(new AuthPayload(password));
        }
    }
}
