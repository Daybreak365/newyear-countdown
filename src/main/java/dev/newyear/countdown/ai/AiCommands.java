package dev.newyear.countdown.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.ScriptValidator;
import dev.newyear.countdown.ai.usage.UsageTracker;
import dev.newyear.countdown.ai.wish.Wish;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /wish (플레이어) 와 /wishai (관리자) 명령. */
public final class AiCommands {
    private AiCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> {
            registerWish(dispatcher);
            registerAdmin(dispatcher);
        });
    }

    private static WishEngine eng(CommandContext<CommandSourceStack> c) {
        WishEngine e = WishEngine.get();
        if (e == null) c.getSource().sendFailure(Component.literal("Wish engine is not running"));
        return e;
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    // ---------------------------------------------------------------- /wish

    private static void registerWish(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wish")
                .then(memoryCommands())
                .then(Commands.literal("cancel").executes(c -> {
                    WishEngine e = eng(c);
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (e == null) return 0;
                    Wish w = e.wishes().openWishOf(p);
                    if (w == null) { say(c, "열린 소원이 없습니다."); return 0; }
                    e.wishes().cancel(w.id, "player cancelled");
                    say(c, "소원 '" + w.text + "'을(를) 취소했습니다.");
                    return 1;
                }))
                .then(Commands.literal("history").executes(c -> {
                    WishEngine e = eng(c);
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (e == null) return 0;
                    List<Wish> mine = new ArrayList<>();
                    for (Wish w : e.state().wishes.values()) if (p.getUUID().toString().equals(w.player)) mine.add(w);
                    mine.sort((a, b) -> Long.compare(b.created, a.created));
                    say(c, "§6내 소원 (" + mine.size() + ")");
                    for (int i = 0; i < Math.min(10, mine.size()); i++) {
                        Wish w = mine.get(i);
                        say(c, " §7- §f" + w.text + " §8[" + w.state + "]" + (w.outcome == null || w.outcome.isEmpty() ? "" : " §7" + w.outcome));
                    }
                    return 1;
                }))
                .then(Commands.literal("answer")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString()).executes(c -> {
                                    WishEngine e = eng(c);
                                    if (e == null) return 0;
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    String id = StringArgumentType.getString(c, "id");
                                    String v = StringArgumentType.getString(c, "value");
                                    var pd = e.ui().pending().get(id);
                                    int index = 0;
                                    if (pd != null && !pd.kind.equals("input")) {
                                        try { index = Integer.parseInt(v.trim()); } catch (NumberFormatException ex) { index = pd.options.indexOf(v.trim()); }
                                    }
                                    String err = e.ui().handleInteract(p, id, index, v, 0);
                                    if (err != null) c.getSource().sendFailure(Component.literal("응답할 수 없습니다: " + err));
                                    return err == null ? 1 : 0;
                                }))))
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    WishEngine e = eng(c);
                    if (e == null) return 0;
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    e.wishes().playerMessage(p, "", StringArgumentType.getString(c, "text"), "command");
                    return 1;
                }))
                .executes(c -> {
                    say(c, "§6/wish <소원>§f — 새벽별에게 소원을 빕니다 (대화 중이면 대답으로 이어집니다). 대화창 단축키는 조작 설정의 '소원 대화'.");
                    return 1;
                }));
    }

    // ---------------------------------------------------------------- /wish memory (플레이어의 기억 공유·사생활)

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> memoryCommands() {
        return Commands.literal("memory")
                .executes(c -> myMemories(c, false))
                .then(Commands.literal("shared").executes(c -> myMemories(c, true)))
                .then(Commands.literal("share").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
                            WishEngine e = eng(c);
                            if (e == null) return 0;
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            var me = e.state().profile(p.getUUID().toString(), p.getGameProfile().name());
                            if (me.memoryLocked) { c.getSource().sendFailure(Component.literal("기억이 잠겨 있습니다. /wish memory unlock 후 공유하세요.")); return 0; }
                            var other = e.state().findPlayer(StringArgumentType.getString(c, "player"));
                            if (other == null) { c.getSource().sendFailure(Component.literal("이 서버에 온 적 없는 플레이어입니다")); return 0; }
                            var res = e.memory().share(StringArgumentType.getString(c, "id"), other.uuid, p.getUUID().toString());
                            String msg = switch (res) {
                                case OK -> "이 기억을 " + other.name + "님과 나눴습니다. 새벽별은 이제 " + other.name + "님과도 이 일을 이야기할 수 있습니다.";
                                case ALREADY -> "이미 " + other.name + "님과 나눈 기억입니다.";
                                case NOT_OWNER -> "내 기억만 나눌 수 있습니다.";
                                case NOT_PRIVATE -> "모두가 아는 세계의 기억입니다.";
                                case NOT_FOUND -> "그런 기억이 없습니다 (/wish memory 로 ID 확인).";
                                case SELF -> "자기 자신과는 나눌 수 없습니다.";
                            };
                            say(c, msg);
                            return res == dev.newyear.countdown.ai.memory.MemoryStore.ShareResult.OK ? 1 : 0;
                        }))))
                .then(Commands.literal("unshare").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> unshareMine(c, null))
                        .then(Commands.argument("player", StringArgumentType.word()).executes(c -> unshareMine(c, StringArgumentType.getString(c, "player"))))))
                .then(Commands.literal("forget").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                    WishEngine e = eng(c);
                    if (e == null) return 0;
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    MemoryRecord r = e.memory().get(StringArgumentType.getString(c, "id"));
                    if (r == null || r.scope != MemoryRecord.Scope.PLAYER || !p.getUUID().toString().equals(r.owner)) {
                        c.getSource().sendFailure(Component.literal("내 사적 기억만 지울 수 있습니다."));
                        return 0;
                    }
                    e.memory().forget(r.id);
                    say(c, "새벽별이 그 일을 잊었습니다: " + r.text);
                    return 1;
                })))
                .then(Commands.literal("lock").executes(c -> lockMemories(c, true)))
                .then(Commands.literal("unlock").executes(c -> lockMemories(c, false)));
    }

    private static int myMemories(CommandContext<CommandSourceStack> c, boolean sharedToMe) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        ServerPlayer p;
        try {
            p = c.getSource().getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            return 0;
        }
        String me = p.getUUID().toString();
        List<MemoryRecord> list = new ArrayList<>(sharedToMe ? e.memory().sharedTo(me) : e.memory().ofPlayer(me));
        list.removeIf(r -> r.archived || r.scope != MemoryRecord.Scope.PLAYER);
        list.sort((a, b) -> Long.compare(b.created, a.created));
        var prof = e.state().players.get(me);
        say(c, sharedToMe ? "§6다른 이들이 나눠 준 기억 (" + list.size() + ")"
                : "§6새벽별이 기억하는 나와의 일 (" + list.size() + ")" + (prof != null && prof.memoryLocked ? " §c[잠김]" : ""));
        for (int i = 0; i < Math.min(12, list.size()); i++) {
            MemoryRecord r = list.get(i);
            String who = sharedToMe ? " §7← " + r.ownerName : r.shared() ? " §b↔ " + dev.newyear.countdown.ai.agent.ContextBuilder.sharedNames(e, r) : "";
            say(c, "§e" + r.id + " §f" + r.text + who);
        }
        if (!sharedToMe) say(c, "§7공유: /wish memory share <ID> <플레이어> · 거두기: unshare <ID> [플레이어] · 잊기: forget <ID> · 잠금: lock/unlock");
        return list.size();
    }

    private static int unshareMine(CommandContext<CommandSourceStack> c, String player) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WishEngine e = eng(c);
        if (e == null) return 0;
        ServerPlayer p = c.getSource().getPlayerOrException();
        String with = null;
        if (player != null) {
            var other = e.state().findPlayer(player);
            if (other == null) { c.getSource().sendFailure(Component.literal("모르는 플레이어")); return 0; }
            with = other.uuid;
        }
        int n = e.memory().unshare(StringArgumentType.getString(c, "id"), with, p.getUUID().toString());
        say(c, n > 0 ? "공유를 거뒀습니다 (" + n + "명)." : "거둘 공유가 없습니다.");
        return n;
    }

    private static int lockMemories(CommandContext<CommandSourceStack> c, boolean lock) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WishEngine e = eng(c);
        if (e == null) return 0;
        ServerPlayer p = c.getSource().getPlayerOrException();
        var prof = e.state().profile(p.getUUID().toString(), p.getGameProfile().name());
        prof.memoryLocked = lock;
        if (lock) {
            int n = e.memory().unshareAllOf(prof.uuid);
            say(c, "§6기억을 잠갔습니다.§f 새벽별은 나와의 일을 누구에게도 나누지 않습니다" + (n > 0 ? " (공유 " + n + "개 거둠)." : "."));
        } else {
            say(c, "기억 잠금을 풀었습니다. 내가 부탁할 때만 다른 이와 기억을 나눕니다.");
        }
        return 1;
    }

    // ---------------------------------------------------------------- /wishai

    private static void registerAdmin(CommandDispatcher<CommandSourceStack> d) {
        dev.newyear.countdown.ai.extension.ExtensionCommands.register(d);
        d.register(Commands.literal("wishai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(AiCommands::status))
                .then(Commands.literal("disable").executes(c -> {
                    WishEngine e = eng(c);
                    if (e == null) return 0;
                    int n = e.killSwitch(c.getSource().getTextName());
                    say(c, "§c킬 스위치 ON§f — AI 호출 차단, 스크립트 " + n + "개 일시정지. 세계 결과물은 그대로 남습니다. /wishai enable 로 복구.");
                    return 1;
                }))
                .then(Commands.literal("enable").executes(c -> {
                    WishEngine e = eng(c);
                    if (e == null) return 0;
                    int n = e.release(c.getSource().getTextName());
                    say(c, "§a킬 스위치 OFF§f — 스크립트 " + n + "개 재개.");
                    return 1;
                }))
                .then(Commands.literal("reload").executes(c -> {
                    AiConfig.load();
                    say(c, "AI 설정을 다시 읽었습니다: model=" + AiConfig.get().model + ", effort=" + AiConfig.get().reasoningEffort
                            + " (동시 요청 수 변경은 재시작 후 적용)");
                    return 1;
                }))
                .then(Commands.literal("scripts")
                        .executes(c -> listScripts(c, null, false))
                        .then(Commands.literal("all").executes(c -> listScripts(c, null, true)))
                        .then(Commands.argument("player", StringArgumentType.word()).executes(c -> listScripts(c, StringArgumentType.getString(c, "player"), true))))
                .then(Commands.literal("script").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> scriptInfo(c, false))
                        .then(Commands.literal("code").executes(c -> scriptInfo(c, true)))
                        .then(Commands.literal("stop").executes(c -> scriptAction(c, "stop")))
                        .then(Commands.literal("rollback").executes(c -> scriptAction(c, "rollback"))
                                .then(Commands.literal("force").executes(c -> scriptAction(c, "rollback_force"))))
                        .then(Commands.literal("preview").executes(c -> scriptAction(c, "preview")))
                        .then(Commands.literal("suspend").executes(c -> scriptAction(c, "suspend")))
                        .then(Commands.literal("resume").executes(c -> scriptAction(c, "resume")))
                        .then(Commands.literal("delete").executes(c -> scriptAction(c, "delete")))
                        .then(Commands.literal("revert").executes(c -> scriptAction(c, "revert")))))
                .then(Commands.literal("gui").executes(c -> {
                    WishEngine e = eng(c);
                    if (e == null) return 0;
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!e.ui().hasModClient(p)) { c.getSource().sendFailure(Component.literal("관리자 화면은 모드가 설치된 클라이언트에서만 열립니다.")); return 0; }
                    e.admin().open(p);
                    return 1;
                }))
                .then(Commands.literal("approve").then(Commands.argument("id", StringArgumentType.word()).executes(c -> approve(c, true))))
                .then(Commands.literal("deny").then(Commands.argument("id", StringArgumentType.word()).executes(c -> approve(c, false))))
                .then(Commands.literal("wishes")
                        .executes(c -> listWishes(c, null))
                        .then(Commands.argument("player", StringArgumentType.word()).executes(c -> listWishes(c, StringArgumentType.getString(c, "player")))))
                .then(Commands.literal("wish").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> wishInfo(c))
                        .then(Commands.literal("cancel").executes(c -> {
                            WishEngine e = eng(c);
                            if (e == null) return 0;
                            boolean ok = e.wishes().cancel(StringArgumentType.getString(c, "id"), "cancelled by admin");
                            say(c, ok ? "취소했습니다." : "열린 소원이 아닙니다.");
                            return ok ? 1 : 0;
                        }))))
                .then(Commands.literal("cooldown")
                        .executes(AiCommands::cooldownInfo)
                        .then(Commands.literal("set").then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86_400)).executes(c -> {
                            int secs = IntegerArgumentType.getInteger(c, "seconds");
                            AiConfig.get().wishCooldownSeconds = secs;
                            AiConfig.save();
                            say(c, secs == 0 ? "소원 쿨타임을 껐습니다." : "소원 쿨타임: " + secs + "초 (저장됨)");
                            return 1;
                        })))
                        .then(Commands.literal("reset")
                                .then(Commands.literal("all").executes(c -> {
                                    WishEngine e = eng(c);
                                    if (e == null) return 0;
                                    int n = 0;
                                    for (var prof : e.state().players.values()) {
                                        if (prof.lastWishAt > 0) n++;
                                        prof.lastWishAt = 0;
                                    }
                                    say(c, "모든 플레이어의 소원 쿨타임을 초기화했습니다 (" + n + "명).");
                                    return 1;
                                }))
                                .then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
                                    WishEngine e = eng(c);
                                    if (e == null) return 0;
                                    var prof = e.state().findPlayer(StringArgumentType.getString(c, "player"));
                                    if (prof == null) { c.getSource().sendFailure(Component.literal("모르는 플레이어")); return 0; }
                                    prof.lastWishAt = 0;
                                    say(c, prof.name + "의 소원 쿨타임을 초기화했습니다.");
                                    return 1;
                                }))))
                .then(Commands.literal("price")
                        .executes(AiCommands::prices)
                        .then(Commands.argument("model", StringArgumentType.string())
                                .then(Commands.literal("remove").executes(c -> {
                                    String m = StringArgumentType.getString(c, "model");
                                    boolean ok = AiConfig.get().modelPrices.remove(m) != null;
                                    if (ok) AiConfig.save();
                                    say(c, ok ? "가격표에서 지웠습니다: " + m : "가격표에 없는 모델입니다.");
                                    return ok ? 1 : 0;
                                }))
                                .then(Commands.argument("input", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0))
                                        .then(Commands.argument("cached", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0))
                                                .then(Commands.argument("output", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0)).executes(c -> {
                                                    String m = StringArgumentType.getString(c, "model");
                                                    double[] pr = {com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "input"),
                                                            com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "cached"),
                                                            com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "output")};
                                                    AiConfig.get().modelPrices.put(m, pr);
                                                    AiConfig.save();
                                                    say(c, String.format("%s: 입력 $%.3f / 캐시 $%.3f / 출력 $%.3f (1M 토큰당) 저장", m, pr[0], pr[1], pr[2]));
                                                    return 1;
                                                }))))))
                .then(Commands.literal("memory")
                        .then(Commands.literal("list").then(Commands.argument("player", StringArgumentType.word()).executes(c -> memList(c, StringArgumentType.getString(c, "player")))))
                        .then(Commands.literal("share").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
                            WishEngine e = eng(c);
                            if (e == null) return 0;
                            var other = e.state().findPlayer(StringArgumentType.getString(c, "player"));
                            if (other == null) { c.getSource().sendFailure(Component.literal("모르는 플레이어")); return 0; }
                            var res = e.memory().share(StringArgumentType.getString(c, "id"), other.uuid, null);
                            say(c, "공유: " + res);
                            return res == dev.newyear.countdown.ai.memory.MemoryStore.ShareResult.OK ? 1 : 0;
                        }))))
                        .then(Commands.literal("unshare").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                            WishEngine e = eng(c);
                            if (e == null) return 0;
                            int n = e.memory().unshare(StringArgumentType.getString(c, "id"), null, null);
                            say(c, "공유 " + n + "개를 거뒀습니다.");
                            return n;
                        })))
                        .then(Commands.literal("world").executes(c -> memList(c, "world")))
                        .then(Commands.literal("forget").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                            WishEngine e = eng(c);
                            if (e == null) return 0;
                            boolean ok = e.memory().forget(StringArgumentType.getString(c, "id"));
                            say(c, ok ? "기억을 지웠습니다." : "그런 기억이 없습니다.");
                            return ok ? 1 : 0;
                        })))
                        .then(Commands.literal("clear").then(Commands.argument("target", StringArgumentType.word()).executes(c -> {
                            WishEngine e = eng(c);
                            if (e == null) return 0;
                            String t = StringArgumentType.getString(c, "target");
                            int n;
                            if (t.equals("all")) n = e.memory().clearAll();
                            else if (t.equals("world")) {
                                List<String> ids = new ArrayList<>();
                                for (MemoryRecord r : e.memory().all()) if (r.scope == MemoryRecord.Scope.WORLD) ids.add(r.id);
                                ids.forEach(e.memory()::forget);
                                n = ids.size();
                            } else {
                                var prof = e.state().findPlayer(t);
                                if (prof == null) { c.getSource().sendFailure(Component.literal("모르는 플레이어")); return 0; }
                                n = e.memory().clearPlayer(prof.uuid);
                            }
                            say(c, "기억 " + n + "개를 지웠습니다.");
                            return 1;
                        }))))
                .then(Commands.literal("autonomy").then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("situation", StringArgumentType.greedyString()).executes(c -> {
                            WishEngine e = eng(c);
                            if (e == null) return 0;
                            ServerPlayer p = e.playerByName(StringArgumentType.getString(c, "player"));
                            if (p == null) { c.getSource().sendFailure(Component.literal("그 플레이어는 접속해 있지 않습니다")); return 0; }
                            boolean ok = e.autonomy().forceTrigger(p, "[admin-requested] " + StringArgumentType.getString(c, "situation"));
                            say(c, ok ? "자율 판단을 요청했습니다." : "AI 를 쓸 수 없습니다 (꺼짐/키 없음).");
                            return ok ? 1 : 0;
                        }))))
                .then(Commands.literal("run").then(Commands.argument("code", StringArgumentType.greedyString()).executes(AiCommands::runCode)))
                .then(Commands.literal("validate").then(Commands.argument("code", StringArgumentType.greedyString()).executes(AiCommands::validateCode)))
                .then(Commands.literal("selftest").executes(c -> {
                    WishEngine e = eng(c);
                    if (e == null) return 0;
                    SelfTest.run(e, c.getSource());
                    return 1;
                }))
                .then(Commands.literal("save").executes(c -> {
                    WishEngine e = eng(c);
                    if (e == null) return 0;
                    e.scripts().saveAllStates();
                    e.writeNow();
                    say(c, "저장했습니다: " + e.storage().dir());
                    return 1;
                })));
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        AiConfig cfg = AiConfig.get();
        UsageTracker.Snapshot s = e.usage().snapshot();
        int live = e.scripts().live().size();
        say(c, "§6[WishAI] §f" + (e.state().killSwitch ? "§c킬 스위치 ON" : cfg.enabled ? "§a켜짐" : "§7꺼짐(설정)")
                + " §fmodel=" + cfg.model + " effort=" + cfg.reasoningEffort + " key=" + (AiConfig.hasApiKey() ? "§a있음" : "§c없음"));
        say(c, String.format("오늘: 턴 %d, 요청 %d, 오류 %d, 입력 %,d(캐시 %,d) / 출력 %,d(추론 %,d) 토큰, 비용 $%.4f / 예산 $%.2f",
                s.today.turns, s.today.requests, s.today.errors, s.today.inputTokens, s.today.cachedTokens, s.today.outputTokens, s.today.reasoningTokens,
                s.today.costUsd, cfg.dailyBudgetUsd));
        say(c, String.format("누적: 턴 %d, 비용 $%.4f | 최근 평균 응답 %.1fs, 오류율 %.0f%% | 진행 %d, 대기 %d",
                s.lifetime.turns, s.lifetime.costUsd, s.avgLatencyMs / 1000.0, s.errorRate * 100, e.agent().active(), e.agent().queued()));
        say(c, "스크립트 실행 중 " + live + " / 기록 " + e.state().scripts.size() + ", 소원 " + e.state().wishes.size() + ", 퀘스트 " + e.state().quests.size()
                + ", 기억 " + e.memory().all().size() + ", 대기 UI " + e.ui().pending().size() + ", 버려진 이벤트 " + e.scripts().droppedEvents());
        say(c, "의미 검색: " + (!cfg.embeddingsEnabled ? "§7꺼짐" : e.semanticAvailable() ? "§a켜짐" : "§e대기(키/예산)") + "§f " + cfg.embeddingModel
                + " 벡터 " + e.semantic().size() + "/" + e.memory().all().size() + ", 임베딩 토큰 오늘 " + s.today.embeddingTokens
                + (e.semantic().lastError.isEmpty() ? "" : " §c최근 오류: " + e.semantic().lastError));
        if (!s.today.errorsByKind.isEmpty()) say(c, "오류 종류: " + s.today.errorsByKind);
        return 1;
    }

    private static int cooldownInfo(CommandContext<CommandSourceStack> c) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        int secs = AiConfig.get().wishCooldownSeconds;
        say(c, "§6소원 쿨타임§f: " + (secs == 0 ? "꺼짐" : secs + "초") + " §7(바꾸기: /wishai cooldown set <초>, 초기화: /wishai cooldown reset <플레이어|all>)");
        long now = System.currentTimeMillis();
        for (var prof : e.state().players.values()) {
            long left = prof.wishCooldownLeft(now, secs);
            if (left > 0) say(c, " §e" + prof.name + "§f: " + (left + 999) / 1000 + "초 남음");
        }
        return 1;
    }

    private static int prices(CommandContext<CommandSourceStack> c) {
        WishEngine e = eng(c);
        AiConfig cfg = AiConfig.get();
        say(c, String.format("§6가격표§f (1M 토큰당 USD) 기본: 입력 %.3f / 캐시 %.3f / 출력 %.3f, 임베딩 %.3f",
                cfg.priceInputPerMTok, cfg.priceCachedInputPerMTok, cfg.priceOutputPerMTok, cfg.priceEmbeddingPerMTok));
        for (var en : cfg.modelPrices.entrySet()) {
            say(c, String.format(" §e%s§f: %.3f / %.3f / %.3f", en.getKey(), en.getValue()[0], en.getValue()[1], en.getValue()[2]));
        }
        if (e != null) {
            var today = e.usage().today();
            if (!today.costByModel.isEmpty()) {
                StringBuilder sb = new StringBuilder("오늘 모델별: ");
                today.costByModel.forEach((m, v) -> sb.append(m).append(String.format(" $%.4f (%,d tok)  ", v, today.tokensByModel.getOrDefault(m, 0L))));
                say(c, sb.toString());
            }
        }
        say(c, "§7설정: /wishai price <모델> <입력> <캐시> <출력> · 지우기: /wishai price <모델> remove");
        return 1;
    }

    private static int listScripts(CommandContext<CommandSourceStack> c, String player, boolean all) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        String uuid = null;
        if (player != null) {
            var prof = e.state().findPlayer(player);
            if (prof == null) { c.getSource().sendFailure(Component.literal("모르는 플레이어")); return 0; }
            uuid = prof.uuid;
        }
        int n = 0;
        for (ScriptRecord r : e.state().scripts.values()) {
            if (!all && !r.state.live() && r.state != ScriptRecord.State.PENDING_APPROVAL && r.state != ScriptRecord.State.SUSPENDED) continue;
            if (uuid != null && !uuid.equals(r.owner)) continue;
            n++;
            String color = r.state.live() ? "§a" : r.state == ScriptRecord.State.PENDING_APPROVAL ? "§e" : "§7";
            say(c, color + r.id + " §f" + r.name + " §7(" + (r.ownerName == null ? "world" : r.ownerName) + ", " + r.state
                    + ", v" + r.version + ", events " + r.subscriptions + (r.errors > 0 ? ", §cerr " + r.errors : "") + "§7)");
        }
        if (n == 0) say(c, "스크립트가 없습니다.");
        return n;
    }

    private static int scriptInfo(CommandContext<CommandSourceStack> c, boolean code) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        ScriptRecord r = e.state().scripts.get(StringArgumentType.getString(c, "id"));
        if (r == null) { c.getSource().sendFailure(Component.literal("그런 스크립트가 없습니다")); return 0; }
        if (code) {
            say(c, "§6" + r.id + " v" + r.version + " code:");
            for (String line : r.code.split("\n")) say(c, "§7" + line);
            return 1;
        }
        say(c, "§6" + r.id + " §f" + r.name + " — " + r.description);
        say(c, "state=" + r.state + " (" + r.stateReason + "), owner=" + r.ownerName + ", wish=" + r.wishId + ", request=" + r.requestId + ", lasting=" + r.lasting);
        say(c, "dim=" + r.dimension + ", duration=" + (r.durationTicks < 0 ? "∞" : (r.durationTicks / 20) + "s") + ", left="
                + (r.durationTicks > 0 ? Math.max(0, (r.expiresTick - e.tick()) / 20) + "s" : "-") + ", runs=" + r.runs + ", steps=" + r.steps);
        say(c, "events=" + r.subscriptions + ", api=" + r.apiCalls);
        say(c, "entities=" + r.entities.size() + " (kept " + r.keptEntities.size() + "), blocks=" + r.blocks.size() + " (conflicts " + r.blockConflicts.size()
                + ", planned " + r.preview.size() + "), timers=" + r.timers.size() + ", behaviours=" + r.behaviours.size() + ", goals=" + r.goals.size()
                + ", models=" + r.models.size() + ", versions=" + r.history.size() + ", heap≈" + dev.newyear.countdown.ai.script.lang.HeapMeter.human(r.heapBytes));
        say(c, "state json=" + (r.stateJson.toString().length() > 300 ? r.stateJson.toString().substring(0, 300) + "…" : r.stateJson));
        if (!r.lastError.isEmpty()) say(c, "§clast error: " + r.lastError);
        return 1;
    }

    /** 스크립트 동작은 관리자 화면과 같은 코드(AdminService)를 쓴다. */
    private static int scriptAction(CommandContext<CommandSourceStack> c, String action) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        String id = StringArgumentType.getString(c, "id");
        if (e.state().scripts.get(id) == null) { c.getSource().sendFailure(Component.literal("그런 스크립트가 없습니다")); return 0; }
        var out = e.admin().scriptAction(id, action, c.getSource().getTextName(), c.getSource().getPlayer());
        say(c, out.has("text") ? out.get("text").getAsString() : id + ": " + action);
        return 1;
    }

    private static int approve(CommandContext<CommandSourceStack> c, boolean yes) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        String id = StringArgumentType.getString(c, "id");
        ScriptRecord r = e.state().scripts.get(id);
        if (r == null || r.state != ScriptRecord.State.PENDING_APPROVAL) { c.getSource().sendFailure(Component.literal("승인 대기 중인 스크립트가 아닙니다")); return 0; }
        say(c, (yes ? "§a" : "") + id + ": " + e.admin().approve(r, yes, c.getSource().getTextName()));
        return 1;
    }

    private static int listWishes(CommandContext<CommandSourceStack> c, String player) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        List<Wish> list = new ArrayList<>();
        for (Wish w : e.state().wishes.values()) if (player == null ? !w.state.terminal() : player.equalsIgnoreCase(w.playerName)) list.add(w);
        list.sort((a, b) -> Long.compare(b.created, a.created));
        for (int i = 0; i < Math.min(15, list.size()); i++) {
            Wish w = list.get(i);
            say(c, "§e" + w.id + " §f" + w.playerName + ": \"" + w.text + "\" §7[" + w.state + ", turns " + w.turns + ", scripts " + w.scriptIds.size() + ", quests " + w.questIds.size() + "]");
        }
        if (list.isEmpty()) say(c, player == null ? "열린 소원이 없습니다." : "소원이 없습니다.");
        return list.size();
    }

    private static int wishInfo(CommandContext<CommandSourceStack> c) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        Wish w = e.state().wishes.get(StringArgumentType.getString(c, "id"));
        if (w == null) { c.getSource().sendFailure(Component.literal("그런 소원이 없습니다")); return 0; }
        say(c, "§6" + w.id + " §f" + w.playerName + ": \"" + w.text + "\" [" + w.state + "] origin=" + w.origin);
        say(c, "scripts=" + w.scriptIds + " quests=" + w.questIds + " memories=" + w.memoryIds.size() + " consents=" + w.consents.size());
        for (Wish.Transition t : w.history) say(c, " §7" + t.from + " → " + t.to + ": " + t.reason);
        int from = Math.max(0, w.conversation.size() - 8);
        for (int i = from; i < w.conversation.size(); i++) say(c, " §8" + w.conversation.get(i).role() + ": §7" + w.conversation.get(i).text());
        return 1;
    }

    private static int memList(CommandContext<CommandSourceStack> c, String who) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        List<MemoryRecord> list = new ArrayList<>();
        if (who.equals("world")) {
            for (MemoryRecord r : e.memory().all()) if (r.scope == MemoryRecord.Scope.WORLD) list.add(r);
        } else {
            var prof = e.state().findPlayer(who);
            if (prof == null) { c.getSource().sendFailure(Component.literal("모르는 플레이어")); return 0; }
            list.addAll(e.memory().ofPlayer(prof.uuid));
        }
        list.sort((a, b) -> Long.compare(b.created, a.created));
        say(c, "§6기억 " + list.size() + "개 (최근 15)");
        for (int i = 0; i < Math.min(15, list.size()); i++) {
            MemoryRecord r = list.get(i);
            say(c, "§e" + r.id + " §7[" + r.kind + ", " + r.importance + (r.archived ? ", archived" : "") + (r.thread == null ? "" : ", " + r.thread)
                    + (r.shared() ? ", shared→" + dev.newyear.countdown.ai.agent.ContextBuilder.sharedNames(e, r) : "")
                    + (e.semantic().vectorOf(r) != null ? ", ∿" : "") + "] §f" + r.text);
        }
        return list.size();
    }

    private static int runCode(CommandContext<CommandSourceStack> c) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        ServerPlayer p = c.getSource().getPlayer();
        ScriptRecord r = new ScriptRecord();
        r.id = "s-admin-" + UUID.randomUUID().toString().substring(0, 6);
        r.name = "admin run";
        r.code = StringArgumentType.getString(c, "code");
        r.created = System.currentTimeMillis();
        r.durationTicks = 20 * 120;
        r.keepEntitiesDefault = true;
        if (p != null) {
            r.owner = p.getUUID().toString();
            r.ownerName = p.getGameProfile().name();
            r.dimension = dev.newyear.countdown.ai.script.api.McUtil.dimId(p.level());
            r.anchor = new double[]{p.getX(), p.getY(), p.getZ()};
        }
        ScriptValidator.Report rep = ScriptValidator.validate(r, List.of(), 0);
        if (!rep.ok) {
            for (String err : rep.errors) c.getSource().sendFailure(Component.literal(err));
            return 0;
        }
        e.state().scripts.put(r.id, r);
        var res = e.scripts().activate(r, false);
        var inst = e.scripts().instance(r.id);
        if (res.log() != null) for (String l : res.log()) say(c, "§7> " + l);
        if (!res.ok()) { c.getSource().sendFailure(Component.literal(res.error())); return 0; }
        if (inst != null && inst.listenerCount() == 0 && inst.activeTimers() == 0 && !e.ui().hasPendingForScript(r.id)) e.scripts().stop(r.id, ScriptRecord.State.STOPPED, "admin one-shot done", false);
        say(c, "§a실행됨 " + r.id);
        return 1;
    }

    private static int validateCode(CommandContext<CommandSourceStack> c) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        ServerPlayer p = c.getSource().getPlayer();
        ScriptRecord r = new ScriptRecord();
        r.code = StringArgumentType.getString(c, "code");
        if (p != null) {
            r.owner = p.getUUID().toString();
            r.ownerName = p.getGameProfile().name();
            r.anchor = new double[]{p.getX(), p.getY(), p.getZ()};
        }
        say(c, ScriptValidator.validate(r, List.of(), 200).toJson().toString());
        return 1;
    }

    @SuppressWarnings("unused")
    private static int unusedInt(IntegerArgumentType t) {
        return 0;
    }
}
