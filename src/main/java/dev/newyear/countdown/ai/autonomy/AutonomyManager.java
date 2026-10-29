package dev.newyear.countdown.ai.autonomy;

import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.AiState;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.agent.TurnRequest;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.Wish;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 자율 세계 발전. 매 틱 AI 를 부르지 않고, 의미 있는 사건(오랜만의 접속, 죽음, 기억 속 장소 방문, 퀘스트 결과,
 * 특별한 아이템, 주기 판단)이 생겼을 때만 — 그것도 쿨다운·시간당 한도 안에서 — AI 에게 판단을 맡긴다.
 */
public final class AutonomyManager {
    private final WishEngine eng;
    private final Map<UUID, Long> deathCooldown = new HashMap<>();
    private long nextPeriodic = -1;
    private long nextMaintenance = 20 * 60 * 3;
    private final Map<String, Long> summarizing = new HashMap<>();
    /** "플레이어|기억" — 지금 그 장소 안에 있는 플레이어 (들어오는 순간에만 반응). */
    private final java.util.Set<String> insidePlace = new java.util.HashSet<>();

    public AutonomyManager(WishEngine eng) {
        this.eng = eng;
    }

    private boolean allowed(String playerUuid) {
        AiConfig cfg = AiConfig.get();
        if (!cfg.autonomyEnabled || !eng.aiAvailable()) return false;
        long now = System.currentTimeMillis();
        AiState st = eng.state();
        if (now - st.lastAutonomyGlobal < cfg.autonomyGlobalCooldownMinutes * 60_000L) return false;
        st.autonomyCalls.removeIf(t -> now - t > 3_600_000L);
        if (st.autonomyCalls.size() >= cfg.autonomyMaxCallsPerHour) return false;
        if (cfg.dailyBudgetUsd > 0 && eng.usage().costToday() >= cfg.dailyBudgetUsd * 0.8) return false;   // 플레이어 소원을 위해 남겨 둔다
        if (playerUuid != null) {
            AiState.PlayerProfile prof = st.players.get(playerUuid);
            if (prof != null && now - prof.lastAutonomy < cfg.autonomyPlayerCooldownMinutes * 60_000L) return false;
            // 대화 중이면 끼어들지 않는다
            if (prof != null && prof.openWishId != null && st.wishes.containsKey(prof.openWishId) && !st.wishes.get(prof.openWishId).state.terminal()) return false;
        }
        return true;
    }

    /** 관리자 명령: 쿨다운을 무시하고 자율 판단을 한 번 연다. */
    public boolean forceTrigger(ServerPlayer p, String description) {
        if (!eng.aiAvailable()) return false;
        startTurn(p, description);
        return true;
    }

    /** 자율 판단 턴을 연다. */
    private void trigger(ServerPlayer p, String description) {
        String uuid = p == null ? null : p.getUUID().toString();
        if (!allowed(uuid)) return;
        startTurn(p, description);
    }

    private void startTurn(ServerPlayer p, String description) {
        String uuid = p == null ? null : p.getUUID().toString();
        long now = System.currentTimeMillis();
        AiState st = eng.state();
        st.lastAutonomyGlobal = now;
        st.autonomyCalls.add(now);
        if (uuid != null) st.profile(uuid, p.getGameProfile().name()).lastAutonomy = now;
        Wish w = new Wish();
        w.id = "w-" + UUID.randomUUID().toString().substring(0, 8);
        w.player = uuid;
        w.playerName = p == null ? null : p.getGameProfile().name();
        w.text = "(자율) " + description;
        w.origin = "autonomous";
        w.created = now;
        w.state = Wish.State.ANALYZING;
        st.wishes.put(w.id, w);
        eng.agent().submit(new TurnRequest(w.id, p == null ? null : p.getUUID(), TurnRequest.Kind.AUTONOMOUS, null, description));
    }

    private boolean hasHistory(String uuid, int min) {
        int n = 0;
        for (MemoryRecord r : eng.memory().all()) {
            if (r.archived) continue;
            if (uuid.equals(r.owner) || r.scope == MemoryRecord.Scope.WORLD) n++;
            if (n >= min) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 트리거

    public void onJoin(ServerPlayer p, long prevSeen) {
        if (prevSeen <= 0) return;
        long away = System.currentTimeMillis() - prevSeen;
        if (away < AiConfig.get().autonomyAbsenceHours * 3_600_000L) return;
        if (!hasHistory(p.getUUID().toString(), 2)) return;
        trigger(p, p.getGameProfile().name() + " returned to the world after " + (away / 3_600_000L) + " hours away. "
                + "If their past (memories, lasting abilities, unfinished quests, creatures) suggests a follow-up story, a greeting or a small change, make it happen.");
    }

    public void onDeath(ServerPlayer p, String cause) {
        long now = System.currentTimeMillis();
        Long last = deathCooldown.get(p.getUUID());
        if (last != null && now - last < 30 * 60_000L) return;
        deathCooldown.put(p.getUUID(), now);
        if (!hasHistory(p.getUUID().toString(), 3)) return;
        if (ThreadLocalRandom.current().nextDouble() > 0.35) return;
        trigger(p, p.getGameProfile().name() + " just died (" + cause + ") at " + (int) p.getX() + "," + (int) p.getY() + "," + (int) p.getZ()
                + ". Consider whether a past wish, ability or legend reacts to this death.");
    }

    public void onQuestFinished(Quest q, Quest.State end) {
        if (q.owner == null || end == Quest.State.CANCELLED) return;
        ServerPlayer p = eng.player(UUID.fromString(q.owner));
        if (p == null) return;
        // 소원의 결과로 끝난 퀘스트는 그 소원 스크립트가 보상하므로, 여기서는 가끔 후일담만
        if (ThreadLocalRandom.current().nextDouble() > 0.25) return;
        trigger(p, "Quest '" + q.title + "' of " + q.ownerName + " ended: " + end + ". Consider an epilogue or consequence in the world (do not repeat rewards already given).");
    }

    public void onSpecialItem(ServerPlayer p, String itemName, String scriptId) {
        if (ThreadLocalRandom.current().nextDouble() > 0.3) return;
        var rec = scriptId == null ? null : eng.state().scripts.get(scriptId);
        trigger(p, p.getGameProfile().name() + " picked up the special item \"" + itemName + "\""
                + (rec == null ? "" : " created by the script '" + rec.name + "' (" + rec.state + ")") + ". Consider whether it awakens something.");
    }

    public void tick() {
        long t = eng.tick();
        if (t % 100 == 0) checkPlaces();
        AiConfig cfg = AiConfig.get();
        if (cfg.autonomyPeriodicMinutes > 0) {
            if (nextPeriodic < 0) nextPeriodic = t + cfg.autonomyPeriodicMinutes * 1200L;
            if (t >= nextPeriodic) {
                nextPeriodic = t + cfg.autonomyPeriodicMinutes * 1200L;
                periodic();
            }
        }
        if (t >= nextMaintenance) {
            nextMaintenance = t + 20 * 60 * 10;
            maintainMemory();
        }
    }

    private void periodic() {
        List<ServerPlayer> ps = new ArrayList<>(eng.server().getPlayerList().getPlayers());
        if (ps.isEmpty()) return;
        ServerPlayer p = ps.get(ThreadLocalRandom.current().nextInt(ps.size()));
        if (!hasHistory(p.getUUID().toString(), 4)) return;
        if (ThreadLocalRandom.current().nextDouble() > 0.5) return;
        trigger(p, "Periodic reflection: look at the world's memories and the current state around " + p.getGameProfile().name()
                + ". If an old story (creature, place, unresolved quest, legend) could naturally continue now, start a small event. Otherwise stay silent.");
    }

    /**
     * 기억 속 장소(좌표가 있는 기억)에 플레이어가 "다시" 들어오면 후속 이벤트 기회.
     * 방금 만든 기억(10분 이내)이나 계속 서 있는 경우에는 반응하지 않고, 바깥에서 안으로 들어오는 순간에만 본다.
     */
    private void checkPlaces() {
        List<MemoryRecord> places = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (MemoryRecord r : eng.memory().all()) {
            if (r.pos != null && !r.archived && r.importance >= 4) places.add(r);
        }
        if (places.isEmpty()) return;
        for (ServerPlayer p : eng.server().getPlayerList().getPlayers()) {
            String dim = McUtil.dimId(p.level());
            for (MemoryRecord r : places) {
                String key = p.getUUID() + "|" + r.id;
                boolean in = (r.dimension == null || r.dimension.equals(dim))
                        && (r.scope == MemoryRecord.Scope.WORLD || p.getUUID().toString().equals(r.owner))
                        && sq(p.getX() - r.pos[0]) + sq(p.getZ() - r.pos[2]) <= 144 && Math.abs(p.getY() - r.pos[1]) <= 16;
                boolean was = insidePlace.contains(key);
                if (in == was) continue;
                if (!in) { insidePlace.remove(key); continue; }
                insidePlace.add(key);
                if (now - r.created < AiConfig.get().autonomyPlaceMinAgeMinutes * 60_000L) continue;   // 방금 생긴 장소
                AiState.PlayerProfile prof = eng.state().profile(p.getUUID().toString(), p.getGameProfile().name());
                Long last = prof.placeVisits.get(r.id);
                if (last != null && now - last < AiConfig.get().autonomyPlaceCooldownHours * 3_600_000L) continue;
                prof.placeVisits.put(r.id, now);
                trigger(p, p.getGameProfile().name() + " came back to a remembered place: \"" + r.text + "\" (memory " + r.id + "). "
                        + "Consider a follow-up event connected to that memory.");
                return;
            }
        }
    }

    private static double sq(double d) {
        return d * d;
    }

    /** 기억이 많이 쌓인 플레이어의 오래된 기억을 요약해 접는다. */
    private void maintainMemory() {
        AiConfig cfg = AiConfig.get();
        for (AiState.PlayerProfile prof : eng.state().players.values()) {
            Long since = summarizing.get(prof.uuid);
            if (since != null && System.currentTimeMillis() - since < 15 * 60_000L) continue;
            List<MemoryRecord> c = eng.memory().summarizeCandidates(prof.uuid, cfg.memorySummarizeThreshold, 24);
            if (c.size() < 8) continue;
            StringBuilder sb = new StringBuilder();
            for (MemoryRecord r : c) sb.append("- ").append(r.text).append('\n');
            List<String> ids = c.stream().map(r -> r.id).toList();
            summarizing.put(prof.uuid, System.currentTimeMillis());
            eng.agent().summarize(sb.toString(), summary -> {
                summarizing.remove(prof.uuid);
                List<MemoryRecord> still = new ArrayList<>();
                for (String id : ids) {
                    MemoryRecord r = eng.memory().get(id);
                    if (r != null && !r.archived) still.add(r);
                }
                eng.memory().replaceWithSummary(still, McUtil.clean(summary, 600), System.currentTimeMillis());
            });
            return;     // 한 번에 한 명씩
        }
    }
}
