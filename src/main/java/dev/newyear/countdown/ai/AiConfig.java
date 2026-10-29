package dev.newyear.countdown.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 소원 엔진 서버 설정 (config/newyearcountdown-ai.json).
 * API 키는 여기 저장하지 않는다 — 서버 환경 변수 OPENAI_API_KEY 에서만 읽는다.
 */
public final class AiConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile AiConfig instance = new AiConfig();

    // ---------------- 모델
    /** 전체 기능 스위치. false 면 AI 호출과 스크립트 실행을 모두 멈춘다 (킬 스위치와 같음). */
    public boolean enabled = true;
    public String model = "gpt-6.1-terra";
    /** none / minimal / low / medium / high / xhigh */
    public String reasoningEffort = "medium";
    /** 자율 이벤트 판단처럼 가벼운 호출에 쓰는 추론 강도. */
    public String autonomyReasoningEffort = "low";
    /** 장기 기억 요약에 쓰는 모델 (비우면 model). */
    public String summaryModel = "";
    /** Responses API 기본 주소. 프록시나 테스트용 가짜 서버를 쓸 때만 바꾼다. */
    public String apiBase = "https://api.openai.com/v1";
    /** 요청 하나의 제한 시간 (초). */
    public int requestTimeoutSeconds = 90;
    /** 일시적 오류 재시도 횟수. */
    public int maxRetries = 3;
    /** 동시에 진행할 수 있는 AI 작업 수. */
    public int maxConcurrentRequests = 3;
    /** 대기열 최대 길이. 넘으면 "잠시 후 다시" 안내. */
    public int maxQueuedRequests = 24;
    /** 한 번의 대화 턴에서 허용하는 도구 호출 왕복 수. */
    public int maxToolRounds = 14;
    /** 한 턴에서 허용하는 총 도구 호출 수. */
    public int maxToolCallsPerTurn = 48;
    /** 응답 최대 출력 토큰 (추론 포함). */
    public int maxOutputTokens = 16000;
    /** OpenAI 측 응답 저장 여부. false 면 추론 내용을 암호화 형태로 직접 주고받는다. */
    public boolean storeResponses = false;

    // ---------------- 비용/한도
    /** 1M 토큰당 가격(USD). 모델 가격표에 맞게 관리자가 설정한다. */
    public double priceInputPerMTok = 2.50;
    public double priceCachedInputPerMTok = 0.25;
    public double priceOutputPerMTok = 15.00;
    /** 서버 전체 하루 예산 (USD). 0 이하면 무제한. */
    public double dailyBudgetUsd = 5.0;
    /** 플레이어 한 명이 하루에 시작할 수 있는 AI 턴 수. */
    public int perPlayerDailyTurns = 60;
    /** 같은 플레이어의 연속 요청 최소 간격 (초). */
    public int perPlayerCooldownSeconds = 4;
    /** 대화 입력 최대 길이. */
    public int maxMessageLength = 300;

    // ---------------- 페르소나
    public String spiritName = "새벽별";
    /** 시스템 프롬프트에 덧붙일 관리자 지시. */
    public String extraInstructions = "";

    // ---------------- 스크립트 한도
    public int maxActiveScriptsPerPlayer = 6;
    public int maxActiveScriptsTotal = 60;
    public int scriptStepsPerRun = 150_000;
    public int scriptStepsPerTickTotal = 600_000;
    public int scriptMillisPerRun = 25;
    public int scriptAllocPerRun = 300_000;
    public int scriptMaxListeners = 24;
    public int scriptMaxTimers = 32;
    public int scriptMaxEntities = 24;
    public int globalMaxScriptEntities = 240;
    public int scriptMaxBlocks = 512;
    public int scriptBlocksPerTick = 64;
    public int scriptMaxRadius = 48;
    public int scriptErrorLimit = 8;
    /** 지속 시간이 지정되지 않은 스크립트의 최대 수명 (분). 0 = 영구 허용. */
    public int scriptDefaultMaxMinutes = 0;
    public int scriptStateMaxBytes = 32_768;
    public int scriptValidationAttempts = 4;

    // ---------------- 권한 정책
    /** AI 스크립트가 블록을 설치할 수 있는지 (공기·액체·풀 같은 대체 가능 블록만). */
    public boolean allowBlockPlacement = true;
    /** 기존 고체 블록을 바꾸는 것까지 허용할지. 기본은 금지 (플레이어 건축물 보호). */
    public boolean allowReplacingSolidBlocks = false;
    /** 블록 변경에 관리자 승인이 필요한 개수 기준. 넘으면 승인 대기. */
    public int approvalBlockThreshold = 128;
    /** 다른 플레이어에게 부정적 효과(독, 텔레포트, 아이템 회수)를 줄 때 동의를 요구한다. */
    public boolean requireConsentForOthers = true;
    /** 관리자 승인을 요구할 API 이름들 (예: "world.setBlock", "world.explode"). */
    public List<String> approvalRequiredApis = new ArrayList<>(List.of("world.setTime", "world.setWeather"));

    // ---------------- 자율 이벤트
    public boolean autonomyEnabled = true;
    /** 같은 플레이어에게 자율 이벤트를 다시 고려하기까지 최소 간격 (분). */
    public int autonomyPlayerCooldownMinutes = 20;
    /** 서버 전체 자율 판단 최소 간격 (분). */
    public int autonomyGlobalCooldownMinutes = 4;
    /** 주기적 판단 간격 (분). 0 이면 끔. */
    public int autonomyPeriodicMinutes = 30;
    /** 시간당 최대 자율 AI 호출 수. */
    public int autonomyMaxCallsPerHour = 6;
    /** 동시에 활성화될 수 있는 자율 이벤트 스크립트 수. */
    public int autonomyMaxActiveEvents = 4;
    /** 자율 이벤트가 블록을 바꿀 수 있는지. */
    public boolean autonomyMayModifyWorld = false;
    /** 기억 속 장소가 이 시간(분)보다 오래되어야 "다시 찾아온" 것으로 본다. */
    public int autonomyPlaceMinAgeMinutes = 10;
    /** 같은 장소로 다시 반응하기까지의 간격 (시간). */
    public int autonomyPlaceCooldownHours = 3;
    /** 오랜만의 접속으로 보는 기준 (시간). */
    public int autonomyAbsenceHours = 6;

    // ---------------- 기억
    public int memoryMaxPerPlayer = 400;
    public int memoryMaxWorld = 1200;
    /** 한 요청에 넣는 기억 수. */
    public int memoryRetrieveCount = 10;
    /** 요약을 시작할 기억 수 (플레이어별). */
    public int memorySummarizeThreshold = 160;

    public static AiConfig get() {
        return instance;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("newyearcountdown-ai.json");
    }

    public static void load() {
        Path path = path();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                AiConfig loaded = GSON.fromJson(r, AiConfig.class);
                if (loaded != null) instance = loaded;
            } catch (Exception e) {
                NewYearCountdown.LOGGER.error("[WishAI] Failed to read AI config, using defaults", e);
            }
        }
        instance.sanitize();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (IOException e) {
            NewYearCountdown.LOGGER.error("[WishAI] Failed to write AI config", e);
        }
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            GSON.toJson(instance, w);
        } catch (IOException e) {
            NewYearCountdown.LOGGER.error("[WishAI] Failed to write AI config", e);
        }
    }

    private void sanitize() {
        if (model == null || model.isBlank()) model = "gpt-6.1-terra";
        if (reasoningEffort == null || reasoningEffort.isBlank()) reasoningEffort = "medium";
        if (apiBase == null || apiBase.isBlank()) apiBase = "https://api.openai.com/v1";
        while (apiBase.endsWith("/")) apiBase = apiBase.substring(0, apiBase.length() - 1);
        maxConcurrentRequests = clamp(maxConcurrentRequests, 1, 16);
        maxQueuedRequests = clamp(maxQueuedRequests, 1, 500);
        maxToolRounds = clamp(maxToolRounds, 1, 40);
        maxRetries = clamp(maxRetries, 0, 8);
        requestTimeoutSeconds = clamp(requestTimeoutSeconds, 10, 600);
        scriptValidationAttempts = clamp(scriptValidationAttempts, 1, 10);
        if (approvalRequiredApis == null) approvalRequiredApis = new ArrayList<>();
        if (spiritName == null || spiritName.isBlank()) spiritName = "새벽별";
        if (extraInstructions == null) extraInstructions = "";
        if (summaryModel == null) summaryModel = "";
        if (autonomyReasoningEffort == null || autonomyReasoningEffort.isBlank()) autonomyReasoningEffort = "low";
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 자가 진단(가짜 API 서버)용 임시 덮어쓰기. 운영 중에는 null. */
    public static volatile String testApiKey;
    public static volatile String testApiBase;

    public String effectiveApiBase() {
        return testApiBase != null ? testApiBase : apiBase;
    }

    /** API 키 (환경 변수). 절대 로그나 패킷에 쓰지 않는다. */
    public static String apiKey() {
        if (testApiKey != null) return testApiKey;
        String k = System.getenv("OPENAI_API_KEY");
        return k == null ? "" : k.trim();
    }

    public static boolean hasApiKey() {
        return !apiKey().isEmpty();
    }
}
