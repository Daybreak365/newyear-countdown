package dev.newyear.countdown.ai;

import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.StatusEntry;
import dev.newyear.countdown.ai.wish.Wish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실제 게임 상태 쪽 기록 (소원·퀘스트·스크립트·HUD 상태·플레이어 프로필).
 * AI 의 기억(MemoryStore)과는 따로 저장한다.
 */
public final class AiState {
    public static final class PlayerProfile {
        public String uuid;
        public String name;
        public long firstSeen;
        public long lastSeen;
        public long lastJoin;
        public long lastAutonomy;
        public int wishes;
        /** 대화가 열려 있는 소원. */
        public String openWishId;
        /** 장소별 마지막 자율 이벤트 시각 (기억 ID → ms). */
        public Map<String, Long> placeVisits = new LinkedHashMap<>();
    }

    public long engineTick;
    public Map<String, Wish> wishes = new LinkedHashMap<>();
    public Map<String, Quest> quests = new LinkedHashMap<>();
    public Map<String, StatusEntry> statuses = new LinkedHashMap<>();
    public Map<String, ScriptRecord> scripts = new LinkedHashMap<>();
    public Map<String, PlayerProfile> players = new LinkedHashMap<>();
    /** 관리자 킬 스위치 상태 (재시작 후에도 유지). */
    public boolean killSwitch;
    public long lastAutonomyGlobal;
    public List<Long> autonomyCalls = new ArrayList<>();

    public PlayerProfile profile(String uuid, String name) {
        PlayerProfile p = players.computeIfAbsent(uuid, k -> {
            PlayerProfile n = new PlayerProfile();
            n.uuid = k;
            n.firstSeen = System.currentTimeMillis();
            return n;
        });
        if (name != null) p.name = name;
        if (p.placeVisits == null) p.placeVisits = new LinkedHashMap<>();
        return p;
    }

    public PlayerProfile findPlayer(String nameOrUuid) {
        PlayerProfile p = players.get(nameOrUuid);
        if (p != null) return p;
        for (PlayerProfile q : players.values()) if (q.name != null && q.name.equalsIgnoreCase(nameOrUuid)) return q;
        return null;
    }

    public void fixNulls() {
        if (wishes == null) wishes = new LinkedHashMap<>();
        if (quests == null) quests = new LinkedHashMap<>();
        if (statuses == null) statuses = new LinkedHashMap<>();
        if (scripts == null) scripts = new LinkedHashMap<>();
        if (players == null) players = new LinkedHashMap<>();
        if (autonomyCalls == null) autonomyCalls = new ArrayList<>();
        for (Wish w : wishes.values()) {
            if (w.conversation == null) w.conversation = new ArrayList<>();
            if (w.scriptIds == null) w.scriptIds = new ArrayList<>();
            if (w.questIds == null) w.questIds = new ArrayList<>();
            if (w.memoryIds == null) w.memoryIds = new ArrayList<>();
            if (w.statusIds == null) w.statusIds = new ArrayList<>();
            if (w.consents == null) w.consents = new ArrayList<>();
            if (w.history == null) w.history = new ArrayList<>();
            if (w.state == null) w.state = Wish.State.CREATED;
        }
        for (ScriptRecord r : scripts.values()) {
            if (r.history == null) r.history = new ArrayList<>();
            if (r.subscriptions == null) r.subscriptions = new ArrayList<>();
            if (r.apiCalls == null) r.apiCalls = new ArrayList<>();
            if (r.permissions == null) r.permissions = new ArrayList<>();
            if (r.entities == null) r.entities = new ArrayList<>();
            if (r.keptEntities == null) r.keptEntities = new ArrayList<>();
            if (r.blocks == null) r.blocks = new ArrayList<>();
            if (r.timers == null) r.timers = new ArrayList<>();
            if (r.behaviours == null) r.behaviours = new ArrayList<>();
            if (r.state == null) r.state = ScriptRecord.State.STOPPED;
            if (r.stateJson == null) r.stateJson = new com.google.gson.JsonObject();
        }
        for (Quest q : quests.values()) {
            if (q.objectives == null) q.objectives = new ArrayList<>();
            if (q.participants == null) q.participants = new ArrayList<>();
            if (q.state == null) q.state = Quest.State.CANCELLED;
        }
    }
}
