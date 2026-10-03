package dev.newyear.countdown;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 서버 설정 (config/newyearcountdown.json).
 * 실제 카운트다운 목표 시각과 타임존, 카운트다운 종료 시 연출 문구를 담는다.
 */
public final class CountdownConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static CountdownConfig instance = new CountdownConfig();

    /** 목표 시각을 해석할 타임존 (IANA ID). */
    public String zone = "Asia/Seoul";
    /** 목표 시각, ISO-8601 로컬 날짜시간. */
    public String target = "2027-01-01T00:00:00";
    /** 종료 시 화면 중앙에 뜨는 타이틀/서브타이틀. */
    public String title = "§6§l새해 복 많이 받으세요!";
    public String subtitle = "§e2027년이 밝았습니다";
    /** 종료 시 전체 채팅 메시지. 빈 문자열이면 생략. */
    public String chatMessage = "§6[카운트다운] §f2027년 새해가 밝았습니다!";

    public static CountdownConfig get() {
        return instance;
    }

    public ZoneId zoneId() {
        try {
            return ZoneId.of(zone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    public long targetMs() {
        try {
            return LocalDateTime.parse(target).atZone(zoneId()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return LocalDateTime.of(2027, 1, 1, 0, 0).atZone(zoneId()).toInstant().toEpochMilli();
        }
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("newyearcountdown.json");
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                CountdownConfig loaded = GSON.fromJson(r, CountdownConfig.class);
                if (loaded != null) instance = loaded;
            } catch (Exception e) {
                NewYearCountdown.LOGGER.error("Failed to read config, using defaults", e);
            }
        }
        // 누락된 필드를 채워 다시 저장
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(instance, w);
        } catch (IOException e) {
            NewYearCountdown.LOGGER.error("Failed to write config", e);
        }
    }
}
