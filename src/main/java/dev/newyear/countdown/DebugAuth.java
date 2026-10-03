package dev.newyear.countdown;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 숨김 기능 잠금 해제용 비밀번호 검증 (SHA-256 해시 비교, 서버·클라이언트 공용). */
public final class DebugAuth {
    private static final String HASH = "d12a59224e1e41c7b8ef3dafde61d54a3558a519cb7531c183c607a0aef22b82";

    private DebugAuth() {}

    public static boolean matches(String input) {
        if (input == null || input.length() > 64) return false;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return MessageDigest.isEqual(sb.toString().getBytes(StandardCharsets.UTF_8), HASH.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }
}
