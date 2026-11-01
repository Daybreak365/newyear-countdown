package dev.newyear.countdown.ai.usage;

import dev.newyear.countdown.ai.openai.ApiResponse;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 토큰·비용·오류율·지연 시간 집계. AI 작업 스레드와 서버 스레드 양쪽에서 부르므로 synchronized.
 * 일일 합계는 저장되어 서버를 다시 켜도 하루 예산이 이어진다.
 */
public final class UsageTracker {
    public static final class Day {
        public String date;
        public long requests;
        public long turns;
        public long errors;
        public long inputTokens;
        public long cachedTokens;
        public long outputTokens;
        public long reasoningTokens;
        public double costUsd;
        public long totalLatencyMs;
        public Map<String, Long> turnsByPlayer = new HashMap<>();
        public Map<String, Double> costByPlayer = new HashMap<>();
        public Map<String, Long> errorsByKind = new HashMap<>();
        /** 응답에 실린 실제 모델 이름별 비용·토큰 (가격표가 모델마다 다르므로). */
        public Map<String, Double> costByModel = new HashMap<>();
        public Map<String, Long> tokensByModel = new HashMap<>();
        public long embeddingRequests;
        public long embeddingTokens;
    }

    public static final class Snapshot {
        public Day today;
        public Day lifetime;
        public int inFlight;
        public int queued;
        public double avgLatencyMs;
        public double errorRate;
    }

    private Day today = new Day();
    private Day lifetime = new Day();
    private final Deque<long[]> recentLatency = new ArrayDeque<>();
    private final ZoneId zone;
    public volatile int inFlight;
    public volatile int queued;

    public UsageTracker(ZoneId zone) {
        this.zone = zone;
        today.date = LocalDate.now(zone).toString();
        lifetime.date = "lifetime";
    }

    private void rollover() {
        String d = LocalDate.now(zone).toString();
        if (!d.equals(today.date)) {
            today = new Day();
            today.date = d;
        }
    }

    public synchronized void load(Day savedToday, Day savedLifetime) {
        if (savedLifetime != null) lifetime = fix(savedLifetime);
        if (savedToday != null) today = fix(savedToday);
        rollover();
    }

    private static Day fix(Day d) {
        if (d.turnsByPlayer == null) d.turnsByPlayer = new HashMap<>();
        if (d.costByPlayer == null) d.costByPlayer = new HashMap<>();
        if (d.errorsByKind == null) d.errorsByKind = new HashMap<>();
        if (d.costByModel == null) d.costByModel = new HashMap<>();
        if (d.tokensByModel == null) d.tokensByModel = new HashMap<>();
        return d;
    }

    public synchronized double cost(ApiResponse.Usage u, double inPrice, double cachedPrice, double outPrice) {
        long uncached = Math.max(0, u.input() - u.cachedInput());
        return (uncached * inPrice + u.cachedInput() * cachedPrice + u.output() * outPrice) / 1_000_000.0;
    }

    /** [입력, 캐시 입력, 출력] 단가로 계산. */
    public double cost(ApiResponse.Usage u, double[] price) {
        return cost(u, price[0], price[1], price[2]);
    }

    public synchronized void recordRequest(String player, ApiResponse.Usage u, double costUsd) {
        recordRequest(player, u, costUsd, null);
    }

    public synchronized void recordRequest(String player, ApiResponse.Usage u, double costUsd, String model) {
        rollover();
        for (Day d : new Day[]{today, lifetime}) {
            if (model != null && !model.isEmpty()) {
                d.costByModel.merge(model, costUsd, Double::sum);
                d.tokensByModel.merge(model, u.input() + u.output(), Long::sum);
            }
            d.requests++;
            d.inputTokens += u.input();
            d.cachedTokens += u.cachedInput();
            d.outputTokens += u.output();
            d.reasoningTokens += u.reasoning();
            d.costUsd += costUsd;
            if (player != null) d.costByPlayer.merge(player, costUsd, Double::sum);
        }
    }

    public synchronized void recordTurn(String player, long latencyMs, boolean error, String errorKind) {
        rollover();
        for (Day d : new Day[]{today, lifetime}) {
            d.turns++;
            d.totalLatencyMs += latencyMs;
            if (player != null) d.turnsByPlayer.merge(player, 1L, Long::sum);
            if (error) {
                d.errors++;
                d.errorsByKind.merge(errorKind == null ? "unknown" : errorKind, 1L, Long::sum);
            }
        }
        recentLatency.addLast(new long[]{System.currentTimeMillis(), latencyMs, error ? 1 : 0});
        while (recentLatency.size() > 200) recentLatency.removeFirst();
    }

    /** 임베딩 요청 (기억 색인·검색 질의). 비용은 하루 예산에 함께 들어간다. */
    public synchronized void recordEmbedding(long tokens, double costUsd, String model) {
        rollover();
        for (Day d : new Day[]{today, lifetime}) {
            d.embeddingRequests++;
            d.embeddingTokens += tokens;
            d.costUsd += costUsd;
            if (model != null) {
                d.costByModel.merge(model, costUsd, Double::sum);
                d.tokensByModel.merge(model, tokens, Long::sum);
            }
        }
    }

    public synchronized long turnsToday(String player) {
        rollover();
        return today.turnsByPlayer.getOrDefault(player, 0L);
    }

    public synchronized double costToday() {
        rollover();
        return today.costUsd;
    }

    public synchronized Snapshot snapshot() {
        rollover();
        Snapshot s = new Snapshot();
        s.today = today;
        s.lifetime = lifetime;
        s.inFlight = inFlight;
        s.queued = queued;
        long n = 0, sum = 0, err = 0;
        for (long[] e : recentLatency) { n++; sum += e[1]; err += e[2]; }
        s.avgLatencyMs = n == 0 ? 0 : (double) sum / n;
        s.errorRate = n == 0 ? 0 : (double) err / n;
        return s;
    }

    public synchronized Day today() {
        rollover();
        return today;
    }

    public synchronized Day lifetime() {
        return lifetime;
    }
}
