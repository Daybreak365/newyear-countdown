package dev.newyear.countdown.ai.wish;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.QuestRef;
import dev.newyear.countdown.ai.script.ScriptManager;
import dev.newyear.countdown.ai.script.api.PlayerRef;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 퀘스트 진행 관리. 진행 갱신은 게임 이벤트를 받은 스크립트가 로컬에서 하며, AI 는 부르지 않는다.
 * 끝났을 때만 기억을 남기고 (필요하면) 자율 판단 트리거로 알린다.
 */
public final class QuestService {
    private final WishEngine eng;

    public QuestService(WishEngine eng) {
        this.eng = eng;
    }

    public void create(Quest q) {
        eng.state().quests.put(q.id, q);
        if (q.wishId != null) {
            Wish w = eng.state().wishes.get(q.wishId);
            if (w != null && !w.questIds.contains(q.id)) w.questIds.add(q.id);
            if (w != null && !w.state.terminal() && w.state != Wish.State.ACTIVE) eng.wishes().transition(w, Wish.State.ACTIVE, "quest created");
        }
        eng.ui().pushQuest(q);
        for (ServerPlayer p : eng.server().getPlayerList().getPlayers()) {
            if (!q.involves(p.getUUID().toString())) continue;
            eng.ui().notify(p, "새 과제", q.title, "mystic", 5);
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.3f);
        }
    }

    /** 진행 갱신. 필수 목표가 모두 끝나면 자동으로 완료 처리하고 true 를 돌려준다. */
    public boolean progress(String id, String oid, double value, boolean add) {
        Quest q = eng.state().quests.get(id);
        if (q == null || q.state != Quest.State.ACTIVE) return q != null && q.state == Quest.State.COMPLETED;
        Quest.Objective o = q.objective(oid);
        if (o == null) return false;
        double before = o.progress;
        o.progress = Math.max(0, Math.min(o.target, add ? o.progress + value : value));
        boolean wasDone = o.done;
        o.done = o.progress >= o.target;
        if (o.progress == before && wasDone == o.done) return q.allRequiredDone();
        eng.ui().pushQuest(q);
        if (o.done && !wasDone) {
            for (ServerPlayer p : players(q)) {
                p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.4f);
            }
        }
        JsObject ev = new JsObject().put("quest", new QuestRef(q.id, false, null)).put("objective", oid)
                .put("progress", o.progress).put("target", o.target);
        eng.scripts().emit("quest_progress", ev, ScriptManager.Ctx.NONE);
        if (q.allRequiredDone()) {
            finish(id, Quest.State.COMPLETED, "");
            return true;
        }
        return false;
    }

    public void finish(String id, Quest.State end, String note) {
        Quest q = eng.state().quests.get(id);
        if (q == null || q.state != Quest.State.ACTIVE) return;
        q.state = end;
        q.resultNote = note == null ? "" : note;
        eng.ui().pushQuest(q);
        for (ServerPlayer p : players(q)) {
            switch (end) {
                case COMPLETED -> {
                    eng.ui().cinematic(p, "과제 완료", q.title, "celebrate", 4);
                    p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7f, 1.0f);
                }
                case FAILED -> eng.ui().notify(p, "과제 실패", q.title + (q.resultNote.isEmpty() ? "" : " — " + q.resultNote), "warning", 6);
                default -> eng.ui().notify(p, "과제 종료", q.title + (q.resultNote.isEmpty() ? "" : " — " + q.resultNote), "info", 5);
            }
        }
        // 기억: 성공/실패한 퀘스트
        if (end == Quest.State.COMPLETED || end == Quest.State.FAILED) {
            MemoryRecord m = new MemoryRecord();
            m.kind = "outcome";
            m.scope = MemoryRecord.Scope.PLAYER;
            m.owner = q.owner;
            m.ownerName = q.ownerName;
            m.wishId = q.wishId;
            m.text = (q.ownerName == null ? "플레이어" : q.ownerName) + "가 과제 '" + q.title + "'을(를) " + (end == Quest.State.COMPLETED ? "완수했다" : "실패했다")
                    + (q.resultNote.isEmpty() ? "" : " (" + q.resultNote + ")") + ".";
            m.importance = end == Quest.State.COMPLETED ? 6 : 5;
            m.tags.add("quest");
            eng.memory().add(m, System.currentTimeMillis());
            if (q.wishId != null) {
                Wish w = eng.state().wishes.get(q.wishId);
                if (w != null && !w.memoryIds.contains(m.id)) w.memoryIds.add(m.id);
            }
        }
        PlayerRef owner = q.owner == null ? null : new PlayerRef(UUID.fromString(q.owner), q.ownerName == null ? "?" : q.ownerName);
        JsObject ev = new JsObject().put("quest", new QuestRef(q.id, false, null)).put("player", owner).put("reason", q.resultNote);
        eng.scripts().emit(end == Quest.State.COMPLETED ? "quest_completed" : "quest_failed", ev, ScriptManager.Ctx.NONE);
        eng.autonomy().onQuestFinished(q, end);
        if (q.wishId != null) eng.wishes().checkWorkDone(q.wishId);
    }

    private ArrayList<ServerPlayer> players(Quest q) {
        ArrayList<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : eng.server().getPlayerList().getPlayers()) if (q.involves(p.getUUID().toString())) out.add(p);
        return out;
    }

    public void tick() {
        long now = eng.tick();
        if (now % 20 != 0) return;
        for (Quest q : new ArrayList<>(eng.state().quests.values())) {
            if (q.state != Quest.State.ACTIVE || q.deadlineTick <= 0) continue;
            if (now >= q.deadlineTick) {
                if (q.failOnTimeout) finish(q.id, Quest.State.FAILED, "시간이 다 되었다");
                else q.deadlineTick = 0;
            }
        }
    }
}
