package dev.newyear.countdown.ai;

import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.ai.agent.TurnRequest;
import dev.newyear.countdown.ai.agent.WishAgent;
import dev.newyear.countdown.ai.autonomy.AutonomyManager;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.memory.MemoryStore;
import dev.newyear.countdown.ai.script.ScriptManager;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.ScriptValidator;
import dev.newyear.countdown.ai.ui.UiService;
import dev.newyear.countdown.ai.usage.UsageTracker;
import dev.newyear.countdown.ai.wish.QuestService;
import dev.newyear.countdown.ai.wish.Wish;
import dev.newyear.countdown.ai.wish.WishManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 소원 엔진의 중심. 서버가 켜질 때 만들어지고 꺼질 때 저장 후 사라진다.
 * AI 가 꺼져 있거나 API 가 끊겨도 이미 만들어진 스크립트·퀘스트는 계속 돈다 (킬 스위치는 둘 다 멈춘다).
 */
public final class WishEngine {
    private static volatile WishEngine instance;

    private final MinecraftServer server;
    private final AiStorage storage;
    private final AiState state;
    private final MemoryStore memory = new MemoryStore();
    private final UsageTracker usage = new UsageTracker(ZoneId.of("Asia/Seoul"));
    private final ScriptManager scripts;
    private final UiService ui;
    private final QuestService quests;
    private final WishManager wishes;
    private final WishAgent agent;
    private final AutonomyManager autonomy;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WishAI-IO");
        t.setDaemon(true);
        return t;
    });
    private long lastSaveTick;

    public static WishEngine get() {
        return instance;
    }

    private WishEngine(MinecraftServer server) {
        this.server = server;
        this.storage = new AiStorage(server.getWorldPath(LevelResource.ROOT).resolve("newyearcountdown_ai"));
        this.state = storage.loadState();
        AiConfig cfg = AiConfig.get();
        memory.maxPerPlayer = cfg.memoryMaxPerPlayer;
        memory.maxWorld = cfg.memoryMaxWorld;
        memory.loadAll(storage.loadMemory());
        AiStorage.UsageFile u = storage.loadUsage();
        if (u != null) usage.load(u.today, u.lifetime);
        this.scripts = new ScriptManager(this);
        this.ui = new UiService(this);
        this.quests = new QuestService(this);
        this.wishes = new WishManager(this);
        this.agent = new WishAgent(this);
        this.autonomy = new AutonomyManager(this);
    }

    public static void start(MinecraftServer server) {
        AiConfig.load();
        WishEngine e = new WishEngine(server);
        instance = e;
        ScriptValidator.primeDocs();
        e.restore();
        NewYearCountdown.LOGGER.info("[WishAI] engine started: model={}, api key {}, {} scripts, {} memories, {} wishes",
                AiConfig.get().model, AiConfig.hasApiKey() ? "present" : "MISSING (set OPENAI_API_KEY)",
                e.state.scripts.size(), e.memory.all().size(), e.state.wishes.size());
    }

    public static void stop() {
        WishEngine e = instance;
        if (e == null) return;
        e.agent.shutdown();
        e.scripts.saveAllStates();
        e.writeNow();
        e.io.shutdown();
        instance = null;
    }

    /** 서버 재시작 후 복구: ACTIVE 스크립트 재실행, 대기 중이던 AI 상태 정리. */
    private void restore() {
        for (Wish w : state.wishes.values()) w.thinking = false;
        if (state.killSwitch) {
            NewYearCountdown.LOGGER.warn("[WishAI] kill switch is ON — scripts stay suspended until /wishai enable");
            for (ScriptRecord r : state.scripts.values()) if (r.state == ScriptRecord.State.ACTIVE) r.state = ScriptRecord.State.SUSPENDED;
            return;
        }
        int ok = 0, bad = 0;
        for (ScriptRecord r : new ArrayList<>(state.scripts.values())) {
            if (r.state != ScriptRecord.State.ACTIVE) continue;
            if (scripts.activate(r, true).ok()) ok++;
            else {
                bad++;
                NewYearCountdown.LOGGER.warn("[WishAI] could not restore script {}: {}", r.id, r.lastError);
            }
        }
        if (ok + bad > 0) NewYearCountdown.LOGGER.info("[WishAI] restored {} scripts ({} failed)", ok, bad);
    }

    // ---------------------------------------------------------------- 접근자

    public MinecraftServer server() {
        return server;
    }

    public AiState state() {
        return state;
    }

    public MemoryStore memory() {
        return memory;
    }

    public UsageTracker usage() {
        return usage;
    }

    public ScriptManager scripts() {
        return scripts;
    }

    public UiService ui() {
        return ui;
    }

    public QuestService quests() {
        return quests;
    }

    public WishManager wishes() {
        return wishes;
    }

    public WishAgent agent() {
        return agent;
    }

    public AutonomyManager autonomy() {
        return autonomy;
    }

    public AiStorage storage() {
        return storage;
    }

    public long tick() {
        return state.engineTick;
    }

    public String spiritName() {
        return AiConfig.get().spiritName;
    }

    /** AI 호출 가능 여부 (설정 + 킬 스위치). 스크립트 실행 여부와는 별개. */
    public boolean aiEnabled() {
        return AiConfig.get().enabled && !state.killSwitch;
    }

    public boolean aiAvailable() {
        return aiEnabled() && AiConfig.hasApiKey();
    }

    // ---------------------------------------------------------------- 킬 스위치

    public int killSwitch(String by) {
        state.killSwitch = true;
        int n = scripts.suspendAll("kill switch by " + by);
        agent.shutdownQueued();
        NewYearCountdown.LOGGER.warn("[WishAI] KILL SWITCH activated by {} — {} scripts suspended, AI calls blocked", by, n);
        writeAsync();
        return n;
    }

    public int release(String by) {
        state.killSwitch = false;
        int n = scripts.resumeAll();
        NewYearCountdown.LOGGER.warn("[WishAI] kill switch released by {} — {} scripts resumed", by, n);
        return n;
    }

    // ---------------------------------------------------------------- 틱·저장

    /** 서버 틱마다 GameEvents 가 부른다. */
    public void serverTick() {
        state.engineTick++;
        if (!state.killSwitch) {
            try {
                scripts.tick();
            } catch (RuntimeException e) {
                // 스크립트 계층의 예상치 못한 오류가 서버 전체를 멈추지 않게 한다
                NewYearCountdown.LOGGER.error("[WishAI] script tick failed", e);
            }
            quests.tick();
        }
        ui.tick();
        if (!state.killSwitch) autonomy.tick();
        if (state.engineTick - lastSaveTick >= 20 * 60 * 2) {
            lastSaveTick = state.engineTick;
            scripts.saveAllStates();
            writeAsync();
        }
    }

    /** 월드 저장과 함께 (또는 주기적으로) 저장한다. 직렬화는 서버 스레드, 쓰기는 IO 스레드. */
    public void writeAsync() {
        AiStorage.Snapshot s = snapshot();
        io.execute(() -> storage.write(s));
    }

    public void writeNow() {
        storage.write(snapshot());
    }

    private AiStorage.Snapshot snapshot() {
        scripts.pruneRecords();
        wishes.prune();
        AiStorage.UsageFile uf = new AiStorage.UsageFile();
        uf.today = usage.today();
        uf.lifetime = usage.lifetime();
        return storage.snapshot(state, new ArrayList<>(memory.all()), uf);
    }

    // ---------------------------------------------------------------- 연결점

    public void onJoin(ServerPlayer p) {
        AiState.PlayerProfile prof = state.profile(p.getUUID().toString(), p.getGameProfile().name());
        long prevSeen = prof.lastSeen;
        prof.lastJoin = System.currentTimeMillis();
        prof.lastSeen = prof.lastJoin;
        ui.syncPlayer(p);
        autonomy.onJoin(p, prevSeen);
    }

    public void onLeave(ServerPlayer p) {
        AiState.PlayerProfile prof = state.profile(p.getUUID().toString(), p.getGameProfile().name());
        prof.lastSeen = System.currentTimeMillis();
    }

    public void rememberFromScript(MemoryRecord r) {
        MemoryRecord saved = memory.add(r, System.currentTimeMillis());
        if (r.wishId != null) {
            Wish w = state.wishes.get(r.wishId);
            if (w != null && !w.memoryIds.contains(saved.id)) w.memoryIds.add(saved.id);
        }
    }

    /** 스크립트의 ai.ask: 소원(또는 새 기록)에 대한 AI 턴을 연다. */
    public boolean requestFromScript(ScriptRecord rec, String text, String data) {
        if (!aiAvailable()) return false;
        String wishId = rec.wishId;
        Wish w = wishId == null ? null : state.wishes.get(wishId);
        if (w == null || w.state.terminal()) {
            // 끝난 소원의 능력이 부른 경우: 이어지는 이야기 기록을 새로 연다
            w = new Wish();
            w.id = "w-" + UUID.randomUUID().toString().substring(0, 8);
            w.player = rec.owner;
            w.playerName = rec.ownerName;
            w.text = "(" + rec.name + ") " + text;
            w.origin = "script";
            w.created = System.currentTimeMillis();
            state.wishes.put(w.id, w);
            rec.wishId = w.id;
            w.scriptIds.add(rec.id);
        }
        agent.submit(new TurnRequest(w.id, rec.owner == null ? null : UUID.fromString(rec.owner), TurnRequest.Kind.SCRIPT,
                data, "script '" + rec.name + "' (" + rec.id + ") says: " + text));
        return true;
    }

    public void notifyAdmins(String msg) {
        NewYearCountdown.LOGGER.info("[WishAI] {}", msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isAdmin(p)) p.sendSystemMessage(Component.literal("§6[WishAI] §f" + msg));
        }
    }

    public static boolean isAdmin(ServerPlayer p) {
        return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** 자가 진단용 가짜 플레이어 (플레이어 목록에는 없지만 조회는 되게). */
    private final java.util.Map<UUID, ServerPlayer> testPlayers = new java.util.HashMap<>();

    public void addTestPlayer(ServerPlayer p) {
        testPlayers.put(p.getUUID(), p);
    }

    public void removeTestPlayer(UUID id) {
        testPlayers.remove(id);
    }

    /** UUID 로 온라인 플레이어 찾기 (자가 진단용 가짜 플레이어 포함). */
    public ServerPlayer player(UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null ? p : testPlayers.get(id);
    }

    public ServerPlayer playerByName(String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        if (p != null) return p;
        for (ServerPlayer t : testPlayers.values()) if (t.getGameProfile().name().equalsIgnoreCase(name)) return t;
        return null;
    }

    public List<ServerPlayer> online() {
        return server.getPlayerList().getPlayers();
    }
}
