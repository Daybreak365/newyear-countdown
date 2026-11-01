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
        /** true 면 이 플레이어의 사적 기억은 누구와도 공유되지 않는다 (/wish memory lock). 잠그면 기존 공유도 거둔다. */
        public boolean memoryLocked;
        /** 마지막으로 새 소원을 빈 시각 (ms). 소원 쿨타임 기준이며 재시작해도 이어진다. 0 = 쿨타임 없음. */
        public long lastWishAt;

        /** 새 소원 쿨타임이 남은 시간 (ms, 없으면 0). */
        public long wishCooldownLeft(long now, int cooldownSeconds) {
            if (cooldownSeconds <= 0 || lastWishAt <= 0) return 0;
            return Math.max(0, lastWishAt + cooldownSeconds * 1000L - now);
        }
    }

    public long engineTick;
    public Map<String, Wish> wishes = new LinkedHashMap<>();
    public Map<String, Quest> quests = new LinkedHashMap<>();
    public Map<String, dev.newyear.countdown.ai.script.PaymentLedger.Receipt> payments = new LinkedHashMap<>();
    public Map<String, StatusEntry> statuses = new LinkedHashMap<>();
    public Map<String, ScriptRecord> scripts = new LinkedHashMap<>();
    public Map<String, PlayerProfile> players = new LinkedHashMap<>();
    public Map<String, dev.newyear.countdown.ai.script.ContentLibrary.Definition> content = new LinkedHashMap<>();
    public Map<String, dev.newyear.countdown.ai.extension.NativeExtensions.Draft> extensions = new LinkedHashMap<>();
    /** AI 가 그린 동적 텍스처 (세계 자원: 그 텍스처를 입은 아이템이 남아 있을 수 있으므로 스크립트가 끝나도 남긴다). */
    public Map<String, dev.newyear.countdown.ai.script.Textures.Def> textures = new LinkedHashMap<>();
    /** 관리자 킬 스위치 상태 (재시작 후에도 유지). */
    public boolean killSwitch;
    public long lastAutonomyGlobal;
    /** 마지막으로 소원이 시간·날씨(모두의 하늘)를 바꾼 시각 (ms). */
    public long lastSkyChange;
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
        if (payments == null) payments = new LinkedHashMap<>();
        if (wishes == null) wishes = new LinkedHashMap<>();
        if (quests == null) quests = new LinkedHashMap<>();
        if (statuses == null) statuses = new LinkedHashMap<>();
        if (scripts == null) scripts = new LinkedHashMap<>();
        if (players == null) players = new LinkedHashMap<>();
        if (content == null) content = new LinkedHashMap<>();
        if (extensions == null) extensions = new LinkedHashMap<>();
        if (textures == null) textures = new LinkedHashMap<>();
        textures.values().removeIf(t -> t == null || t.id == null || t.rows == null || t.palette == null);
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
            if (r.runtime == null) r.runtime = new com.google.gson.JsonObject();
            if (r.history == null) r.history = new ArrayList<>();
            if (r.subscriptions == null) r.subscriptions = new ArrayList<>();
            if (r.apiCalls == null) r.apiCalls = new ArrayList<>();
            if (r.permissions == null) r.permissions = new ArrayList<>();
            if (r.entities == null) r.entities = new ArrayList<>();
            if (r.keptEntities == null) r.keptEntities = new ArrayList<>();
            if (r.blocks == null) r.blocks = new ArrayList<>();
            if (r.blockConflicts == null) r.blockConflicts = new ArrayList<>();
            if (r.goals == null) r.goals = new ArrayList<>();
            if (r.models == null) r.models = new ArrayList<>();
            if (r.preview == null) r.preview = new ArrayList<>();
            if (r.edits == null) r.edits = new ArrayList<>();
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
