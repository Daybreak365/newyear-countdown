package dev.newyear.countdown.client;

import dev.newyear.countdown.SyncPayload;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 클라이언트 쪽 카운트다운 상태. 서버가 모드 없는 경우를 대비해 기본값(2027-01-01 00:00 KST)을 로컬로 계산한다. */
public final class ClientCountdown {
    public static final String DEFAULT_ZONE = "Asia/Seoul";

    private static long targetMs = defaultTarget();
    private static long offsetMs = 0;       // (서버 시계 - 내 시계)
    private static boolean debug = false;
    private static String zone = DEFAULT_ZONE;

    private ClientCountdown() {}

    private static long defaultTarget() {
        return LocalDateTime.of(2027, 1, 1, 0, 0).atZone(ZoneId.of(DEFAULT_ZONE)).toInstant().toEpochMilli();
    }

    public static void onSync(SyncPayload p) {
        targetMs = p.targetMs();
        offsetMs = p.serverNowMs() - System.currentTimeMillis();
        debug = p.debug();
        zone = p.zone();
    }

    public static void reset() {
        targetMs = defaultTarget();
        offsetMs = 0;
        debug = false;
        zone = DEFAULT_ZONE;
    }

    /** 서버에 보낼 수 없을 때(타이틀 화면/모드 없는 서버) 내 화면에만 적용하는 로컬 디버그. */
    public static void setLocalDebug(long ms) {
        targetMs = ms;
        debug = true;
    }

    public static long now() {
        return System.currentTimeMillis() + offsetMs;
    }

    public static long targetMs() {
        return targetMs;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static ZoneId zone() {
        try {
            return ZoneId.of(zone);
        } catch (Exception e) {
            return ZoneId.of(DEFAULT_ZONE);
        }
    }
}
