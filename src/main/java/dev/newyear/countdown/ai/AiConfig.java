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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 소원 엔진 서버 설정 (config/newyearcountdown-ai.json).
 * API 키는 파일에 저장하지 않는다. 관리자 접속 중 임시 키 또는 OPENAI_API_KEY를 사용한다.
 */
public final class AiConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile AiConfig instance = new AiConfig();
    private static final SessionApiKey SESSION_KEY = new SessionApiKey();

    // ---------------- 모델
    /** 전체 기능 스위치. false 면 AI 호출과 스크립트 실행을 모두 멈춘다 (킬 스위치와 같음). */
    public boolean enabled = true;
    /** Operator-controlled isolated Minecraft runner. Arguments: {jar}, {report}, {hash}. Never populated by AI tools. */
    public List<String> extensionTestCommand = new ArrayList<>();
    public String model = "gpt-6.1-sol";
    /** Sol: low / medium / high / xhigh / max. Other models may support none / minimal. */
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
    /** 1M 토큰당 가격(USD). 모델 가격표에 맞게 관리자가 설정한다. modelPrices 에 없는 모델에 쓴다. */
    public double priceInputPerMTok = 2.50;
    public double priceCachedInputPerMTok = 0.25;
    public double priceOutputPerMTok = 15.00;
    /**
     * 모델별 가격표: {"모델 이름 또는 접두어": [입력, 캐시 입력, 출력]} (1M 토큰당 USD).
     * 응답에 실린 실제 모델 이름(예: 날짜가 붙은 스냅샷)을 가장 긴 접두어로 찾는다. /wishai price 로 바꿀 수 있다.
     */
    public Map<String, double[]> modelPrices = new LinkedHashMap<>();
    /** 임베딩 1M 토큰당 가격 (USD). */
    public double priceEmbeddingPerMTok = 0.02;
    /** 서버 전체 하루 예산 (USD). 0 이하면 무제한. */
    public double dailyBudgetUsd = 5.0;
    /** 플레이어 한 명이 하루에 시작할 수 있는 AI 턴 수. */
    public int perPlayerDailyTurns = 60;
    /** 같은 플레이어의 연속 요청 최소 간격 (초). */
    public int perPlayerCooldownSeconds = 4;
    /**
     * 새 소원 쿨타임 (초): 한 플레이어가 새 소원을 빈 뒤 다음 새 소원까지 기다려야 하는 시간. 0 이면 끔.
     * 진행 중인 소원의 대화(대답·협상)는 막지 않는다. /wishai cooldown set|reset 으로 바꾸거나 초기화한다.
     */
    public int wishCooldownSeconds = 60;
    /**
     * 플레이어 소원으로 지속 능력(activate_script lasting:true)을 줄 때 조건을 요구한다:
     * 그 소원에 수락된 계약이나 완료된 퀘스트가 있어야 한다 (아주 작은 소원은 지속 능력이 아니라 run_script 로 끝난다).
     */
    public boolean requireConditionForLastingAbilities = true;
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
    public int scriptMaxBlocks = 4096;
    public int scriptBlocksPerTick = 64;
    /** 모든 스크립트를 합쳐 한 틱에 적용하는 점진적 세계 변경 칸 수. */
    public int globalBlocksPerTick = 256;
    public int scriptMaxRadius = 48;
    public int scriptErrorLimit = 8;
    /** 지속 시간이 지정되지 않은 스크립트의 최대 수명 (분). 0 = 영구 허용. */
    public int scriptDefaultMaxMinutes = 0;
    public int scriptStateMaxBytes = 32_768;
    public int scriptValidationAttempts = 4;
    /**
     * 스크립트가 붙잡고 있는 값(전역 변수·state·클로저·타이머)의 추정 크기 상한 (바이트).
     * 실행이 끝날 때마다 실제로 도달 가능한 값 그래프를 따라가며 JVM 객체 크기 모형으로 잰다. 넘으면 오류로 센다.
     */
    public int scriptHeapMaxBytes = 4 * 1024 * 1024;
    /** 재시작 때 after()/every()·나중에 등록한 on() 을 클로저째 저장해 이어서 돌린다. */
    public boolean scriptPersistClosures = true;
    /** 스크립트 하나가 엔티티에 붙일 수 있는 AI 목표(goal) 수. */
    public int scriptMaxGoals = 32;
    /** 스크립트 하나가 만들 수 있는 조립 모델 수와 모델 하나의 최대 부품 수. */
    public int scriptMaxModels = 8;
    public int modelMaxParts = 48;
    /** AI 가 그린 텍스처 수 한도 (서버 전체) 와 최대 크기 (픽셀). */
    public int maxDynamicTextures = 256;
    public int dynamicTextureMaxSize = 32;

    // ---------------- 권한 정책
    /** AI 스크립트가 블록을 바꿀 수 있는지 (false 면 블록 변경 전부 금지). */
    public boolean allowBlockPlacement = true;
    /**
     * 세계 변경 범위.
     * "natural"(기본): 빈 공간 + 자연 지형(돌·흙·모래·광석·나무·물…)을 바꿀 수 있다. 지은 것과 그 바로 옆 칸, 내용물이 있는 블록은 보호.
     * "empty_only": 빈 공간(공기·물·풀)에만 놓을 수 있다 (예전 동작).
     * "any": 내용물이 있는 블록만 빼고 무엇이든 (관리자가 직접 고른 서버에서만 권장).
     */
    public String worldEditMode = "natural";
    /** 예전 설정 이름. true 면 worldEditMode "any" 와 같다. */
    public boolean allowReplacingSolidBlocks = false;
    /** 블록 변경에 관리자 승인이 필요한 개수 기준. 넘으면 승인 대기 (관리자는 미리보기로 확인). */
    public int approvalBlockThreshold = 1024;
    /** 시간·날씨처럼 모든 플레이어에게 미치는 변경의 서버 전체 최소 간격 (분). */
    public int skyChangeCooldownMinutes = 10;
    /** 다른 플레이어에게 부정적 효과(독, 텔레포트, 아이템 회수)를 줄 때 동의를 요구한다. */
    public boolean requireConsentForOthers = true;
    /** 관리자 승인을 요구할 API 이름들 (예: "world.terraform", "world.setTime"). 기본은 없음 — 블록 수 기준만. */
    public List<String> approvalRequiredApis = new ArrayList<>();
    /** 설정 형식 버전 (기본값이 바뀐 항목을 한 번만 옮기기 위해). */
    public int configVersion;

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

    // ---------------- 의미 검색 (임베딩)
    /** 기억을 임베딩해서 키워드가 달라도 뜻이 비슷하면 찾는다. API 키가 없으면 자동으로 키워드 검색만. */
    public boolean embeddingsEnabled = true;
    public String embeddingModel = "text-embedding-3-small";
    /** 0 이면 모델 기본 차원. 작을수록 저장·계산이 가볍다. */
    public int embeddingDimensions = 256;
    public int embeddingBatchSize = 32;
    /** 의미 점수 가중치 (키워드 관련성 가중치가 4.0). */
    public double embeddingWeight = 3.5;
    /** 이 코사인 유사도 아래는 무관한 것으로 본다. */
    public double embeddingMinSimilarity = 0.25;
    /** 턴마다 의미 검색으로 덧붙일 기억 수 (키워드 검색 결과에 없던 것만). */
    public int embeddingExtraMemories = 5;
    /** 질의 임베딩 요청 제한 시간 (초). 넘으면 그 턴은 키워드 검색만. */
    public int embeddingTimeoutSeconds = 8;

    // ---------------- 서버
    /**
     * 데디케이티드 서버의 pause-when-empty-seconds 가 켜져 있어도, 플레이어 없이 돌아야 하는 스크립트
     * (주인 없는 세계 규칙, 이름 타이머, 남은 시간이 흐르는 능력)가 있으면 서버를 재우지 않는다.
     */
    public boolean keepServerAwakeForScripts = true;

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
        instance.migrate();
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

    /** Publish only after the complete config has been durably written. Called on the server thread. */
    public static void replaceAndSave(AiConfig next) throws IOException {
        writeConfig(next, path());
        instance = next;
    }

    static void writeConfig(AiConfig next, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), "wishai-config-", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(next, w);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void sanitize() {
        if (model == null || model.isBlank()) model = "gpt-6.1-sol";
        if (reasoningEffort == null || reasoningEffort.isBlank()) reasoningEffort = "medium";
        if (apiBase == null || apiBase.isBlank()) apiBase = "https://api.openai.com/v1";
        while (apiBase.endsWith("/")) apiBase = apiBase.substring(0, apiBase.length() - 1);
        maxConcurrentRequests = clamp(maxConcurrentRequests, 1, 16);
        maxQueuedRequests = clamp(maxQueuedRequests, 1, 500);
        maxToolRounds = clamp(maxToolRounds, 1, 40);
        maxRetries = clamp(maxRetries, 0, 8);
        requestTimeoutSeconds = clamp(requestTimeoutSeconds, 10, 600);
        scriptValidationAttempts = clamp(scriptValidationAttempts, 1, 10);
        wishCooldownSeconds = clamp(wishCooldownSeconds, 0, 86_400);
        if (worldEditMode == null || !List.of("natural", "empty_only", "any").contains(worldEditMode)) worldEditMode = "natural";
        globalBlocksPerTick = clamp(globalBlocksPerTick, 1, 4096);
        skyChangeCooldownMinutes = clamp(skyChangeCooldownMinutes, 0, 1440);
        if (approvalRequiredApis == null) approvalRequiredApis = new ArrayList<>();
        if (spiritName == null || spiritName.isBlank()) spiritName = "새벽별";
        if (extraInstructions == null) extraInstructions = "";
        if (summaryModel == null) summaryModel = "";
        if (autonomyReasoningEffort == null || autonomyReasoningEffort.isBlank()) autonomyReasoningEffort = "low";
        if (modelPrices == null) modelPrices = new LinkedHashMap<>();
        modelPrices.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().length < 3);
        if (embeddingModel == null || embeddingModel.isBlank()) embeddingModel = "text-embedding-3-small";
        embeddingDimensions = clamp(embeddingDimensions, 0, 3072);
        embeddingBatchSize = clamp(embeddingBatchSize, 1, 256);
        embeddingExtraMemories = clamp(embeddingExtraMemories, 0, 20);
        embeddingTimeoutSeconds = clamp(embeddingTimeoutSeconds, 2, 60);
        scriptHeapMaxBytes = clamp(scriptHeapMaxBytes, 64 * 1024, 256 * 1024 * 1024);
        dynamicTextureMaxSize = clamp(dynamicTextureMaxSize, 8, 64);
        modelMaxParts = clamp(modelMaxParts, 1, 256);
    }

    /**
     * 기본값이 바뀐 항목을 한 번 옮긴다 — 관리자가 예전 기본값을 그대로 두었을 때만 (직접 바꾼 값은 건드리지 않는다).
     * v2: 원래 세계를 바꾸는 소원을 어느 정도 들어주도록 블록 한도 512→4096, 승인 기준 128→1024, 시간·날씨 승인 요구 → 서버 전체 쿨타임.
     */
    private void migrate() {
        if (configVersion >= 2) return;
        List<String> changed = new ArrayList<>();
        if (scriptMaxBlocks == 512) { scriptMaxBlocks = 4096; changed.add("scriptMaxBlocks 512→4096"); }
        if (approvalBlockThreshold == 128) { approvalBlockThreshold = 1024; changed.add("approvalBlockThreshold 128→1024"); }
        if (approvalRequiredApis.size() == 2 && approvalRequiredApis.containsAll(List.of("world.setTime", "world.setWeather"))) {
            approvalRequiredApis.clear();
            changed.add("approvalRequiredApis [world.setTime, world.setWeather]→[] (sky changes now use skyChangeCooldownMinutes)");
        }
        configVersion = 2;
        if (!changed.isEmpty()) NewYearCountdown.LOGGER.info("[WishAI] AI config defaults updated: {}", String.join(", ", changed));
    }

    /** 실제 적용할 세계 변경 범위. */
    public String editMode() {
        if (allowReplacingSolidBlocks) return "any";
        return worldEditMode == null ? "natural" : worldEditMode;
    }

    /** [입력, 캐시 입력, 출력] 1M 토큰당 가격. 응답의 실제 모델 이름을 가장 긴 접두어로 찾고, 없으면 기본 단가. */
    public double[] priceFor(String model) {
        if (model != null && !modelPrices.isEmpty()) {
            double[] best = null;
            int bestLen = -1;
            for (Map.Entry<String, double[]> e : modelPrices.entrySet()) {
                String k = e.getKey();
                if ((model.equals(k) || model.startsWith(k)) && k.length() > bestLen) {
                    best = e.getValue();
                    bestLen = k.length();
                }
            }
            if (best != null) return best;
        }
        // Standard, short-context list rates; explicit administrator entries above take precedence.
        if (model != null && (model.equals("gpt-6.1-sol") || model.startsWith("gpt-6.1-sol-")))
            return new double[]{2.0, 0.10, 10.0};
        return new double[]{priceInputPerMTok, priceCachedInputPerMTok, priceOutputPerMTok};
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

    public static void setSessionApiKey(java.util.UUID player, String key) { SESSION_KEY.set(player, key); }
    public static void clearSessionApiKey() { SESSION_KEY.clear(); }
    public static void clearSessionApiKeyFor(java.util.UUID player) { SESSION_KEY.onDisconnect(player); }
    public static boolean hasSessionApiKey() { return SESSION_KEY.present(); }

    /** Status only; never returns any part of a credential. */
    public static String apiKeySource() {
        if (hasSessionApiKey()) return "session";
        String key = System.getenv("OPENAI_API_KEY");
        return key != null && !key.isBlank() ? "environment" : "none";
    }

    /** API 키. 서버에서만 사용하며 파일·로그·서버 응답에 넣지 않는다. */
    public static String apiKey() {
        if (testApiKey != null) return testApiKey;
        String temporary = SESSION_KEY.get();
        if (!temporary.isEmpty()) return temporary;
        String k = System.getenv("OPENAI_API_KEY");
        return k == null ? "" : k.trim();
    }

    public static boolean hasApiKey() {
        return !apiKey().isEmpty();
    }
}
