package dev.newyear.countdown.ai;

import com.google.gson.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit server-side allowlist for the in-game editor. Never exposes credentials or runner commands. */
public final class AiSettings {
    private static final Gson GSON = new Gson();
    private record Field(String key, String group, String label, String hint, String type,
                         double min, double max, List<String> choices, boolean restart) {}
    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();
    static {
        String g = "모델·응답";
        text("model", g, "대화 모델", "Sol: gpt-6.1-sol / Terra: gpt-6.1-terra. 다음 요청부터 적용", 1, 160);
        choice("reasoningEffort", g, "대화 추론 강도", "높을수록 응답 시간과 토큰 사용량이 늘어납니다", "low", "medium", "high", "xhigh", "max", "none", "minimal");
        choice("autonomyReasoningEffort", g, "자율 이벤트 추론", "Sol은 low~max 지원. none/minimal은 다른 모델용", "low", "medium", "high", "xhigh", "max", "none", "minimal");
        text("summaryModel", g, "기억 요약 모델", "비우면 대화 모델을 사용합니다", 0, 160);
        integer("maxOutputTokens", g, "최대 출력 토큰", "추론 토큰 포함. 선택 모델의 출력 한도 이하로 설정", 256, 128000);
        integer("requestTimeoutSeconds", g, "요청 제한 시간 (초)", "요청 하나의 응답을 기다리는 시간", 10, 600);
        integer("maxRetries", g, "재시도 횟수", "일시적 API 오류에 대한 재시도", 0, 8);
        integer("maxToolRounds", g, "도구 왕복 한도", "한 대화에서 AI가 도구를 사용하는 왕복 수", 1, 40);
        integer("maxToolCallsPerTurn", g, "도구 호출 한도", "한 대화에서 실행할 수 있는 도구의 총 수", 1, 256);
        integer("maxConcurrentRequests", g, "동시 요청 수", "서버 또는 싱글플레이 월드를 다시 열어야 적용", 1, 16, true);
        integer("maxQueuedRequests", g, "대기 요청 수", "서버 또는 싱글플레이 월드를 다시 열어야 적용", 1, 500, true);
        bool("storeResponses", g, "API 응답 저장", "OpenAI 측 응답 저장 여부");

        g = "예산·대화";
        number("dailyBudgetUsd", g, "하루 예산 (USD)", "서버 전체 추정 비용 한도. 0 = 무제한", 0, 100000);
        integer("perPlayerDailyTurns", g, "하루 대화 횟수", "플레이어 한 명의 하루 AI 대화 한도", 1, 100000);
        integer("perPlayerCooldownSeconds", g, "대화 간격 (초)", "같은 플레이어의 연속 메시지 간격", 0, 86400);
        integer("wishCooldownSeconds", g, "새 소원 간격 (초)", "진행 중인 소원의 대화는 제외. 0 = 끔", 0, 86400);
        text("spiritName", g, "AI 이름", "대화에서 사용할 이름", 1, 40);
        text("extraInstructions", g, "추가 성격·지시", "기본 지침에 덧붙이는 관리자 지시", 0, 4000);
        bool("requireConditionForLastingAbilities", g, "영구 능력에 조건 요구", "수락한 계약이나 완료된 퀘스트가 있어야 지속 능력 부여");

        g = "자율 이벤트";
        bool("autonomyEnabled", g, "자율 이벤트", "플레이어의 상황에 맞춰 AI가 먼저 사건을 제안");
        integer("autonomyPeriodicMinutes", g, "주기 (분)", "주기적으로 상황을 판단. 0 = 주기 판단 끔", 0, 10080);
        integer("autonomyPlayerCooldownMinutes", g, "개인 간격 (분)", "같은 플레이어에게 다시 반응하기까지의 간격", 0, 10080);
        integer("autonomyGlobalCooldownMinutes", g, "서버 간격 (분)", "서버 전체 자율 판단의 최소 간격", 0, 10080);
        integer("autonomyMaxCallsPerHour", g, "시간당 호출 한도", "자율 이벤트에 사용하는 AI 호출 수", 0, 1000);
        integer("autonomyMaxActiveEvents", g, "동시 사건 한도", "동시에 실행할 자율 이벤트 스크립트 수", 0, 1000);
        bool("autonomyMayModifyWorld", g, "자율 사건의 블록 변경", "자율 이벤트에도 아래 세계 변경 정책을 적용");
        integer("autonomyPlaceMinAgeMinutes", g, "장소 기억 최소 나이 (분)", "이 시간보다 오래된 장소 기억에 다시 반응", 0, 10080);
        integer("autonomyPlaceCooldownHours", g, "같은 장소 반응 간격 (시간)", "장소 재방문 이벤트의 간격", 0, 8760);
        integer("autonomyAbsenceHours", g, "오랜만의 접속 기준 (시간)", "접속 환영 이벤트의 부재 기간 기준", 0, 8760);

        g = "기억";
        integer("memoryMaxPerPlayer", g, "개인 기억 한도", "플레이어별 보관 한도. 월드 재시작 후 적용", 1, 100000, true);
        integer("memoryMaxWorld", g, "세계 기억 한도", "세계 공유 기억 한도. 월드 재시작 후 적용", 1, 100000, true);
        integer("memoryRetrieveCount", g, "대화에 넣는 기억 수", "한 요청의 기억 문맥 크기", 1, 100);
        integer("memorySummarizeThreshold", g, "요약 시작 기준", "플레이어 기억 수가 이 기준에 도달하면 요약", 2, 100000);
        bool("embeddingsEnabled", g, "의미 검색", "키워드가 달라도 뜻이 비슷한 기억 검색");
        integer("embeddingExtraMemories", g, "의미 검색 추가 기억", "한 대화에 추가로 넣는 관련 기억 수", 0, 20);
        number("embeddingWeight", g, "의미 검색 가중치", "키워드 검색과 함께 사용하는 점수 비중", 0, 100);
        number("embeddingMinSimilarity", g, "최소 의미 유사도", "이 점수 미만이면 관련 없는 기억으로 취급", -1, 1);

        g = "세계·실행";
        bool("allowBlockPlacement", g, "AI 블록 변경", "AI가 블록을 설치·교체할 수 있는지");
        choice("worldEditMode", g, "블록 변경 범위", "natural: 자연 지형 / empty_only: 빈 공간 / any: 넓게 허용", "natural", "empty_only", "any");
        integer("approvalBlockThreshold", g, "관리자 승인 기준 (칸)", "계획된 블록 변경 수가 기준을 넘으면 승인 대기", 0, 1000000);
        integer("skyChangeCooldownMinutes", g, "시간·날씨 간격 (분)", "서버 전체에 영향을 주는 변경의 최소 간격", 0, 1440);
        bool("requireConsentForOthers", g, "다른 플레이어 동의", "다른 플레이어에 대한 부정적 조작에 동의 요구");
        integer("maxActiveScriptsPerPlayer", g, "개인 스크립트 한도", "한 플레이어의 동시 실행 수", 1, 1000);
        integer("maxActiveScriptsTotal", g, "서버 스크립트 한도", "서버 전체 동시 실행 수", 1, 10000);
        integer("scriptMaxBlocks", g, "스크립트 블록 한도", "스크립트 하나가 변경할 수 있는 최대 칸 수", 1, 1000000);
        integer("scriptBlocksPerTick", g, "틱당 블록 수 (개별)", "스크립트 하나의 점진적 변경 속도", 1, 4096);
        integer("globalBlocksPerTick", g, "틱당 블록 수 (전체)", "서버 전체의 점진적 변경 속도", 1, 4096);
        integer("scriptMaxRadius", g, "활동 반경 (블록)", "스크립트가 조작할 수 있는 범위", 1, 1024);
        integer("scriptMaxEntities", g, "개별 엔티티 한도", "스크립트 하나가 관리할 엔티티 수", 1, 10000);
        integer("globalMaxScriptEntities", g, "전체 엔티티 한도", "서버 전체 스크립트 엔티티 수", 1, 100000);
        integer("scriptDefaultMaxMinutes", g, "기본 수명 (분)", "지속 시간을 지정하지 않은 스크립트. 0 = 영구 허용", 0, 525600);
        bool("keepServerAwakeForScripts", g, "빈 서버 실행 유지", "시간이 필요한 스크립트가 있으면 서버 일시정지 방지");
    }

    private AiSettings() {}
    private static void text(String k, String g, String l, String h, int min, int max) {
        FIELDS.put(k, new Field(k, g, l, h, "string", min, max, List.of(), false));
    }
    private static void integer(String k, String g, String l, String h, int min, int max) {
        integer(k, g, l, h, min, max, false);
    }
    private static void integer(String k, String g, String l, String h, int min, int max, boolean restart) {
        FIELDS.put(k, new Field(k, g, l, h, "int", min, max, List.of(), restart));
    }
    private static void number(String k, String g, String l, String h, double min, double max) {
        FIELDS.put(k, new Field(k, g, l, h, "number", min, max, List.of(), false));
    }
    private static void bool(String k, String g, String l, String h) {
        FIELDS.put(k, new Field(k, g, l, h, "boolean", 0, 0, List.of("true", "false"), false));
    }
    private static void choice(String k, String g, String l, String h, String... choices) {
        FIELDS.put(k, new Field(k, g, l, h, "choice", 0, 0, List.of(choices), false));
    }

    /** Wire schema and values are built only from the allowlist. */
    public static JsonObject snapshot(AiConfig cfg) {
        JsonObject values = GSON.toJsonTree(cfg).getAsJsonObject();
        JsonArray fields = new JsonArray();
        for (Field f : FIELDS.values()) {
            JsonObject o = GSON.toJsonTree(f).getAsJsonObject();
            o.add("value", f.key.equals("worldEditMode") ? new JsonPrimitive(cfg.editMode()) : values.get(f.key));
            fields.add(o);
        }
        JsonObject result = new JsonObject();
        result.addProperty("kind", "config");
        result.add("fields", fields);
        return result;
    }

    /** Validate the whole patch before changing anything. Reject stale edits instead of overwriting another admin. */
    public static AiConfig prepare(AiConfig current, JsonObject changes, JsonObject expected) {
        if (changes == null || changes.isEmpty()) throw new IllegalArgumentException("변경한 설정이 없습니다.");
        if (expected == null) throw new IllegalArgumentException("설정을 다시 불러오세요.");
        JsonObject next = GSON.toJsonTree(current).getAsJsonObject();
        for (var e : changes.entrySet()) {
            Field f = FIELDS.get(e.getKey());
            if (f == null) throw new IllegalArgumentException("인게임에서 변경할 수 없는 항목: " + e.getKey());
            JsonElement now = f.key.equals("worldEditMode") ? new JsonPrimitive(current.editMode()) : next.get(f.key);
            if (!now.equals(expected.get(f.key))) throw new IllegalArgumentException(f.label + ": 서버 값이 바뀌었습니다. 다시 불러오세요.");
            next.add(f.key, parse(f, e.getValue()));
        }
        if (changes.has("worldEditMode")) next.addProperty("allowReplacingSolidBlocks", false);
        AiConfig candidate = GSON.fromJson(next, AiConfig.class);
        if (candidate.model.equals("gpt-6.1-sol") || candidate.model.startsWith("gpt-6.1-sol-")) {
            List<String> supported = List.of("low", "medium", "high", "xhigh", "max");
            if (!supported.contains(candidate.reasoningEffort) || !supported.contains(candidate.autonomyReasoningEffort))
                throw new IllegalArgumentException("Sol의 추론 강도는 low / medium / high / xhigh / max 중에서 선택하세요.");
        }
        return candidate;
    }

    private static JsonElement parse(Field f, JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(f.label + ": 올바른 값을 입력하세요.");
        String s = value.getAsString().trim();
        if (f.type.equals("string")) {
            if (s.length() < f.min || s.length() > f.max) throw new IllegalArgumentException(f.label + ": 길이는 " + (int) f.min + "~" + (int) f.max + "자입니다.");
            if ((f.key.equals("model") || f.key.equals("summaryModel")) && !s.isEmpty() && !s.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*"))
                throw new IllegalArgumentException(f.label + ": 모델 ID에 공백이나 특수 문자를 넣을 수 없습니다.");
            return new JsonPrimitive(s);
        }
        if (!f.choices.isEmpty()) {
            if (!f.choices.contains(s)) throw new IllegalArgumentException(f.label + ": " + String.join(" / ", f.choices));
            return f.type.equals("boolean") ? new JsonPrimitive(Boolean.parseBoolean(s)) : new JsonPrimitive(s);
        }
        try {
            double n = Double.parseDouble(s);
            if (!Double.isFinite(n) || n < f.min || n > f.max || (f.type.equals("int") && n != Math.rint(n))) throw new NumberFormatException();
            return f.type.equals("int") ? new JsonPrimitive((int) n) : new JsonPrimitive(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(f.label + ": " + f.min + "~" + f.max + (f.type.equals("int") ? " 범위의 정수" : " 범위의 숫자") + "를 입력하세요.");
        }
    }

    public static boolean requiresRestart(JsonObject changes) {
        return changes.keySet().stream().anyMatch(k -> FIELDS.containsKey(k) && FIELDS.get(k).restart);
    }
}
