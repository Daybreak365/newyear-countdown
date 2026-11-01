package dev.newyear.countdown.ai.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.AiSettings;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.agent.ContextBuilder;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.ScriptManager;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.ScriptValidator;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.usage.UsageTracker;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 화면(클라이언트 AdminScreen)과 /wishai 명령이 함께 쓰는 관리 기능.
 * 요청은 {"op": ..., ...} JSON, 응답은 UI 채널의 {"op":"admin","kind":op,...}. 모든 요청은 서버에서 권한(OP 2)을 다시 확인한다.
 */
public final class AdminService {
    private final WishEngine eng;

    public AdminService(WishEngine eng) {
        this.eng = eng;
    }

    // ---------------------------------------------------------------- 진입점

    /** 클라이언트 관리자 화면의 요청 (서버 스레드). */
    public void handle(ServerPlayer p, String json) {
        if (!WishEngine.isAdmin(p)) {
            NewYearCountdown.LOGGER.warn("[WishAI] non-admin {} sent an admin request", p.getGameProfile().name());
            return;
        }
        JsonObject req;
        try {
            req = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String op = str(req, "op", "");
        JsonObject out;
        try {
            out = switch (op) {
                case "open" -> {
                    JsonObject o = overview();
                    o.addProperty("kind", "open");
                    yield o;
                }
                case "overview" -> overview();
                case "config" -> AiSettings.snapshot(AiConfig.get());
                case "config_save" -> saveConfig(req, p.getGameProfile().name());
                case "session_key" -> sessionKey(req, p.getUUID());
                case "scripts" -> scripts(str(req, "filter", "live"));
                case "script" -> script(str(req, "id", ""));
                case "script_action" -> scriptAction(str(req, "id", ""), str(req, "action", ""), p.getGameProfile().name(), p);
                case "script_edit" -> scriptEdit(str(req, "id", ""), str(req, "code", ""), str(req, "reason", "edited in admin GUI"),
                        p.getGameProfile().name(), req.has("apply") && req.get("apply").getAsBoolean());
                case "preview_data" -> previewData(str(req, "id", ""));
                case "wishes" -> wishes(str(req, "filter", "open"));
                case "wish_cancel" -> {
                    boolean ok = eng.wishes().cancel(str(req, "id", ""), "cancelled by admin " + p.getGameProfile().name());
                    JsonObject o = msg(ok ? "소원을 취소했습니다." : "열린 소원이 아닙니다.");
                    o.addProperty("refresh", "wishes");
                    yield o;
                }
                case "memory" -> memory(str(req, "who", "world"));
                case "memory_forget" -> {
                    MemoryRecord r = eng.memory().get(str(req, "id", ""));
                    boolean ok = r != null && eng.memory().forget(r.id);
                    JsonObject o = msg(ok ? "기억을 지웠습니다." : "그런 기억이 없습니다.");
                    o.addProperty("refresh", "memory");
                    yield o;
                }
                case "memory_unshare" -> {
                    int n = eng.memory().unshare(str(req, "id", ""), null, null);
                    JsonObject o = msg("공유 " + n + "개를 거뒀습니다.");
                    o.addProperty("refresh", "memory");
                    yield o;
                }
                case "killswitch" -> {
                    boolean on = req.has("on") && req.get("on").getAsBoolean();
                    int n = on ? eng.killSwitch(p.getGameProfile().name()) : eng.release(p.getGameProfile().name());
                    JsonObject o = msg(on ? "킬 스위치 ON — 스크립트 " + n + "개 일시정지" : "킬 스위치 OFF — 스크립트 " + n + "개 재개");
                    o.addProperty("refresh", "overview");
                    yield o;
                }
                case "reload" -> {
                    AiConfig.load();
                    JsonObject o = msg("설정을 다시 읽었습니다 (model=" + AiConfig.get().model + ")");
                    o.addProperty("refresh", "overview");
                    yield o;
                }
                case "save" -> {
                    eng.scripts().saveAllStates();
                    eng.writeNow();
                    yield msg("저장했습니다.");
                }
                default -> msg("알 수 없는 요청: " + op);
            };
        } catch (RuntimeException e) {
            NewYearCountdown.LOGGER.warn("[WishAI] admin request {} failed", op, e);
            out = msg("요청 처리 실패: " + e.getClass().getSimpleName());
        }
        out.addProperty("op", "admin");
        if (!out.has("kind")) out.addProperty("kind", op);
        eng.ui().send(p, out);
    }

    /** 관리자 화면을 연다 (/wishai gui). */
    public void open(ServerPlayer p) {
        JsonObject o = overview();
        o.addProperty("op", "admin");
        o.addProperty("kind", "open");
        eng.ui().send(p, o);
    }

    // ---------------------------------------------------------------- 조회

    /** Called only after the OP check in handle. No key, prefix or suffix is ever returned or logged. */
    private JsonObject sessionKey(JsonObject req, UUID player) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", "session_key");
        out.addProperty("ok", true);
        JsonElement secret = req.remove("key");
        try {
            switch (str(req, "action", "status")) {
                case "set" -> {
                    if (secret == null || !secret.isJsonPrimitive() || !secret.getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("API 키를 입력하세요.");
                    AiConfig.setSessionApiKey(player, secret.getAsString());
                    out.addProperty("text", "임시 키를 적용했습니다. 다음 AI 요청부터 사용합니다.");
                }
                case "clear" -> {
                    AiConfig.clearSessionApiKey();
                    out.addProperty("text", "임시 키를 삭제했습니다. 환경 변수 키가 있으면 사용합니다.");
                }
                case "status" -> out.addProperty("text", "키는 서버 메모리에서만 임시로 보관합니다.");
                default -> throw new IllegalArgumentException("알 수 없는 키 설정 요청입니다.");
            }
        } catch (IllegalArgumentException e) {
            out.addProperty("ok", false);
            out.addProperty("text", e.getMessage());
        }
        out.addProperty("source", AiConfig.apiKeySource());
        return out;
    }

    private JsonObject saveConfig(JsonObject req, String actor) {
        try {
            JsonObject changes = req.has("changes") && req.get("changes").isJsonObject() ? req.getAsJsonObject("changes") : null;
            JsonObject expected = req.has("expected") && req.get("expected").isJsonObject() ? req.getAsJsonObject("expected") : null;
            AiConfig next = AiSettings.prepare(AiConfig.get(), changes, expected);
            AiConfig.replaceAndSave(next);
            eng.memory().semanticWeight = next.embeddingWeight;
            eng.memory().semanticFloor = next.embeddingMinSimilarity;
            JsonObject out = AiSettings.snapshot(next);
            out.addProperty("kind", "config_result");
            out.addProperty("ok", true);
            out.addProperty("text", AiSettings.requiresRestart(changes)
                    ? "저장했습니다. 재시작 표시 항목은 서버/월드를 다시 열면 적용됩니다."
                    : "저장했습니다. 다음 요청·실행부터 적용됩니다.");
            NewYearCountdown.LOGGER.info("[WishAI] {} updated settings: {}", actor, changes.keySet());
            return out;
        } catch (IllegalArgumentException | java.io.IOException e) {
            JsonObject out = new JsonObject();
            out.addProperty("kind", "config_result");
            out.addProperty("ok", false);
            out.addProperty("text", e instanceof java.io.IOException ? "설정 파일 저장 실패. 서버 폴더의 쓰기 권한을 확인하세요." : e.getMessage());
            return out;
        }
    }

    public JsonObject overview() {
        AiConfig cfg = AiConfig.get();
        UsageTracker.Snapshot s = eng.usage().snapshot();
        JsonObject o = new JsonObject();
        o.addProperty("kind", "overview");
        o.addProperty("enabled", cfg.enabled);
        o.addProperty("killSwitch", eng.state().killSwitch);
        o.addProperty("model", cfg.model);
        o.addProperty("effort", cfg.reasoningEffort);
        o.addProperty("apiKey", AiConfig.hasApiKey());
        o.addProperty("apiKeySource", AiConfig.apiKeySource());
        o.addProperty("turnsToday", s.today.turns);
        o.addProperty("requestsToday", s.today.requests);
        o.addProperty("errorsToday", s.today.errors);
        o.addProperty("costToday", s.today.costUsd);
        o.addProperty("budget", cfg.dailyBudgetUsd);
        o.addProperty("costLifetime", s.lifetime.costUsd);
        o.addProperty("inputTokens", s.today.inputTokens);
        o.addProperty("cachedTokens", s.today.cachedTokens);
        o.addProperty("outputTokens", s.today.outputTokens);
        o.addProperty("reasoningTokens", s.today.reasoningTokens);
        o.addProperty("embeddingTokens", s.today.embeddingTokens);
        o.addProperty("avgLatencyMs", s.avgLatencyMs);
        o.addProperty("errorRate", s.errorRate);
        o.addProperty("active", eng.agent().active());
        o.addProperty("queued", eng.agent().queued());
        o.addProperty("liveScripts", eng.scripts().live().size());
        o.addProperty("scripts", eng.state().scripts.size());
        int pending = 0;
        for (ScriptRecord r : eng.state().scripts.values()) if (r.state == ScriptRecord.State.PENDING_APPROVAL) pending++;
        o.addProperty("pendingApproval", pending);
        int open = 0;
        for (Wish w : eng.state().wishes.values()) if (!w.state.terminal()) open++;
        o.addProperty("openWishes", open);
        o.addProperty("wishes", eng.state().wishes.size());
        o.addProperty("quests", eng.state().quests.size());
        o.addProperty("memories", eng.memory().all().size());
        o.addProperty("vectors", eng.semantic().size());
        o.addProperty("semantic", cfg.embeddingsEnabled ? (eng.semanticAvailable() ? "on" : "waiting") : "off");
        o.addProperty("droppedEvents", eng.scripts().droppedEvents());
        JsonObject byModel = new JsonObject();
        s.today.costByModel.forEach(byModel::addProperty);
        o.add("costByModel", byModel);
        return o;
    }

    public JsonObject scripts(String filter) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "scripts");
        o.addProperty("filter", filter);
        JsonArray arr = new JsonArray();
        List<ScriptRecord> list = new ArrayList<>(eng.state().scripts.values());
        list.sort((a, b) -> {
            int ra = rank(a.state), rb = rank(b.state);
            return ra != rb ? ra - rb : Long.compare(b.created, a.created);
        });
        for (ScriptRecord r : list) {
            boolean show = switch (filter) {
                case "all" -> true;
                case "pending" -> r.state == ScriptRecord.State.PENDING_APPROVAL;
                default -> r.state.live() || r.state == ScriptRecord.State.PENDING_APPROVAL || r.state == ScriptRecord.State.SUSPENDED;
            };
            if (!show) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", r.id);
            j.addProperty("name", r.name);
            j.addProperty("label", r.abilityLabel);
            j.addProperty("owner", r.ownerName == null ? "world" : r.ownerName);
            j.addProperty("state", r.state.name());
            j.addProperty("version", r.version);
            j.addProperty("errors", r.errors);
            j.addProperty("blocks", r.blocks.size());
            j.addProperty("preview", r.preview.size());
            arr.add(j);
            if (arr.size() >= 200) break;
        }
        o.add("list", arr);
        return o;
    }

    private static int rank(ScriptRecord.State s) {
        return switch (s) {
            case PENDING_APPROVAL -> 0;
            case ACTIVE -> 1;
            case SUSPENDED -> 2;
            default -> 3;
        };
    }

    public JsonObject script(String id) {
        ScriptRecord r = eng.state().scripts.get(id);
        if (r == null) return msg("그런 스크립트가 없습니다: " + id);
        JsonObject o = new JsonObject();
        o.addProperty("kind", "script");
        o.addProperty("id", r.id);
        o.addProperty("name", r.name);
        o.addProperty("label", r.abilityLabel);
        o.addProperty("description", r.description);
        o.addProperty("owner", r.ownerName == null ? "world" : r.ownerName);
        o.addProperty("wish", r.wishId == null ? "" : r.wishId);
        o.addProperty("state", r.state.name());
        o.addProperty("reason", r.stateReason);
        o.addProperty("version", r.version);
        o.addProperty("lasting", r.lasting);
        o.addProperty("autonomous", r.autonomous);
        o.addProperty("duration", r.durationTicks < 0 ? -1 : r.durationTicks / 20);
        o.addProperty("left", r.durationTicks > 0 ? Math.max(0, (r.expiresTick - eng.tick()) / 20) : -1);
        o.addProperty("runs", r.runs);
        o.addProperty("steps", r.steps);
        o.addProperty("heap", r.heapBytes);
        o.addProperty("errors", r.errors);
        o.addProperty("lastError", r.lastError);
        o.addProperty("entities", r.entities.size());
        o.addProperty("kept", r.keptEntities.size());
        o.addProperty("blocks", r.blocks.size());
        o.addProperty("conflicts", r.blockConflicts.size());
        o.addProperty("preview", r.preview.size());
        o.addProperty("goals", r.goals.size());
        o.addProperty("models", r.models.size());
        o.addProperty("timers", r.timers.size());
        o.addProperty("approvedBy", r.approvedBy == null ? "" : r.approvedBy);
        o.addProperty("code", r.code);
        JsonArray ev = new JsonArray();
        var running=eng.scripts().instance(r.id);
        if(running==null)for(String s:r.subscriptions)ev.add(s);
        else running.listeners.entrySet().stream().filter(e->!e.getValue().isEmpty()).map(java.util.Map.Entry::getKey).sorted().forEach(ev::add);
        if(running!=null)o.addProperty("listenerCount",running.listenerCount());
        o.add("events", ev);
        JsonArray api = new JsonArray();
        for (String s : r.apiCalls) api.add(s);
        o.add("api", api);
        JsonArray hist = new JsonArray();
        for (ScriptRecord.Version v : r.history) hist.add("v" + v.version + " — " + (v.reason == null ? "" : v.reason));
        o.add("history", hist);
        if (r.continuations != null) {
            int t = r.continuations.has("timers") ? r.continuations.getAsJsonArray("timers").size() : 0;
            int l = r.continuations.has("listeners") ? r.continuations.getAsJsonArray("listeners").size() : 0;
            o.addProperty("resumable", t + " timers, " + l + " listeners"
                    + (r.continuations.has("skipped") ? " (" + r.continuations.getAsJsonArray("skipped").size() + " not savable)" : ""));
        }
        o.addProperty("stateJson", clip(r.stateJson.toString(), 1500));
        return o;
    }

    public JsonObject previewData(String id) {
        ScriptRecord r = eng.state().scripts.get(id);
        if (r == null) return msg("그런 스크립트가 없습니다");
        // 실행된 스크립트는 실제로 바꾼 블록, 아직이면 검증 때의 계획
        List<ScriptRecord.BlockChange> blocks = r.preview.isEmpty() || (r.state.live() && !r.blocks.isEmpty()) ? r.blocks : r.preview;
        JsonObject o = eng.ui().previewJson("admin-" + r.id, r.name, blocks, 0);
        o.addProperty("op", "admin");
        o.addProperty("kind", "preview_data");
        o.addProperty("script", r.id);
        o.addProperty("source", blocks == r.blocks ? "placed" : "planned");
        return o;
    }

    public JsonObject wishes(String filter) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "wishes");
        JsonArray arr = new JsonArray();
        List<Wish> list = new ArrayList<>(eng.state().wishes.values());
        list.sort((a, b) -> Long.compare(b.created, a.created));
        for (Wish w : list) {
            if ("open".equals(filter) && w.state.terminal()) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", w.id);
            j.addProperty("player", w.playerName == null ? "?" : w.playerName);
            j.addProperty("text", clip(w.text, 120));
            j.addProperty("state", w.state.name());
            j.addProperty("turns", w.turns);
            j.addProperty("scripts", w.scriptIds.size());
            j.addProperty("quests", w.questIds.size());
            j.addProperty("outcome", clip(w.outcome == null ? "" : w.outcome, 120));
            j.addProperty("created", w.created);
            arr.add(j);
            if (arr.size() >= 120) break;
        }
        o.add("list", arr);
        return o;
    }

    public JsonObject memory(String who) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "memory");
        o.addProperty("who", who);
        List<MemoryRecord> list = new ArrayList<>();
        if ("world".equals(who)) {
            for (MemoryRecord r : eng.memory().all()) if (r.scope == MemoryRecord.Scope.WORLD) list.add(r);
        } else {
            var prof = eng.state().findPlayer(who);
            if (prof != null) {
                list.addAll(eng.memory().ofPlayer(prof.uuid));
                o.addProperty("locked", prof.memoryLocked);
            }
        }
        list.sort((a, b) -> Long.compare(b.created, a.created));
        JsonArray arr = new JsonArray();
        for (MemoryRecord r : list) {
            JsonObject j = new JsonObject();
            j.addProperty("id", r.id);
            j.addProperty("kind", r.kind);
            j.addProperty("text", clip(r.text, 300));
            j.addProperty("importance", r.importance);
            j.addProperty("archived", r.archived);
            j.addProperty("summary", r.summary);
            j.addProperty("shared", r.shared() ? ContextBuilder.sharedNames(eng, r) : "");
            j.addProperty("vector", eng.semantic().vectorOf(r) != null);
            if (r.thread != null) j.addProperty("thread", r.thread);
            j.addProperty("created", r.created);
            arr.add(j);
            if (arr.size() >= 100) break;
        }
        o.add("list", arr);
        JsonArray players = new JsonArray();
        players.add("world");
        for (var prof : eng.state().players.values()) if (prof.name != null) players.add(prof.name);
        o.add("players", players);
        return o;
    }

    // ---------------------------------------------------------------- 동작 (명령과 화면이 같이 쓴다)

    public JsonObject scriptAction(String id, String action, String by, ServerPlayer viewer) {
        ScriptRecord r = eng.state().scripts.get(id);
        if (r == null) return msg("그런 스크립트가 없습니다: " + id);
        String who = "admin " + by;
        String text;
        switch (action) {
            case "stop" -> {
                eng.scripts().stop(id, ScriptRecord.State.STOPPED, "stopped by " + who, false);
                text = "멈췄습니다.";
            }
            case "rollback", "rollback_force" -> {
                boolean force = action.equals("rollback_force");
                if (r.state.live()) eng.scripts().stop(id, ScriptRecord.State.STOPPED, "rolled back by " + who, false);
                ScriptManager.RollbackReport rep = eng.scripts().revertBlocks(r, false, force);
                text = (force ? "강제 " : "") + "롤백: " + rep.describe() + (rep.conflicts() > 0 && !force ? " — 그래도 되돌리려면 rollback force" : "");
            }
            case "suspend" -> {
                var inst = eng.scripts().instance(id);
                if (inst != null) eng.scripts().saveState(inst);
                eng.scripts().live().remove(id);
                r.state = ScriptRecord.State.SUSPENDED;
                r.stateReason = "suspended by " + who;
                text = "일시정지했습니다.";
            }
            case "resume" -> {
                if (r.state.ended()) r.state = ScriptRecord.State.SUSPENDED;
                var res = eng.scripts().activate(r, true);
                text = res.ok() ? "재개했습니다." : "재개 실패: " + res.error();
            }
            case "revert" -> {
                if (r.history.isEmpty()) return msg("이전 버전이 없습니다.");
                ScriptRecord.Version v = r.history.remove(r.history.size() - 1);
                r.code = v.code;
                r.version = v.version;
                if (r.state.live() || r.state == ScriptRecord.State.SUSPENDED) {
                    var res = eng.scripts().activate(r, true);
                    text = res.ok() ? "v" + v.version + " 으로 되돌렸습니다." : "되돌렸지만 실행 실패: " + res.error();
                } else text = "v" + v.version + " 으로 되돌렸습니다 (실행 중이 아님).";
            }
            case "delete" -> {
                if (r.state.live()) eng.scripts().stop(id, ScriptRecord.State.STOPPED, "deleted by " + who, true);
                eng.state().scripts.remove(id);
                text = "삭제했습니다.";
            }
            case "approve", "deny" -> text = approve(r, action.equals("approve"), by);
            case "preview" -> {
                if (viewer == null) return msg("플레이어만 월드 미리보기를 볼 수 있습니다.");
                List<ScriptRecord.BlockChange> blocks = r.preview.isEmpty() || (r.state.live() && !r.blocks.isEmpty()) ? r.blocks : r.preview;
                if (blocks.isEmpty()) return msg("미리 볼 블록 변경이 없습니다.");
                eng.ui().blockPreview(viewer, "admin-" + r.id, r.name, blocks, 120);
                text = "월드 미리보기를 120초 동안 보여 줍니다 (" + blocks.size() + "칸).";
            }
            default -> {
                return msg("알 수 없는 동작: " + action);
            }
        }
        NewYearCountdown.LOGGER.info("[WishAI] {} {} script {}: {}", who, action, id, text);
        JsonObject o = msg(id + ": " + text);
        o.addProperty("refresh", action.equals("delete") ? "scripts" : "script");
        o.addProperty("id", id);
        return o;
    }

    public String approve(ScriptRecord r, boolean yes, String by) {
        if (r.state != ScriptRecord.State.PENDING_APPROVAL) return "승인 대기 중인 스크립트가 아닙니다.";
        Wish w = r.wishId == null ? null : eng.state().wishes.get(r.wishId);
        ServerPlayer owner = r.owner == null ? null : eng.player(UUID.fromString(r.owner));
        if (yes) {
            r.approvedBy = by;
            var res = eng.scripts().activate(r, false);
            if (w != null && !w.state.terminal()) eng.wishes().transition(w, Wish.State.ACTIVE, "approved by admin");
            if (owner != null) eng.ui().notify(owner, "별들의 허락", "'" + (r.abilityLabel.isEmpty() ? r.name : r.abilityLabel) + "'이(가) 깨어났습니다.", "success", 6);
            return res.ok() ? "승인 및 실행했습니다." : "승인했지만 실행 실패: " + res.error();
        }
        r.state = ScriptRecord.State.STOPPED;
        r.stateReason = "denied by " + by;
        if (w != null && !w.state.terminal()) eng.wishes().transition(w, Wish.State.REJECTED, "admin denied the script");
        if (owner != null) eng.ui().notify(owner, "별들의 거절", "이 소원은 하늘이 허락하지 않았습니다.", "warning", 6);
        return "거부했습니다.";
    }

    /**
     * 관리자가 코드를 직접 고친다: 검증 결과를 돌려주고, apply 면 새 버전으로 바꿔 끼운다 (이전 버전은 기록에 남아 revert 가능).
     * 실행 중(또는 일시정지)이면 state 를 지킨 채 새 코드로 다시 시작한다. 승인 대기 중이면 대기 상태를 유지한다.
     */
    public JsonObject scriptEdit(String id, String code, String reason, String by, boolean apply) {
        ScriptRecord r = eng.state().scripts.get(id);
        if (r == null) return msg("그런 스크립트가 없습니다: " + id);
        if (code.length() > 30_000) return msg("코드가 너무 깁니다 (최대 30000자).");
        ScriptRecord trial = new ScriptRecord();
        trial.id = r.id;
        trial.name = r.name;
        trial.code = code;
        trial.owner = r.owner;
        trial.ownerName = r.ownerName;
        trial.wishId = r.wishId;
        trial.anchor = r.anchor;
        trial.dimension = r.dimension;
        trial.durationTicks = r.durationTicks;
        trial.autonomous = r.autonomous;
        ScriptValidator.Report rep = ScriptValidator.validate(trial, List.of(), 200);
        JsonObject o = new JsonObject();
        o.addProperty("kind", "edit_result");
        o.addProperty("id", id);
        o.add("validation", rep.toJson());
        o.addProperty("ok", rep.ok);
        if (!rep.ok || !apply) {
            o.addProperty("applied", false);
            o.addProperty("text", rep.ok ? "검증 통과 — 적용하려면 '적용'" : "검증 실패: " + String.join(" / ", rep.errors));
            return o;
        }
        ScriptRecord.Version v = new ScriptRecord.Version();
        v.version = r.version;
        v.code = r.code;
        v.time = System.currentTimeMillis();
        v.reason = McUtil.clean("before admin edit by " + by + ": " + reason, 200);
        r.history.add(v);
        while (r.history.size() > 10) r.history.remove(0);
        var inst = eng.scripts().instance(r.id);
        if (inst != null) eng.scripts().saveState(inst);
        r.code = code;
        r.version++;
        r.errors = 0;
        r.lastError = "";
        r.apiCalls = new ArrayList<>(rep.apiCalls);
        r.preview = new ArrayList<>(rep.planned);
        String result;
        if (r.state.live() || r.state == ScriptRecord.State.SUSPENDED) {
            var act = eng.scripts().activate(r, true);
            result = act.ok() ? "v" + r.version + " 적용 및 재시작" : "v" + r.version + " 적용했지만 시작 실패: " + act.error();
        } else {
            result = "v" + r.version + " 적용 (현재 " + r.state + " — 실행하려면 재개/승인)";
        }
        NewYearCountdown.LOGGER.info("[WishAI] admin {} edited script {} -> v{}: {}", by, r.id, r.version, reason);
        o.addProperty("applied", true);
        o.addProperty("version", r.version);
        o.addProperty("text", result);
        return o;
    }

    // ---------------------------------------------------------------- 도우미

    private static JsonObject msg(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "message");
        o.addProperty("text", text);
        return o;
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
