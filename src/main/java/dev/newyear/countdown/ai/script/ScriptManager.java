package dev.newyear.countdown.ai.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.api.PlayerRef;
import dev.newyear.countdown.ai.script.lang.Budget;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Parser;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Stdlib;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.Callable;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.ui.UiService;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 실행 중인 스크립트 전체를 관리한다 (서버 스레드 전용).
 * 이벤트는 구독한 스크립트에만 전달되고, 타이머·영역·행동은 정해진 간격으로만 검사한다 (매 틱 전수 검사 없음).
 */
public final class ScriptManager {
    private final WishEngine eng;
    private final LinkedHashMap<String, ScriptInstance> live = new LinkedHashMap<>();
    /** 이벤트별 구독 스크립트 수 (빠른 건너뛰기용). */
    private final Map<String, Integer> listenerIndex = new HashMap<>();
    private boolean indexDirty = true;
    private long stepsThisTick;
    private long droppedEvents;
    private final Deque<JsObject> customQueue = new ArrayDeque<>();
    private int customDepth;
    private final List<String> stopRequests = new ArrayList<>();
    private final Map<String, String> stopReasons = new HashMap<>();
    private final Map<UUID, double[]> lastPos = new HashMap<>();
    private long lastDayTime = -1;

    private record Pending(long due, String scriptId, ScriptRecord.BlockChange block, UUID entity) {}

    private final List<Pending> pending = new ArrayList<>();

    public ScriptManager(WishEngine eng) {
        this.eng = eng;
    }

    public Map<String, ScriptInstance> live() {
        return live;
    }

    public ScriptInstance instance(String id) {
        return live.get(id);
    }

    public long droppedEvents() {
        return droppedEvents;
    }

    private Budget newBudget() {
        AiConfig c = AiConfig.get();
        return new Budget(c.scriptStepsPerRun, c.scriptAllocPerRun, 48, c.scriptMillisPerRun);
    }

    // ---------------------------------------------------------------- 활성화

    public record Activation(boolean ok, String error, List<String> log) {}

    /** 검증을 통과한 기록을 실제로 실행한다. restored=true 면 서버 재시작 후 복구. */
    public Activation activate(ScriptRecord rec, boolean restored) {
        ScriptInstance previous = live.get(rec.id);
        boolean replacingCode = previous != null && !previous.source.equals(rec.code);
        if (live.containsKey(rec.id)) stop(rec.id, ScriptRecord.State.STOPPED, "replaced by new version", false);
        ScriptInstance inst = new ScriptInstance(rec, false);
        inst.restored = restored;
        try {
            inst.program = Parser.parse(inst.source);
        } catch (ScriptError e) {
            rec.state = ScriptRecord.State.FAULTED;
            rec.lastError = e.describe();
            return new Activation(false, e.describe(), List.of());
        }
        if (restored && rec.stateJson != null) {
            inst.in.budget.reset();
            Object st = Stdlib.fromJson(rec.stateJson, null, this::resolveRef, 0);
            if (st instanceof JsObject jo) inst.state = jo;
        }
        Wish wish = rec.wishId == null ? null : eng.state().wishes.get(rec.wishId);
        ScriptApi.install(inst, wish);
        rec.subscriptions.clear();
        if (!restored) {
            rec.startedTick = eng.tick();
            rec.expiresTick = rec.durationTicks > 0 ? rec.startedTick + rec.durationTicks : 0;
        }
        rec.state = ScriptRecord.State.ACTIVE;
        if (replacingCode || restored && rec.continuations != null && rec.continuations.has("code")
                && !Continuations.codeHash(inst.source).equals(rec.continuations.get("code").getAsString())) {
            // Bindings from replaced code must not reserve slots for actions it no longer implements.
            RuntimeState.section(rec, "controls").asMap().clear();
            RuntimeState.section(rec, "panels").asMap().clear();
        }
        live.put(rec.id, inst);
        indexDirty = true;
        Budget b = newBudget();
        b.maxSteps *= 2;    // 최상위 초기화 코드는 조금 더 여유를 준다
        inst.in.budget = b;
        b.reset();
        try {
            inst.in.run(inst.program);
            if (restored && AiConfig.get().scriptPersistClosures && rec.continuations != null) {
                var res = Continuations.restore(inst, rec.continuations, eng.tick(), codecHost);
                inst.lostContinuations = res.lost().size();
                if (res.kept() + res.revived() + res.dropped() + res.brains() > 0 || !res.lost().isEmpty()) {
                    NewYearCountdown.LOGGER.info("[WishAI] script {} resumed: {} re-timed, {} revived from saved closures, {} already finished, {} brains{}",
                            rec.id, res.kept(), res.revived(), res.dropped(), res.brains(), res.lost().isEmpty() ? "" : ", lost: " + res.lost());
                }
                indexDirty = true;
            }
            RuntimeControls.validateReceiver(inst);
            checkHeap(inst);
            eng.quests().validateTrackers(inst);
            rec.subscriptions = new ArrayList<>(inst.listeners.entrySet().stream()
                    .filter(e -> !e.getValue().isEmpty()).map(Map.Entry::getKey).sorted().toList());
        } catch (ScriptError e) {
            eng.quests().rollbackPreparation(inst);
            live.remove(rec.id);
            indexDirty = true;
            rec.lastError = e.describe();
            rec.state = ScriptRecord.State.FAULTED;
            RuntimeControls.cleanup(rec);
            RuntimeState.suspend(rec);
            eng.extensions().cleanup(rec.id);
            // 복구 실패는 세계에 남은 결과물(엔티티·블록)을 지우지 않는다 — 다시 시작할 수 있게
            if (!restored) cleanup(rec, inst, true);
            else eng.ui().cancelInteractionsOfScript(rec.id);
            rec.state = ScriptRecord.State.FAULTED;
            rec.stateReason = "startup error";
            return new Activation(false, e.describe(), new ArrayList<>(inst.in.log));
        } catch (RuntimeException e) {
            eng.quests().rollbackPreparation(inst);
            live.remove(rec.id);
            indexDirty = true;
            rec.state = ScriptRecord.State.FAULTED;
            RuntimeControls.cleanup(rec);
            RuntimeState.suspend(rec);
            eng.extensions().cleanup(rec.id);
            NewYearCountdown.LOGGER.warn("[WishAI] script {} crashed during startup", rec.id, e);
            if (!restored) cleanup(rec, inst, true);
            rec.state = ScriptRecord.State.FAULTED;
            rec.lastError = "internal error: " + e.getClass().getSimpleName();
            return new Activation(false, rec.lastError, List.of());
        } finally {
            rec.steps += b.steps();
            rec.runs++;
        }
        if (restored) applyGoals(inst);
        RuntimeState.restore(rec);
        for (ServerPlayer p : eng.online()) RuntimeControls.sync(p);
        inst.initialized = true;
        eng.quests().publishPrepared(inst);
        return new Activation(true, null, new ArrayList<>(inst.in.log));
    }

    // ---------------------------------------------------------------- 엔티티 AI 목표

    /** 목표를 등록하고 (같은 엔티티의 같은 종류는 바꿔 끼운다) 지금 로드된 몹에 붙인다. 반환: 목표 id. */
    public int addGoal(ScriptInstance inst, EntityRef r, ScriptRecord.GoalSpec spec) {
        ScriptRecord rec = inst.rec;
        rec.goals.removeIf(g -> g.entity.equals(spec.entity) && g.kind.equals(spec.kind));
        if (rec.goals.size() >= AiConfig.get().scriptMaxGoals) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Too many AI goals in this script (max " + AiConfig.get().scriptMaxGoals + ")", 0, 0);
        }
        spec.id = ++inst.seq;
        rec.goals.add(spec);
        Entity e = r.live();
        if (e instanceof Mob m) {
            // 같은 종류의 이전 목표를 뗀다
            for (var g : new ArrayList<>(m.getGoalSelector().getAvailableGoals())) {
                if (g.getGoal() instanceof Goals.WishGoal w && w.scriptId().equals(rec.id) && rec.goals.stream().noneMatch(x -> x.id == w.goalId())) {
                    m.getGoalSelector().removeGoal(g.getGoal());
                }
            }
            String err = Goals.apply(m, rec.id, spec);
            if (err != null) {
                rec.goals.remove(spec);
                throw new ScriptError(ScriptError.Kind.RUNTIME, err, 0, 0);
            }
            if (!EntityRef.owned(inst, r)) m.addTag(GOAL_TAG + rec.id);
        }
        return spec.id;
    }

    public static final String GOAL_TAG = "wishai_goal_";

    public void clearGoals(ScriptInstance inst, EntityRef r, int goalId) {
        String u = r.id.toString();
        inst.rec.goals.removeIf(g -> g.entity.equals(u) && (goalId < 0 || g.id == goalId));
        if (goalId < 0 || inst.rec.goals.stream().noneMatch(g -> g.entity.equals(u) && g.kind.equals("script"))) inst.brains.remove(u);
        if (r.live() instanceof Mob m) {
            Goals.remove(m, inst.rec.id, goalId);
            if (inst.rec.goals.stream().noneMatch(g -> g.entity.equals(u))) m.removeTag(GOAL_TAG + inst.rec.id);
        }
    }

    /** 재시작 후: 저장된 목표 명세를 지금 로드된 몹에 다시 붙인다 (두뇌 콜백을 잃은 script 목표는 버린다). */
    private void applyGoals(ScriptInstance inst) {
        inst.rec.goals.removeIf(g -> g.kind.equals("script") && !inst.brains.containsKey(g.entity));
        for (ScriptRecord.GoalSpec g : inst.rec.goals) {
            try {
                if (new EntityRef(UUID.fromString(g.entity), "?").live() instanceof Mob m) Goals.apply(m, inst.rec.id, g);
            } catch (IllegalArgumentException ignored) { }
        }
        for (ScriptRecord.GoalSpec g : inst.rec.goals) inst.seq = Math.max(inst.seq, g.id);
    }

    /** 두뇌 콜백 호출 (몹의 목표 선택기 안에서, 서버 스레드). */
    public Object callBrain(String scriptId, Mob mob, String which) {
        ScriptInstance inst = live.get(scriptId);
        if (inst == null) return false;
        ScriptInstance.Brain b = inst.brains.get(mob.getUUID().toString());
        if (b == null) return false;
        Callable fn = switch (which) {
            case "canUse" -> b.canUse;
            case "start" -> b.start;
            case "stop" -> b.stop;
            default -> b.tick;
        };
        if (fn == null) return null;
        return invoke(inst, fn, Arrays.asList(EntityRef.of(mob)), "brain." + which);
    }

    private void removeGoals(ScriptRecord rec) {
        for (ScriptRecord.GoalSpec g : rec.goals) {
            try {
                if (new EntityRef(UUID.fromString(g.entity), "?").live() instanceof Mob m) {
                    Goals.removeAll(m, rec.id);
                    m.removeTag(GOAL_TAG + rec.id);
                }
            } catch (IllegalArgumentException ignored) { }
        }
        rec.goals.clear();
    }

    /** 클로저 저장/복구에서 게임 핸들 ↔ 참조 문자열. */
    private final dev.newyear.countdown.ai.script.lang.ClosureCodec.Host codecHost = new dev.newyear.countdown.ai.script.lang.ClosureCodec.Host() {
        @Override
        public String refOf(Values.HostObject h) {
            Object ref = h.getMember(null, "$ref");
            return ref instanceof String s ? s : null;
        }

        @Override
        public Object resolve(String ref) {
            return resolveRef(ref);
        }
    };

    /**
     * 스크립트가 붙잡고 있는 값의 크기를 재고, 한도를 넘으면 오류로 센다 (여러 번 넘으면 멈춘다).
     * 실행할 때마다 재지 않고 16번에 한 번, 또는 그 실행이 많이 할당했을 때만.
     */
    private void checkHeap(ScriptInstance inst) {
        inst.runsSinceHeap = 0;
        long cap = AiConfig.get().scriptHeapMaxBytes;
        var r = dev.newyear.countdown.ai.script.lang.HeapMeter.measure(inst.heapRoots(), cap, inst.in.globals);
        inst.rec.heapBytes = r.bytes();
        if (r.overCap()) {
            // 한 번에 멈추지는 않지만 예산 오류처럼 무겁게 센다
            onError(inst, new ScriptError(ScriptError.Kind.BUDGET, "Memory limit exceeded: this script holds more than "
                    + dev.newyear.countdown.ai.script.lang.HeapMeter.human(cap) + " of data (state, variables, closures). Keep fewer/smaller values", 0, 0), "heap check");
        }
    }

    /** 저장된 상태의 참조 문자열을 핸들로 되돌린다. */
    private Object resolveRef(String ref) {
        try {
            if (ref.startsWith("player:")) {
                UUID id = UUID.fromString(ref.substring(7));
                var prof = eng.state().players.get(id.toString());
                return new PlayerRef(id, prof == null ? "?" : prof.name);
            }
            if (ref.startsWith("entity:")) {
                String[] parts = ref.split(":", 3);
                return new EntityRef(UUID.fromString(parts[1]), parts.length > 2 ? parts[2] : "?");
            }
            if (ref.startsWith("quest:")) return new QuestRef(ref.substring(6), false, null);
            if (ref.startsWith("model:")) {
                String[] parts = ref.split(":", 3);
                if (parts.length == 3) return new dev.newyear.countdown.ai.script.api.ModelRef(parts[1], parts[2], null);
            }
        } catch (IllegalArgumentException ignored) {
            // 손상된 참조는 일반 객체로 남긴다
        }
        return null;
    }

    // ---------------------------------------------------------------- 정지·정리

    public void requestStop(String id, String reason) {
        if (!stopRequests.contains(id)) stopRequests.add(id);
        stopReasons.put(id, reason);
    }

    public boolean stop(String id, ScriptRecord.State end, String reason, boolean rollbackAllBlocks) {
        ScriptInstance inst = live.remove(id);
        ScriptRecord rec = inst != null ? inst.rec : eng.state().scripts.get(id);
        if (rec == null) return false;
        indexDirty = true;
        if (inst != null) saveState(inst);
        rec.state = end;
        rec.stateReason = reason == null ? "" : reason;
        var buildPlan=RuntimeConstruction.plan(rec);
        if(buildPlan!=null && buildPlan.pending() && rec.id.equals(buildPlan.runnerScriptId)) {buildPlan.status="PAUSED";buildPlan.reason=rec.stateReason;}
        cleanup(rec, inst, rollbackAllBlocks, end == ScriptRecord.State.STOPPED && "replaced by new version".equals(reason));
        NewYearCountdown.LOGGER.info("[WishAI] script {} ({}) -> {}: {}", rec.id, rec.name, end, reason);
        if (rec.wishId != null) eng.wishes().checkWorkDone(rec.wishId);
        return true;
    }

    /** 스크립트가 만든 엔티티·임시 블록·HUD·퀘스트·대기 중 UI 를 정리한다. 남기기로 한 것은 남긴다. */
    private void cleanup(ScriptRecord rec, ScriptInstance inst, boolean rollbackAllBlocks) {
        cleanup(rec, inst, rollbackAllBlocks, false);
    }

    private void cleanup(ScriptRecord rec, ScriptInstance inst, boolean rollbackAllBlocks, boolean preserveQuests) {
        eng.extensions().cleanup(rec.id);
        RuntimeControls.cleanup(rec);
        RuntimeState.cleanup(rec);
        removeGoals(rec);
        rec.edits.clear();
        // 남기지 않는 부품이 사라지면 모델 기록도 없앤다 (남긴 모델은 그대로 — 엔티티로 세계에 남는다)
        rec.models.removeIf(m -> m.parts.stream().noneMatch(p -> p.entity != null && rec.keptEntities.contains(p.entity)));
        for (ScriptRecord.ModelSpec m : rec.models) m.follow = null;
        for (String u : new ArrayList<>(rec.entities)) {
            if (rec.keptEntities.contains(u)) continue;
            try {
                Entity e = new EntityRef(UUID.fromString(u), "?").live();
                if (e != null) e.discard();
            } catch (IllegalArgumentException ignored) { }
        }
        rec.behaviours.clear();
        revertBlocks(rec, !rollbackAllBlocks);
        eng.ui().clearStatusesOfScript(rec.id);
        eng.ui().cancelInteractionsOfScript(rec.id);
        for (Quest q : eng.state().quests.values()) {
            if (!preserveQuests && !q.preparing && rec.id.equals(q.scriptId) && q.state == Quest.State.ACTIVE) {
                eng.quests().finish(q.id, Quest.State.CANCELLED, "능력이 사라져 퀘스트가 멈췄다");
            }
        }
        pending.removeIf(p -> p.scriptId.equals(rec.id));
    }

    /** 블록 되돌리기 결과. conflicts: 그 사이 다른 누가 바꿔서 건드리지 않은 칸 (rec.blockConflicts 에 남는다). */
    public record RollbackReport(int reverted, int conflicts, int protectedBlocks, List<String> samples) {
        public String describe() {
            String s = "되돌림 " + reverted + "칸";
            if (conflicts > 0) s += ", 그 사이 바뀐 칸 " + conflicts + "개는 그대로 둠" + (samples.isEmpty() ? "" : " (" + String.join(", ", samples) + ")");
            if (protectedBlocks > 0) s += ", 내용물이 있는 블록 " + protectedBlocks + "개는 보호됨";
            return s;
        }
    }

    private enum Revert { DONE, CONFLICT, PROTECTED, GONE }

    /** onlyTemporary=true 면 임시 블록만, 아니면 이 스크립트가 바꾼 모든 블록을 원래대로. */
    public RollbackReport revertBlocks(ScriptRecord rec, boolean onlyTemporary) {
        return revertBlocks(rec, onlyTemporary, false);
    }

    /**
     * force=true (관리자 전용): 그 사이 바뀐 칸도 되돌린다. 단, 상자·표지판처럼 내용물이 있는 블록은 절대 덮어쓰지 않는다.
     * force 면 이전에 충돌로 남겨 둔 칸(rec.blockConflicts)도 함께 처리한다.
     */
    public RollbackReport revertBlocks(ScriptRecord rec, boolean onlyTemporary, boolean force) {
        Iterator<ScriptRecord.BlockChange> it = rec.blocks.iterator();
        List<ScriptRecord.BlockChange> rev = new ArrayList<>();
        while (it.hasNext()) {
            ScriptRecord.BlockChange b = it.next();
            if (onlyTemporary && !b.temporary) continue;
            rev.add(b);
            it.remove();
        }
        if (force) {
            rev.addAll(0, rec.blockConflicts);
            rec.blockConflicts.clear();
        }
        java.util.Collections.reverse(rev);
        int done = 0, conflicts = 0, prot = 0;
        List<String> samples = new ArrayList<>();
        for (ScriptRecord.BlockChange b : rev) {
            Revert r = revertOne(b, force);
            if (r == Revert.DONE) done++;
            if (r != Revert.CONFLICT && r != Revert.PROTECTED) continue;
            if (rec.blockConflicts.size() < 4096) rec.blockConflicts.add(b);
            if (r == Revert.PROTECTED) prot++;
            else conflicts++;
            if (samples.size() < 4) samples.add(b.x + "," + b.y + "," + b.z);
        }
        if (conflicts + prot > 0) NewYearCountdown.LOGGER.info("[WishAI] script {} rollback: {} reverted, {} changed by others, {} protected", rec.id, done, conflicts, prot);
        return new RollbackReport(done, conflicts, prot, samples);
    }

    private Revert revertOne(ScriptRecord.BlockChange b, boolean force) {
        ServerLevel l = McUtil.level(eng.server(), b.dimension);
        if (l == null) return Revert.GONE;
        BlockPos p = new BlockPos(b.x, b.y, b.z);
        BlockState cur = l.getBlockState(p);
        BlockState before = McUtil.parseBlockString(b.before);
        if (before == null) return Revert.GONE;
        if (McUtil.blockString(cur).equals(b.before)) return Revert.DONE;     // 이미 원래대로
        // 그 사이 플레이어가 바꿨다면 건드리지 않는다 (force 여도 내용물이 있는 블록은 보호)
        if (b.after != null && !McUtil.blockString(cur).equals(b.after)) {
            if (!force) return Revert.CONFLICT;
            if (cur.hasBlockEntity()) return Revert.PROTECTED;
        }
        l.setBlock(p, before, 3);
        return Revert.DONE;
    }

    public void scheduleRevert(String scriptId, ScriptRecord.BlockChange b, long ticks) {
        pending.add(new Pending(eng.tick() + ticks, scriptId, b, null));
    }

    public void scheduleEntityRemoval(String scriptId, UUID entity, long ticks) {
        pending.add(new Pending(eng.tick() + ticks, scriptId, null, entity));
    }

    /** 킬 스위치: 모든 스크립트 일시 정지 (세계 결과물은 그대로). */
    public int suspendAll(String reason) {
        int n = 0;
        for (String id : new ArrayList<>(live.keySet())) {
            ScriptInstance inst = live.remove(id);
            saveState(inst);
            inst.rec.state = ScriptRecord.State.SUSPENDED;
            RuntimeControls.cleanup(inst.rec);
            RuntimeState.suspend(inst.rec);
            eng.extensions().cleanup(inst.rec.id);
            inst.rec.stateReason = reason;
            n++;
        }
        indexDirty = true;
        return n;
    }

    public int resumeAll() {
        int n = 0;
        for (ScriptRecord r : eng.state().scripts.values()) {
            if (r.state == ScriptRecord.State.SUSPENDED && activate(r, true).ok()) n++;
        }
        return n;
    }

    public void saveState(ScriptInstance inst) {
        try {
            JsonElement el = Stdlib.toJson(inst.state);
            String s = el.toString();
            if (s.length() > AiConfig.get().scriptStateMaxBytes) {
                inst.rec.lastError = "state too large (" + s.length() + " bytes); not saved";
            } else {
                inst.rec.stateJson = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            }
        } catch (ScriptError e) {
            inst.rec.lastError = "state not saved: " + e.getMessage();
        }
        if (AiConfig.get().scriptPersistClosures && !inst.dry) {
            try {
                inst.rec.continuations = Continuations.save(inst, eng.tick(), codecHost).json();
            } catch (RuntimeException e) {
                NewYearCountdown.LOGGER.warn("[WishAI] could not save continuations of {}", inst.rec.id, e);
            }
        }
    }

    public void saveAllStates() {
        for (ScriptInstance inst : live.values()) saveState(inst);
    }

    public int totalEntities() {
        int n = 0;
        for (ScriptInstance i : live.values()) n += i.rec.entities.size();
        return n;
    }

    // ---------------------------------------------------------------- 호출

    private Object invoke(ScriptInstance inst, Callable fn, List<Object> args, String what) {
        if (!inst.rec.state.live()) return null;
        long cap = AiConfig.get().scriptStepsPerTickTotal;
        if (stepsThisTick > cap) {
            droppedEvents++;
            return null;
        }
        Budget b = newBudget();
        inst.in.budget = b;
        b.reset();
        try {
            Object result = inst.in.invoke(fn, args);
            eng.quests().publishPrepared(inst);
            return result;
        } catch (ScriptError e) {
            eng.quests().rollbackPreparation(inst);
            onError(inst, e, what);
        } catch (RuntimeException e) {
            eng.quests().rollbackPreparation(inst);
            NewYearCountdown.LOGGER.warn("[WishAI] script {} internal error in {}", inst.rec.id, what, e);
            onError(inst, new ScriptError(ScriptError.Kind.RUNTIME, "internal error: " + e.getClass().getSimpleName(), 0, 0), what);
        } finally {
            stepsThisTick += b.steps();
            inst.rec.steps += b.steps();
            inst.rec.runs++;
            if (inst.rec.state.live() && (++inst.runsSinceHeap >= 16 || b.alloc() > 2_000)) checkHeap(inst);
        }
        return null;
    }

    private void onError(ScriptInstance inst, ScriptError e, String what) {
        ScriptRecord rec = inst.rec;
        rec.errors += e.kind == ScriptError.Kind.BUDGET ? 3 : 1;
        rec.lastError = what + ": " + e.describe();
        inst.recentErrors.add(rec.lastError);
        while (inst.recentErrors.size() > 10) inst.recentErrors.remove(0);
        NewYearCountdown.LOGGER.info("[WishAI] script {} error in {}: {}", rec.id, what, e.describe());
        if (rec.errors >= AiConfig.get().scriptErrorLimit) {
            requestStop(rec.id, "too many errors: " + e.describe());
            stopReasons.put(rec.id + "#fault", "1");
            if (rec.owner != null) {
                ServerPlayer p = eng.player(UUID.fromString(rec.owner));
                if (p != null) eng.ui().notify(p, "능력이 흩어졌다", "'" + (rec.abilityLabel.isEmpty() ? rec.name : rec.abilityLabel) + "'의 마법이 불안정해져 사라졌습니다.", "warning", 6);
            }
        }
    }

    // ---------------------------------------------------------------- 이벤트

    private void rebuildIndex() {
        listenerIndex.clear();
        for (ScriptInstance i : live.values()) {
            for (Map.Entry<String, List<ScriptInstance.Listener>> e : i.listeners.entrySet()) {
                if (!e.getValue().isEmpty()) listenerIndex.merge(e.getKey(), 1, Integer::sum);
            }
            if (!i.regions.isEmpty()) listenerIndex.merge("$regions", 1, Integer::sum);
        }
        indexDirty = false;
    }

    public boolean hasListeners(String type) {
        if (indexDirty) rebuildIndex();
        return listenerIndex.containsKey(type);
    }

    /** 이벤트 필터 맥락. */
    public record Ctx(UUID player, UUID entity, String item, String region, String onlyScript) {
        public static final Ctx NONE = new Ctx(null, null, null, null, null);

        public static Ctx player(UUID p) {
            return new Ctx(p, null, null, null, null);
        }
    }

    public void emit(String type, JsObject payload, Ctx ctx) {
        if (!hasListeners(type)) return;
        for (ScriptInstance inst : new ArrayList<>(live.values())) {
            if (ctx.onlyScript() != null && !ctx.onlyScript().equals(inst.rec.id)) continue;
            List<ScriptInstance.Listener> ls = inst.listeners.get(type);
            if (ls == null || ls.isEmpty()) continue;
            for (ScriptInstance.Listener l : new ArrayList<>(ls)) {
                if (!matches(l.filter(), ctx)) continue;
                if (l.once()) {
                    ls.remove(l);
                    indexDirty = true;
                }
                invoke(inst, l.fn(), Arrays.asList(payload), "on('" + type + "')");
                if (!inst.rec.state.live()) break;
            }
        }
    }

    private final java.util.Set<String> intercepting = new java.util.HashSet<>();

    /** Synchronous before-events. Only a successful handler return can change the operation. */
    public JsObject intercept(String type, JsObject payload, Ctx ctx, Object affected) {
        JsObject result = new JsObject().put("cancel", false);
        if (payload.get("amount") instanceof Number n) result.put("amount", n.doubleValue());
        if (!eng.aiEnabled() || !hasListeners(type) || !intercepting.add(type)) return result;
        try {
            for (ScriptInstance inst : live.values().stream().sorted(java.util.Comparator.comparing(x -> x.rec.id)).toList()) {
                List<ScriptInstance.Listener> ls = inst.listeners.get(type);
                if (ls == null) continue;
                try {
                    if (affected instanceof PlayerRef p) Policy.checkAffect(inst.in, inst, p, "intercept " + type + " for", true);
                    else if (affected instanceof EntityRef e) {
                        if (e.live() == null) continue;
                        Policy.checkControl(inst.in, inst, e);
                        Policy.checkRange(inst.in, inst, new double[]{e.live().getX(),e.live().getY(),e.live().getZ()});
                    }
                } catch (RuntimeException denied) { continue; }
                for (ScriptInstance.Listener listener : new ArrayList<>(ls)) {
                    if (!matches(listener.filter(),ctx)) continue;
                    JsObject event = new JsObject();
                    event.map.putAll(payload.map);
                    if (result.get("amount") instanceof Number n) event.put("amount", n.doubleValue());
                    if (listener.once()) { ls.remove(listener); indexDirty = true; }
                    Object answer = invoke(inst,listener.fn(),List.of(event),"on('" + type + "')");
                    InterceptDecision.merge(result,answer);
                    if (Boolean.TRUE.equals(result.get("cancel"))) return result;
                }
            }
        } finally { intercepting.remove(type); }
        return result;
    }

    private static boolean matches(JsObject f, Ctx ctx) {
        if (f == null) return true;
        Object p = f.get("player");
        if (p instanceof PlayerRef pr && (ctx.player() == null || !pr.id.equals(ctx.player()))) return false;
        if (p instanceof String s && !s.isEmpty() && ctx.player() != null) {
            WishEngine e = WishEngine.get();
            ServerPlayer sp = e == null ? null : e.player(ctx.player());
            if (sp == null || !sp.getGameProfile().name().equalsIgnoreCase(s)) return false;
        }
        Object en = f.get("entity");
        if (en instanceof EntityRef er && (ctx.entity() == null || !er.id.equals(ctx.entity()))) return false;
        Object item = f.get("item");
        if (item instanceof String s && (ctx.item() == null || !ctx.item().equals(s.contains(":") ? s : "minecraft:" + s))) return false;
        Object region = f.get("region");
        if (region instanceof String s && !s.equals(ctx.region())) return false;
        return true;
    }

    public void queueCustom(JsObject ev) {
        if (customDepth >= 8) {
            droppedEvents++;
            return;
        }
        ev.put("$depth", (double) (customDepth + 1));
        if (customQueue.size() < 256) customQueue.add(ev);
        else droppedEvents++;
    }

    // ---------------------------------------------------------------- UI 콜백

    public UiService.Responder callbackResponder(String scriptId, Callable fn, boolean choiceMode) {
        return UiService.forScript(scriptId, (player, index, value, timedOut) -> {
            ScriptInstance inst = live.get(scriptId);
            if (inst == null) return;
            PlayerRef pr = player == null ? null : PlayerRef.of(player);
            List<Object> args = choiceMode
                    ? Arrays.asList(timedOut ? -1.0 : (double) index, timedOut ? null : value, pr)
                    : Arrays.asList(timedOut ? null : value, pr);
            invoke(inst, fn, args, "ui callback");
            stopIfIdleOneShot(inst);
        });
    }

    /** run_script 로 만든 일회성 스크립트가 더 기다릴 것(리스너·타이머·행동·UI)이 없으면 끝낸다. */
    public void stopIfIdleOneShot(ScriptInstance inst) {
        ScriptRecord r = inst.rec;
        if (!r.keepEntitiesDefault || r.lasting || !r.state.live()) return;
        if (!r.edits.isEmpty()) return;
        if (inst.listenerCount() > 0 || inst.activeTimers() > 0 || !r.behaviours.isEmpty() || !r.timers.isEmpty()) return;
        if (eng.ui().hasPendingForScript(r.id)) return;
        if (RuntimeState.section(r,"projectiles").size() > 0 || RuntimeState.section(r,"controls").size() > 0 || RuntimeState.section(r,"panels").size() > 0) return;
        requestStop(r.id, "one-shot finished");
    }

    public UiService.Responder consentResponder(String scriptId, Callable fn) {
        return UiService.forScript(scriptId, (player, index, value, timedOut) -> {
            ScriptInstance inst = live.get(scriptId);
            if (inst == null) return;
            invoke(inst, fn, Arrays.asList(!timedOut && index == 0, player == null ? null : PlayerRef.of(player)), "consent callback");
        });
    }

    // ---------------------------------------------------------------- 틱

    public void tick() {
        stepsThisTick = 0;
        long now = eng.tick();

        // 정지 요청
        if (!stopRequests.isEmpty()) {
            for (String id : new ArrayList<>(stopRequests)) {
                boolean fault = stopReasons.remove(id + "#fault") != null;
                stop(id, fault ? ScriptRecord.State.FAULTED : ScriptRecord.State.STOPPED, stopReasons.remove(id), false);
            }
            stopRequests.clear();
        }

        // 예약된 블록 복구·엔티티 제거
        if (!pending.isEmpty()) {
            Iterator<Pending> it = pending.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (p.due > now) continue;
                it.remove();
                ScriptRecord rec = eng.state().scripts.get(p.scriptId);
                if (p.block != null && rec != null && rec.blocks.remove(p.block) && revertOne(p.block, false) == Revert.CONFLICT) rec.blockConflicts.add(p.block);
                if (p.entity != null) {
                    Entity e = new EntityRef(p.entity, "?").live();
                    if (e != null) e.discard();
                    if (rec != null) rec.entities.remove(p.entity.toString());
                }
            }
        }

        // 타이머
        for (ScriptInstance inst : new ArrayList<>(live.values())) {
            if (inst.timers.isEmpty() && inst.rec.timers.isEmpty()) continue;
            for (ScriptInstance.Timer t : new ArrayList<>(inst.timers)) {
                if (t.cancelled || t.due > now) continue;
                if (stepsThisTick > AiConfig.get().scriptStepsPerTickTotal) { t.due = now + 1; continue; }
                if (t.interval > 0) t.due = now + t.interval;
                else t.cancelled = true;
                t.runs++;
                invoke(inst, t.fn, List.of(), t.interval > 0 ? "every()" : "after()");
                if (!inst.rec.state.live()) break;
            }
            inst.timers.removeIf(t -> t.cancelled);
            if (inst.timers.isEmpty()) stopIfIdleOneShot(inst);
            if (!inst.rec.timers.isEmpty()) {
                for (ScriptRecord.NamedTimer nt : new ArrayList<>(inst.rec.timers)) {
                    if (nt.fireTick > now) continue;
                    inst.rec.timers.remove(nt);
                    JsObject ev = new JsObject().put("name", nt.name).put("data", nt.data == null ? null : Stdlib.fromJson(nt.data, null, this::resolveRef, 0));
                    emit("timer", ev, new Ctx(null, null, null, null, inst.rec.id));
                }
            }
        }

        // 사용자 정의 이벤트
        int n = 0;
        while (!customQueue.isEmpty() && n++ < 64) {
            JsObject ev = customQueue.poll();
            customDepth = (int) Interpreter.toNumber(ev.get("$depth"));
            ev.map.remove("$depth");
            emit("custom", ev, Ctx.NONE);
            customDepth = 0;
        }

        // 만료
        for (ScriptInstance inst : new ArrayList<>(live.values())) {
            ScriptRecord r = inst.rec;
            if (r.durationTicks > 0 && r.expiresTick > 0 && now >= r.expiresTick) {
                stop(r.id, ScriptRecord.State.EXPIRED, "duration ended", false);
                if (r.owner != null) {
                    ServerPlayer p = eng.player(UUID.fromString(r.owner));
                    if (p != null) eng.ui().notify(p, "효과가 끝났다", "'" + (r.abilityLabel.isEmpty() ? r.name : r.abilityLabel) + "'의 시간이 다했습니다.", "info", 5);
                }
            }
        }

        // 큰 세계 변경: 스크립트마다 틱당 한도, 모두 합쳐 서버 전체 한도 안에서
        if(now%20==0) for(ScriptInstance inst:new ArrayList<>(live.values())) RuntimeConstruction.tick(inst);
        int edits = AiConfig.get().globalBlocksPerTick;
        for (ScriptInstance inst : new ArrayList<>(live.values())) {
            if (inst.rec.edits.isEmpty()) continue;
            if (edits <= 0) break;
            edits -= WorldEdits.tick(this, inst, Math.min(AiConfig.get().scriptBlocksPerTick, edits));
            stopIfIdleOneShot(inst);
        }
        if (now % 2 == 0) tickModels();
        if (now % 10 == 0) {
            tickBehaviours(now);
            tickRegionsAndMoves();
        }
        if (now % 20 == 0) tickTime();
    }

    private void tickBehaviours(long now) {
        for (ScriptInstance inst : live.values()) {
            if (inst.rec.behaviours.isEmpty()) continue;
            Iterator<ScriptRecord.Behaviour> it = inst.rec.behaviours.iterator();
            while (it.hasNext()) {
                ScriptRecord.Behaviour b = it.next();
                Entity e;
                try {
                    e = new EntityRef(UUID.fromString(b.entity), "?").live();
                } catch (IllegalArgumentException ex) {
                    it.remove();
                    continue;
                }
                if (e == null) continue;        // 언로드: 다음에 다시
                if (!e.isAlive()) { it.remove(); continue; }
                if (!(e instanceof Mob m)) continue;
                double[] target = null;
                Entity tEnt = null;
                if (b.target != null) {
                    try {
                        if (b.target.startsWith("player:")) tEnt = eng.player(UUID.fromString(b.target.substring(7)));
                        else if (b.target.startsWith("entity:")) tEnt = new EntityRef(UUID.fromString(b.target.substring(7)), "?").live();
                    } catch (IllegalArgumentException ignored) { }
                    if (tEnt != null && tEnt.level() == e.level()) target = new double[]{tEnt.getX(), tEnt.getY(), tEnt.getZ()};
                } else if (b.anchor != null) {
                    target = b.anchor;
                }
                if (target == null) continue;
                double dx = target[0] - e.getX(), dz = target[2] - e.getZ(), dy = target[1] - e.getY();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                switch (b.kind) {
                    case "follow" -> {
                        if (dist > 40 && EntityRef.owned(inst, EntityRef.of(e))) {
                            m.teleportTo(target[0] - dx / dist * 2, target[1], target[2] - dz / dist * 2);
                        } else if (dist > b.distance) {
                            if (tEnt != null) m.getNavigation().moveTo(tEnt, b.speed);
                        } else {
                            m.getNavigation().stop();
                            if (tEnt != null) m.getLookControl().setLookAt(tEnt);
                        }
                    }
                    case "orbit" -> {
                        double ang = (now / 10.0) * 0.35 * b.speed + (b.entity.hashCode() % 628) / 100.0;
                        m.getNavigation().moveTo(target[0] + Math.cos(ang) * b.radius, target[1], target[2] + Math.sin(ang) * b.radius, b.speed);
                    }
                    case "stay" -> {
                        if (dist > 1.5) m.getNavigation().moveTo(target[0], target[1], target[2], 1.0);
                    }
                    default -> { }
                }
            }
        }
    }

    /** 따라다니는 모델: 대상 위치(+오프셋)로 부품을 미끄러뜨리고, turn 이면 대상이 보는 쪽으로 돈다. */
    private void tickModels() {
        for (ScriptInstance inst : live.values()) {
            for (ScriptRecord.ModelSpec m : inst.rec.models) {
                if (m.follow == null) continue;
                Entity t = null;
                try {
                    UUID u = UUID.fromString(m.follow.substring(7));
                    t = m.follow.startsWith("player:") ? eng.player(u) : new EntityRef(u, "?").live();
                } catch (IllegalArgumentException | StringIndexOutOfBoundsException ignored) { }
                if (t == null || !McUtil.dimId(t.level()).equals(m.dimension)) continue;
                double[] off = m.followOffset == null ? new double[]{0, 0, 0} : m.followOffset;
                double[] to = {t.getX() + off[0], t.getY() + off[1], t.getZ() + off[2]};
                if (m.origin != null && Math.abs(to[0] - m.origin[0]) + Math.abs(to[1] - m.origin[1]) + Math.abs(to[2] - m.origin[2]) > 0.02) Models.moveTo(m, to, 3);
                if (m.followTurn && Math.abs(net.minecraft.util.Mth.wrapDegrees(t.getYRot() - m.yaw)) > 2) {
                    m.yaw = t.getYRot();
                    Models.refresh(m, 3);
                }
            }
        }
    }

    private void tickRegionsAndMoves() {
        boolean regions = hasListeners("$regions");
        boolean moves = hasListeners("move");
        if (!regions && !moves) return;
        for (ServerPlayer p : eng.server().getPlayerList().getPlayers()) {
            String dim = McUtil.dimId(p.level());
            if (moves) {
                double[] prev = lastPos.get(p.getUUID());
                double[] cur = {p.getX(), p.getY(), p.getZ()};
                if (prev != null && (Math.abs(prev[0] - cur[0]) + Math.abs(prev[1] - cur[1]) + Math.abs(prev[2] - cur[2]) > 0.5)) {
                    JsObject ev = new JsObject().put("player", PlayerRef.of(p))
                            .put("from", dev.newyear.countdown.ai.script.api.Args.posObj(prev[0], prev[1], prev[2]))
                            .put("to", dev.newyear.countdown.ai.script.api.Args.posObj(cur[0], cur[1], cur[2]));
                    emit("move", ev, Ctx.player(p.getUUID()));
                }
                lastPos.put(p.getUUID(), cur);
            }
            if (regions) {
                for (ScriptInstance inst : new ArrayList<>(live.values())) {
                    if (inst.regions.isEmpty()) continue;
                    for (ScriptInstance.Region r : inst.regions.values()) {
                        String key = r.name() + "|" + p.getUUID();
                        boolean in = r.contains(dim, p.getX(), p.getY(), p.getZ());
                        boolean was = inst.inside.contains(key);
                        if (in == was) continue;
                        if (in) inst.inside.add(key);
                        else inst.inside.remove(key);
                        JsObject ev = new JsObject().put("player", PlayerRef.of(p)).put("region", r.name());
                        emit(in ? "region_enter" : "region_leave", ev, new Ctx(p.getUUID(), null, null, r.name(), inst.rec.id));
                    }
                }
            }
        }
    }

    private void tickTime() {
        long t = eng.server().overworld().getOverworldClockTime();
        long day = Math.floorDiv(t, 24000L);
        long tod = Math.floorMod(t, 24000L);
        if (lastDayTime >= 0) {
            long prev = Math.floorMod(lastDayTime, 24000L);
            boolean wrapped = Math.floorDiv(t, 24000L) != Math.floorDiv(lastDayTime, 24000L);
            check("dawn", 0, prev, tod, wrapped, day);
            check("noon", 6000, prev, tod, wrapped, day);
            check("dusk", 12000, prev, tod, wrapped, day);
            check("night", 13000, prev, tod, wrapped, day);
        }
        lastDayTime = t;
    }

    private void check(String event, long mark, long prev, long cur, boolean wrapped, long day) {
        boolean crossed = wrapped ? (mark > prev || mark <= cur) : (prev < mark && cur >= mark);
        if (mark == 0) crossed = wrapped;
        if (crossed) emit(event, new JsObject().put("day", (double) day), Ctx.NONE);
    }

    /** 언로드 상태였던 엔티티가 나중에 로드되면, 이미 끝난 스크립트의 것인지 확인해 치운다. */
    public void onEntityLoad(Entity e) {
        // 목표를 받은 세계의 몹: 그 스크립트가 살아 있으면 목표를 다시 붙이고, 끝났으면 표시만 지운다 (몹은 건드리지 않는다)
        if (e instanceof Mob m) {
            for (String tag : new ArrayList<>(e.entityTags())) {
                if (!tag.startsWith(GOAL_TAG)) continue;
                ScriptInstance inst = live.get(tag.substring(GOAL_TAG.length()));
                if (inst == null) e.removeTag(tag);
            }
            String u = e.getUUID().toString();
            for (ScriptInstance inst : live.values()) {
                for (ScriptRecord.GoalSpec g : inst.rec.goals) if (g.entity.equals(u)) Goals.apply(m, inst.rec.id, g);
            }
        }
        if (!e.entityTags().contains("wishai")) return;
        for (String tag : e.entityTags()) {
            if (!tag.startsWith("wishai_") || tag.equals(ScriptApi.FX_TAG) || tag.startsWith(GOAL_TAG)) continue;
            String sid = tag.substring(7);
            ScriptRecord rec = eng.state().scripts.get(sid);
            String u = e.getUUID().toString();
            if (rec == null || (rec.state.ended() && !rec.keptEntities.contains(u))) {
                e.discard();
            }
        }
    }

    /** 오래 끝난 스크립트 기록 정리 (최근 200개만 보관). */
    public void pruneRecords() {
        List<ScriptRecord> ended = new ArrayList<>();
        for (ScriptRecord r : eng.state().scripts.values()) if (r.state.ended() && r.keptEntities.isEmpty()) ended.add(r);
        if (ended.size() <= 200) return;
        ended.sort((a, b) -> Long.compare(a.created, b.created));
        for (int i = 0; i < ended.size() - 200; i++) eng.state().scripts.remove(ended.get(i).id);
    }

    public void memoryOnFault(ScriptRecord rec) {
        MemoryRecord m = new MemoryRecord();
        m.kind = "script";
        m.text = "능력 '" + rec.name + "'이(가) 오류로 사라졌다.";
        m.scope = rec.owner == null ? MemoryRecord.Scope.WORLD : MemoryRecord.Scope.PLAYER;
        m.owner = rec.owner;
        m.scriptId = rec.id;
        m.importance = 3;
        eng.memory().add(m, System.currentTimeMillis());
    }

    /** UI 응답을 이 소원에 속한 스크립트들에 이벤트로도 알린다. */
    public void emitUiResponse(String wishId, ServerPlayer p, String interaction, String value) {
        if (wishId == null || !hasListeners("ui_response")) return;
        for (ScriptInstance inst : new ArrayList<>(live.values())) {
            if (!wishId.equals(inst.rec.wishId)) continue;
            emit("ui_response", new JsObject().put("player", PlayerRef.of(p)).put("interaction", interaction).put("value", value),
                    new Ctx(p.getUUID(), null, null, null, inst.rec.id));
        }
    }

    public Values.JsObject payload() {
        return new JsObject();
    }
}
