package dev.newyear.countdown.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.openai.ApiException;
import dev.newyear.countdown.ai.openai.ApiResponse;
import dev.newyear.countdown.ai.openai.OpenAiClient;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 턴 실행기. 서버 메인 스레드를 막지 않도록 HTTP 와 도구 루프는 작업 스레드에서 돌고,
 * 세계를 읽고 바꾸는 도구 처리기는 서버 스레드로 넘겨 실행한다.
 * 같은 소원에 대한 요청은 동시에 하나만 진행하며, 진행 중에 들어온 말은 다음 턴으로 합친다.
 */
public final class WishAgent {
    private final WishEngine eng;
    private final OpenAiClient client;
    private final AgentRunner runner;
    private final ThreadPoolExecutor pool;
    private final AtomicInteger threadSeq = new AtomicInteger();

    private static final class InFlight {
        final OpenAiClient.CancelToken cancel = new OpenAiClient.CancelToken();
        boolean followUp;
        final List<String> merged = new ArrayList<>();
    }

    /** 서버 스레드에서만 접근. */
    private final Map<String, InFlight> inFlight = new HashMap<>();
    private final Map<UUID, Long> lastRequest = new HashMap<>();

    public WishAgent(WishEngine eng) {
        this.eng = eng;
        AiConfig cfg = AiConfig.get();
        this.client = new OpenAiClient(() -> {
            AiConfig c = AiConfig.get();
            return new OpenAiClient.Settings(c.effectiveApiBase(), AiConfig.apiKey(), Duration.ofSeconds(c.requestTimeoutSeconds), c.maxRetries);
        });
        this.runner = new AgentRunner(client);
        this.pool = new ThreadPoolExecutor(cfg.maxConcurrentRequests, cfg.maxConcurrentRequests, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.maxQueuedRequests), r -> {
            Thread t = new Thread(r, "WishAI-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.pool.allowCoreThreadTimeOut(true);
    }

    public OpenAiClient client() {
        return client;
    }

    public int queued() {
        return pool.getQueue().size();
    }

    public int active() {
        return pool.getActiveCount();
    }

    public boolean busy(String wishId) {
        return inFlight.containsKey(wishId);
    }

    public void shutdown() {
        for (InFlight f : inFlight.values()) f.cancel.cancel();
        pool.shutdownNow();
        try {
            pool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 킬 스위치: 대기열을 비우고 진행 중인 요청을 모두 취소한다 (실행기는 살려 둔다). */
    public void shutdownQueued() {
        pool.getQueue().clear();
        for (Map.Entry<String, InFlight> e : inFlight.entrySet()) {
            e.getValue().cancel.cancel();
            Wish w = eng.state().wishes.get(e.getKey());
            if (w != null) w.thinking = false;
        }
        inFlight.clear();
    }

    /** 진행 중인 요청 취소 (소원 취소 시). */
    public void cancel(String wishId, boolean hard) {
        InFlight f = inFlight.get(wishId);
        if (f != null && hard) f.cancel.cancel();
    }

    // ---------------------------------------------------------------- 제출 (서버 스레드)

    public void submit(TurnRequest req) {
        Wish w = req.wishId() == null ? null : eng.state().wishes.get(req.wishId());
        ServerPlayer p = req.player() == null ? null : eng.player(req.player());

        // 같은 소원이 이미 처리 중이면 합친다 (API 호출 병합)
        if (w != null) {
            InFlight f = inFlight.get(w.id);
            if (f != null) {
                f.followUp = true;
                if (req.message() != null) f.merged.add(req.message());
                return;
            }
        }

        String refusal = gate(req, p);
        if (refusal != null) {
            if (p != null && req.kind() != TurnRequest.Kind.AUTONOMOUS) eng.ui().spiritLine(p, w == null ? null : w.id, refusal, "sad");
            return;
        }
        AiConfig cfg = AiConfig.get();
        String turnId = "t-" + UUID.randomUUID().toString().substring(0, 8);
        // 요청 내용은 지금 서버 상태로 만든다
        List<JsonObject> input = new ArrayList<>();
        input.add(AgentRunner.developerMessage(ContextBuilder.turnContext(eng, req, w, p)));
        if (w != null) {
            int from = Math.max(0, w.conversation.size() - 16);
            // 마지막 줄은 이번 요청 메시지일 수 있으므로 중복을 피한다
            int to = w.conversation.size();
            if (req.kind() == TurnRequest.Kind.PLAYER && to > 0 && w.conversation.get(to - 1).role().equals("player")
                    && w.conversation.get(to - 1).text().equals(req.message())) to--;
            for (int i = from; i < to; i++) {
                Wish.Line l = w.conversation.get(i);
                if (l.role().equals("player")) input.add(AgentRunner.userMessage(l.text()));
                else if (l.role().equals("spirit")) input.add(AgentRunner.assistantMessage(l.text()));
            }
        }
        String msg = switch (req.kind()) {
            case PLAYER -> req.message();
            case UI -> req.message();
            case SCRIPT -> "[script notification] " + req.trigger() + (req.message() == null || req.message().isEmpty() ? "" : " | data: " + req.message());
            case AUTONOMOUS -> "[autonomous check] " + req.trigger()
                    + "\nDecide whether something should happen in the world now, based on memories and the real state. "
                    + "It is fine to do nothing (say = \"\"). If you act, keep it small, delightful, fair, connected to past events; never grief.";
        };
        input.add(AgentRunner.userMessage(msg));

        boolean autonomous = req.kind() == TurnRequest.Kind.AUTONOMOUS;
        AgentRunner.Request areq = new AgentRunner.Request(cfg.model, autonomous ? cfg.autonomyReasoningEffort : cfg.reasoningEffort,
                Prompts.instructions(), input, Tools.definitions(), Prompts.outputSchema(), "wish_reply",
                cfg.maxToolRounds, cfg.maxToolCallsPerTurn, cfg.maxOutputTokens, cfg.storeResponses, Prompts.VERSION, safetyId(req.player()));

        InFlight f = new InFlight();
        if (w != null) {
            inFlight.put(w.id, f);
            w.thinking = true;
            w.turns++;
            if (w.state == Wish.State.CREATED) eng.wishes().transition(w, Wish.State.ANALYZING, "thinking");
        }
        if (p != null && !autonomous) eng.ui().thinking(p, w == null ? null : w.id, true, "");
        if (req.player() != null && req.kind() == TurnRequest.Kind.PLAYER) lastRequest.put(req.player(), System.currentTimeMillis());

        Tools.Ctx ctx = new Tools.Ctx(eng, req, turnId, w);
        long started = System.currentTimeMillis();
        try {
            eng.usage().queued = pool.getQueue().size() + 1;
            pool.execute(() -> runTurn(req, areq, ctx, f, started));
        } catch (RejectedExecutionException e) {
            if (w != null) {
                inFlight.remove(w.id);
                w.thinking = false;
            }
            if (p != null) {
                eng.ui().thinking(p, w == null ? null : w.id, false, "");
                if (!autonomous) eng.ui().spiritLine(p, w == null ? null : w.id, "소원을 비는 이들이 너무 많구나… 잠시 뒤에 다시 말해 주겠느냐.", "sad");
            }
        }
    }

    /** 한도·예산·킬 스위치 확인. 막아야 하면 플레이어에게 보여 줄 문장을 돌려준다. */
    public String gate(TurnRequest req, ServerPlayer p) {
        AiConfig cfg = AiConfig.get();
        if (!eng.aiEnabled()) return "지금은 별빛이 잠들어 있구나. (AI 기능이 꺼져 있습니다)";
        if (!AiConfig.hasApiKey()) return "하늘과 이어지는 실이 끊어져 있구나… (서버에 OPENAI_API_KEY 가 설정되지 않았습니다)";
        if (cfg.dailyBudgetUsd > 0 && eng.usage().costToday() >= cfg.dailyBudgetUsd) {
            return "오늘은 별빛을 너무 많이 썼구나. 내일 다시 빌어 보거라. (서버 일일 AI 예산 소진)";
        }
        if (req.player() != null && req.kind() == TurnRequest.Kind.PLAYER) {
            String u = req.player().toString();
            if (eng.usage().turnsToday(u) >= cfg.perPlayerDailyTurns) return "오늘은 충분히 이야기했단다. 내일 또 오거라. (하루 대화 한도)";
            Long last = lastRequest.get(req.player());
            if (last != null && System.currentTimeMillis() - last < cfg.perPlayerCooldownSeconds * 1000L) return "숨 좀 고르고 말하거라.";
        }
        return null;
    }

    private static String safetyId(UUID player) {
        if (player == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("wishai:" + player).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 실행 (작업 스레드)

    private void runTurn(TurnRequest req, AgentRunner.Request areq, Tools.Ctx ctx, InFlight f, long started) {
        AgentRunner.Result result = null;
        ApiException error = null;
        String who = req.player() == null ? null : req.player().toString();
        eng.usage().inFlight++;
        try {
            AiConfig cfg = AiConfig.get();
            result = runner.run(areq, (name, args) -> onServer(() -> Tools.execute(ctx, name, args)), f.cancel,
                    u -> eng.usage().recordRequest(who, u, eng.usage().cost(u, cfg.priceInputPerMTok, cfg.priceCachedInputPerMTok, cfg.priceOutputPerMTok)),
                    toolName -> onServerAsync(() -> {
                        ServerPlayer p = ctx.player();
                        if (p != null && req.kind() != TurnRequest.Kind.AUTONOMOUS) eng.ui().thinking(p, ctx.wish == null ? null : ctx.wish.id, true, statusLabel(toolName));
                    }));
        } catch (ApiException e) {
            error = e;
        } catch (RuntimeException e) {
            NewYearCountdown.LOGGER.error("[WishAI] turn crashed", e);
            error = new ApiException(ApiException.Kind.SERVER, "internal: " + e.getClass().getSimpleName());
        } finally {
            eng.usage().inFlight--;
            eng.usage().queued = pool.getQueue().size();
        }
        long latency = System.currentTimeMillis() - started;
        eng.usage().recordTurn(who, latency, error != null, error == null ? null : error.kind.name());
        AgentRunner.Result fr = result;
        ApiException fe = error;
        onServerAsync(() -> finish(req, ctx, f, fr, fe));
    }

    private static String statusLabel(String tool) {
        return switch (tool) {
            case "search_memories", "get_wish_history" -> "기억을 더듬는 중";
            case "validate_script", "activate_script", "update_script", "run_script" -> "마법을 엮는 중";
            case "get_player_context", "inspect_entities", "inspect_region", "get_world_context", "get_active_content" -> "세상을 살피는 중";
            case "create_quest", "propose_contract" -> "계약서를 쓰는 중";
            default -> "";
        };
    }

    /** 작업 스레드에서 서버 스레드로 넘겨 실행하고 결과를 기다린다 (서버가 멈추면 시간 초과). */
    private String onServer(java.util.function.Supplier<String> task) {
        if (!eng.server().isRunning()) return Tools.err("Server is shutting down");
        CompletableFuture<String> fut = eng.server().submit(task);
        try {
            return fut.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Tools.err("interrupted");
        } catch (Exception e) {
            return Tools.err("Tool timed out or failed on the server thread: " + e.getClass().getSimpleName());
        }
    }

    private void onServerAsync(Runnable r) {
        if (eng.server().isRunning()) eng.server().execute(() -> {
            if (WishEngine.get() == eng) r.run();
        });
    }

    // ---------------------------------------------------------------- 마무리 (서버 스레드)

    private void finish(TurnRequest req, Tools.Ctx ctx, InFlight f, AgentRunner.Result result, ApiException error) {
        Wish w = ctx.wish;
        if (w != null) {
            inFlight.remove(w.id);
            w.thinking = false;
        }
        ServerPlayer p = ctx.player();
        boolean autonomous = req.kind() == TurnRequest.Kind.AUTONOMOUS;
        if (p != null && !autonomous) eng.ui().thinking(p, w == null ? null : w.id, false, "");

        if (error != null) {
            NewYearCountdown.LOGGER.warn("[WishAI] turn failed ({}): {}", error.kind, error.getMessage());
            if (w != null) w.failures++;
            if (p != null && !autonomous && error.kind != ApiException.Kind.CANCELLED) {
                eng.ui().spiritLine(p, w == null ? null : w.id, friendly(error), "sad");
            }
            if (w != null && (w.state == Wish.State.ANALYZING || w.state == Wish.State.CREATED)) {
                eng.wishes().transition(w, Wish.State.NEGOTIATING, "AI error: " + error.kind + " (player may retry)");
            }
            if (w != null && w.failures >= 4 && !w.state.terminal() && w.scriptIds.isEmpty()) {
                eng.wishes().transition(w, Wish.State.FAILED, "AI unavailable: " + error.kind);
            }
            return;
        }
        if (w != null) w.failures = 0;
        JsonObject j = result.json == null ? new JsonObject() : result.json;
        String say = str(j, "say");
        String mood = str(j, "mood");
        boolean expect = j.has("expect_reply") && j.get("expect_reply").isJsonPrimitive() && j.get("expect_reply").getAsBoolean();
        List<String> quick = new ArrayList<>();
        if (j.has("quick_replies") && j.get("quick_replies").isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray("quick_replies")) if (e.isJsonPrimitive() && quick.size() < 4) quick.add(dev.newyear.countdown.ai.script.api.McUtil.clean(e.getAsString(), 40));
        }
        say = dev.newyear.countdown.ai.script.api.McUtil.clean(say, 800);
        if (!say.isBlank()) {
            if (autonomous) {
                if (p != null) {
                    eng.ui().spiritLine(p, w == null ? null : w.id, say, mood, quick, expect);
                    eng.ui().notify(p, eng.spiritName(), say, "mystic", 8);
                }
            } else {
                eng.ui().spiritLine(p, w == null ? null : w.id, say, mood, quick, expect);
            }
        } else if (w != null && autonomous && w.conversation.isEmpty()) {
            // 자율 판단 결과 아무것도 하지 않음: 빈 기록은 지운다
            if (w.scriptIds.isEmpty() && w.questIds.isEmpty()) eng.state().wishes.remove(w.id);
        }

        // 상태 추론: AI 가 명시하지 않았을 때
        if (w != null && !w.state.terminal() && eng.state().wishes.containsKey(w.id)) {
            boolean activeWork = false;
            for (String qid : w.questIds) {
                Quest q = eng.state().quests.get(qid);
                if (q != null && q.state == Quest.State.ACTIVE) activeWork = true;
            }
            for (String sid : w.scriptIds) {
                var r = eng.state().scripts.get(sid);
                // 지속 능력(lasting)은 소원이 이뤄진 결과물이므로 '진행 중인 일'로 보지 않는다
                if (r != null && !r.lasting && (r.state.live() || r.state == dev.newyear.countdown.ai.script.ScriptRecord.State.PENDING_APPROVAL) && r.durationTicks != 2400) activeWork = true;
            }
            String wid = w.id;
            // AI 가 직접 띄운 UI(답이 다음 턴으로 오는 것)만 '대화 대기'로 본다. 스크립트가 띄운 UI 는 진행 중인 일이다.
            boolean waitingUi = eng.ui().pending().values().stream().anyMatch(x -> wid.equals(x.wishId) && (x.responder == null || x.responder.scriptId() == null));
            if (eng.ui().pending().values().stream().anyMatch(x -> wid.equals(x.wishId) && x.responder != null && x.responder.scriptId() != null)) activeWork = true;
            if (expect || waitingUi) {
                if (w.state == Wish.State.ANALYZING || w.state == Wish.State.CREATED) eng.wishes().transition(w, Wish.State.NEGOTIATING, "awaiting player reply");
            } else if (activeWork) {
                if (w.state != Wish.State.ACTIVE && w.state != Wish.State.AWAITING_APPROVAL && w.state != Wish.State.AWAITING_ACCEPTANCE) {
                    eng.wishes().transition(w, Wish.State.ACTIVE, "work in progress");
                }
            } else if (w.state != Wish.State.AWAITING_ACCEPTANCE && w.state != Wish.State.AWAITING_APPROVAL) {
                w.summary = w.summary == null || w.summary.isEmpty() ? say : w.summary;
                eng.wishes().transition(w, Wish.State.COMPLETED, say.isEmpty() ? "conversation ended" : say);
            }
        }

        if (f.followUp && w != null && !w.state.terminal()) {
            String merged = String.join("\n", f.merged);
            eng.agent().submit(new TurnRequest(w.id, req.player(), TurnRequest.Kind.PLAYER, merged.isEmpty() ? "(continues)" : merged, null));
        }
        if (result.trace.size() > 0) {
            NewYearCountdown.LOGGER.info("[WishAI] turn {} done: {} api calls, {} tool calls, usage in={} cached={} out={}", ctx.turnId,
                    result.apiCalls, result.trace.size(), result.usage.input(), result.usage.cachedInput(), result.usage.output());
        }
    }

    private static String str(JsonObject j, String k) {
        JsonElement e = j.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static String friendly(ApiException e) {
        return switch (e.kind) {
            case NO_API_KEY -> "하늘과 이어지는 실이 끊어져 있구나… (서버에 OPENAI_API_KEY 가 설정되지 않았습니다)";
            case AUTH -> "별빛의 문이 굳게 잠겨 있구나. (AI 인증에 실패했습니다 — 관리자에게 알려 주세요)";
            case RATE_LIMIT -> "하늘이 소원들로 붐비는구나. 잠시 뒤 다시 말해 주겠느냐.";
            case QUOTA, BUDGET -> "오늘 쓸 수 있는 별빛을 모두 썼구나. 다음에 다시 빌어 보거라.";
            case TIMEOUT -> "생각이 너무 깊어져 길을 잃었구나… 한 번만 더 말해 주겠느냐.";
            case NETWORK, SERVER -> "별빛이 잠시 흐려졌구나. 조금 뒤에 다시 빌어 보거라.";
            case BAD_REQUEST, INVALID_OUTPUT -> "말이 엉켜 버렸구나. 다르게 다시 말해 주겠느냐.";
            case DISABLED -> "지금은 별빛이 잠들어 있구나.";
            case CANCELLED -> "";
        };
    }

    // ---------------------------------------------------------------- 요약 (도구 없는 가벼운 호출)

    /** 기억 요약. 작업 스레드에서 돌고 결과는 콜백(서버 스레드)으로. */
    public void summarize(String text, java.util.function.Consumer<String> onDone) {
        if (!eng.aiEnabled() || !AiConfig.hasApiKey()) return;
        AiConfig cfg = AiConfig.get();
        if (cfg.dailyBudgetUsd > 0 && eng.usage().costToday() >= cfg.dailyBudgetUsd * 0.9) return;
        List<JsonObject> input = new ArrayList<>();
        input.add(AgentRunner.userMessage("Summarize these memories of a Minecraft player's history with the wish spirit into 2-4 short Korean sentences. "
                + "Keep names, places, promises and unresolved stories. Memories:\n" + text));
        String model = cfg.summaryModel == null || cfg.summaryModel.isBlank() ? cfg.model : cfg.summaryModel;
        AgentRunner.Request areq = new AgentRunner.Request(model, "low", "You compress game memories faithfully. Never invent facts.", input,
                new JsonArray(), Prompts.summarySchema(), "memory_summary", 1, 0, 800, cfg.storeResponses, Prompts.VERSION + "-sum", null);
        try {
            pool.execute(() -> {
                try {
                    AgentRunner.Result r = runner.run(areq, (a, b) -> "{}", null,
                            u -> eng.usage().recordRequest(null, u, eng.usage().cost(u, cfg.priceInputPerMTok, cfg.priceCachedInputPerMTok, cfg.priceOutputPerMTok)), null);
                    String s = r.json != null && r.json.has("summary") ? r.json.get("summary").getAsString() : r.text;
                    if (s != null && !s.isBlank()) onServerAsync(() -> onDone.accept(s));
                } catch (ApiException e) {
                    NewYearCountdown.LOGGER.info("[WishAI] memory summary skipped: {}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 바쁘면 다음 기회에
        }
    }

    public ApiResponse.Usage zero() {
        return ApiResponse.Usage.ZERO;
    }
}
