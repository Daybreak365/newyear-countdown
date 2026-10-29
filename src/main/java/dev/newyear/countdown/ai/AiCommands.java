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

    // ---------------------------------------------------------------- /wishai

    private static void registerAdmin(CommandDispatcher<CommandSourceStack> d) {
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
                        .then(Commands.literal("rollback").executes(c -> scriptAction(c, "rollback")))
                        .then(Commands.literal("suspend").executes(c -> scriptAction(c, "suspend")))
                        .then(Commands.literal("resume").executes(c -> scriptAction(c, "resume")))
                        .then(Commands.literal("delete").executes(c -> scriptAction(c, "delete")))
                        .then(Commands.literal("revert").executes(c -> scriptAction(c, "revert")))))
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
                .then(Commands.literal("memory")
                        .then(Commands.literal("list").then(Commands.argument("player", StringArgumentType.word()).executes(c -> memList(c, StringArgumentType.getString(c, "player")))))
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
        if (!s.today.errorsByKind.isEmpty()) say(c, "오류 종류: " + s.today.errorsByKind);
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
        say(c, "entities=" + r.entities.size() + " (kept " + r.keptEntities.size() + "), blocks=" + r.blocks.size() + ", timers=" + r.timers.size()
                + ", behaviours=" + r.behaviours.size() + ", versions=" + r.history.size());
        say(c, "state json=" + (r.stateJson.toString().length() > 300 ? r.stateJson.toString().substring(0, 300) + "…" : r.stateJson));
        if (!r.lastError.isEmpty()) say(c, "§clast error: " + r.lastError);
        return 1;
    }

    private static int scriptAction(CommandContext<CommandSourceStack> c, String action) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        String id = StringArgumentType.getString(c, "id");
        ScriptRecord r = e.state().scripts.get(id);
        if (r == null) { c.getSource().sendFailure(Component.literal("그런 스크립트가 없습니다")); return 0; }
        String by = "admin " + c.getSource().getTextName();
        switch (action) {
            case "stop" -> e.scripts().stop(id, ScriptRecord.State.STOPPED, "stopped by " + by, false);
            case "rollback" -> e.scripts().stop(id, ScriptRecord.State.STOPPED, "rolled back by " + by, true);
            case "suspend" -> {
                var inst = e.scripts().instance(id);
                if (inst != null) e.scripts().saveState(inst);
                e.scripts().live().remove(id);
                r.state = ScriptRecord.State.SUSPENDED;
                r.stateReason = "suspended by " + by;
            }
            case "resume" -> {
                if (r.state.ended()) r.state = ScriptRecord.State.SUSPENDED;
                var res = e.scripts().activate(r, true);
                say(c, res.ok() ? "재개했습니다." : "§c실패: " + res.error());
                return res.ok() ? 1 : 0;
            }
            case "revert" -> {
                if (r.history.isEmpty()) { c.getSource().sendFailure(Component.literal("이전 버전이 없습니다")); return 0; }
                ScriptRecord.Version v = r.history.remove(r.history.size() - 1);
                r.code = v.code;
                r.version = v.version;
                if (r.state.live() || r.state == ScriptRecord.State.SUSPENDED) {
                    var res = e.scripts().activate(r, true);
                    say(c, res.ok() ? "v" + v.version + " 으로 되돌렸습니다." : "§c실패: " + res.error());
                }
            }
            case "delete" -> {
                if (r.state.live()) e.scripts().stop(id, ScriptRecord.State.STOPPED, "deleted by " + by, true);
                e.state().scripts.remove(id);
            }
            default -> { }
        }
        say(c, id + ": " + action + " 완료");
        return 1;
    }

    private static int approve(CommandContext<CommandSourceStack> c, boolean yes) {
        WishEngine e = eng(c);
        if (e == null) return 0;
        String id = StringArgumentType.getString(c, "id");
        ScriptRecord r = e.state().scripts.get(id);
        if (r == null || r.state != ScriptRecord.State.PENDING_APPROVAL) { c.getSource().sendFailure(Component.literal("승인 대기 중인 스크립트가 아닙니다")); return 0; }
        Wish w = r.wishId == null ? null : e.state().wishes.get(r.wishId);
        ServerPlayer owner = r.owner == null ? null : e.player(UUID.fromString(r.owner));
        if (yes) {
            r.approvedBy = c.getSource().getTextName();
            var res = e.scripts().activate(r, false);
            if (w != null && !w.state.terminal()) e.wishes().transition(w, Wish.State.ACTIVE, "approved by admin");
            if (owner != null) e.ui().notify(owner, "별들의 허락", "'" + (r.abilityLabel.isEmpty() ? r.name : r.abilityLabel) + "'이(가) 깨어났습니다.", "success", 6);
            say(c, res.ok() ? "§a승인 및 실행: " + id : "§c승인했지만 실행 실패: " + res.error());
        } else {
            r.state = ScriptRecord.State.STOPPED;
            r.stateReason = "denied by " + c.getSource().getTextName();
            if (w != null && !w.state.terminal()) e.wishes().transition(w, Wish.State.REJECTED, "admin denied the script");
            if (owner != null) e.ui().notify(owner, "별들의 거절", "이 소원은 하늘이 허락하지 않았습니다.", "warning", 6);
            say(c, "거부했습니다: " + id);
        }
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
            say(c, "§e" + r.id + " §7[" + r.kind + ", " + r.importance + (r.archived ? ", archived" : "") + (r.thread == null ? "" : ", " + r.thread) + "] §f" + r.text);
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
        if (inst != null && inst.listenerCount() == 0 && inst.activeTimers() == 0) e.scripts().stop(r.id, ScriptRecord.State.STOPPED, "admin one-shot done", false);
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
