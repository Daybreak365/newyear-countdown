package dev.newyear.countdown.ai.wish;

import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.AiState;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.agent.TurnRequest;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.ScriptManager;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.ui.UiService;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 소원 생명주기. 플레이어 입력 → (AI 턴) → 상태 전이 → 종료 정리.
 * 소원 상태와 스크립트 상태는 분리되어 있다: lasting 스크립트는 소원이 끝나도 남는다.
 */
public final class WishManager {
    private final WishEngine eng;

    public WishManager(WishEngine eng) {
        this.eng = eng;
    }

    public static String sanitize(String raw, int max) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c < 32 || c == '§' || c == 127) continue;
            sb.append(c);
        }
        String s = sb.toString().strip().replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max) : s;
    }

    public Wish openWishOf(ServerPlayer p) {
        AiState.PlayerProfile prof = eng.state().players.get(p.getUUID().toString());
        if (prof == null || prof.openWishId == null) return null;
        Wish w = eng.state().wishes.get(prof.openWishId);
        if (w == null || w.state.terminal()) {
            prof.openWishId = null;
            return null;
        }
        return w;
    }

    /**
     * 플레이어가 무언가 말했다 (대화 화면, /wish, 연등).
     * @param wishId 이어 갈 소원 (빈 문자열이면 열린 소원이 있으면 이어서, 없으면 새 소원)
     */
    public void playerMessage(ServerPlayer p, String wishId, String raw, String origin) {
        AiConfig cfg = AiConfig.get();
        String text = sanitize(raw == null ? "" : raw, cfg.maxMessageLength);
        if (text.isEmpty()) return;
        String uuid = p.getUUID().toString();
        AiState.PlayerProfile prof = eng.state().profile(uuid, p.getGameProfile().name());
        // 한도·예산·키 확인을 소원 기록을 만들기 전에 한다 (거절된 소원이 '분석 중'으로 남지 않게)
        if (eng.agent().busy(prof.openWishId == null ? "" : prof.openWishId) == false) {
            String refusal = eng.agent().gate(TurnRequest.player(null, p.getUUID(), text), p);
            if (refusal != null) {
                eng.ui().spiritLine(p, prof.openWishId, refusal, "sad");
                return;
            }
        }

        Wish w = null;
        if (wishId != null && !wishId.isEmpty()) {
            Wish c = eng.state().wishes.get(wishId);
            if (c != null && uuid.equals(c.player) && c.state.conversational()) w = c;
        }
        // 대화 중인 소원만 이어 간다. 진행 중(퀘스트·승인 대기)인 소원이 있어도 새 말은 새 소원으로 시작한다
        // (진행 중인 소원과 퀘스트는 컨텍스트로 AI 에게 함께 전달된다).
        if (w == null && !"lantern".equals(origin)) {
            Wish open = openWishOf(p);
            if (open != null && open.state.conversational()) w = open;
        }
        boolean fresh = w == null;
        if (fresh) {
            w = new Wish();
            w.id = "w-" + UUID.randomUUID().toString().substring(0, 8);
            w.player = uuid;
            w.playerName = p.getGameProfile().name();
            w.text = text;
            w.origin = origin;
            w.created = System.currentTimeMillis();
            eng.state().wishes.put(w.id, w);
            prof.wishes++;
            // 기억: 소원을 빌었다는 사실 (중요도 낮음 — 결과가 나오면 결과 기억이 더 중요)
            MemoryRecord m = new MemoryRecord();
            m.kind = "wish";
            m.scope = MemoryRecord.Scope.PLAYER;
            m.owner = uuid;
            m.ownerName = w.playerName;
            m.wishId = w.id;
            m.text = w.playerName + "가 소원을 빌었다: \"" + text + "\"";
            m.importance = 3;
            eng.memory().add(m, System.currentTimeMillis());
            w.memoryIds.add(m.id);
        }
        prof.openWishId = w.id;
        w.addLine("player", text, System.currentTimeMillis());
        eng.ui().playerLine(p, w.id, text);
        if (fresh) {
            eng.ui().sendConversation(p, w);
            transition(w, Wish.State.ANALYZING, "new wish via " + origin);
        } else if (w.state == Wish.State.AWAITING_ACCEPTANCE && w.contract != null && !w.contract.accepted) {
            // 계약 카드가 떠 있는데 말로 대답하면 협상으로 본다
            eng.ui().cancelInteractionsOfWish(w.id);
            transition(w, Wish.State.NEGOTIATING, "player is negotiating");
        }
        eng.agent().submit(TurnRequest.player(w.id, p.getUUID(), text));
    }

    /** AI 가 만든 UI(선택지·입력·계약)의 응답을 다음 AI 턴으로 넘긴다. */
    public UiService.Responder aiResponder(String wishId, String kind, String prompt) {
        return (player, index, value, timedOut) -> {
            Wish w = eng.state().wishes.get(wishId);
            if (w == null || w.state.terminal()) return;
            String msg;
            if (timedOut) msg = "[UI " + kind + " timed out without an answer] prompt: " + prompt;
            else msg = "[UI " + kind + " answer] prompt: " + prompt + " → player answered: \"" + value + "\"";
            if (kind.equals("contract")) {
                if (timedOut || w.contract == null) return;
                if (index == 0) {
                    w.contract.accepted = true;
                    transition(w, Wish.State.PREPARING, "contract accepted");
                    msg = "[CONTRACT ACCEPTED] The player accepted your contract (terms: " + w.contract.terms + "; reward: " + w.contract.reward
                            + "). Now make it real: create the quest UI and a script that tracks the condition and gives the reward when met.";
                } else {
                    w.contract.declined = true;
                    transition(w, Wish.State.NEGOTIATING, "contract declined");
                    msg = "[CONTRACT DECLINED] The player declined the contract. Negotiate, offer a different deal, or let it go.";
                }
            }
            UUID pid = player != null ? player.getUUID() : UUID.fromString(w.player);
            eng.agent().submit(new TurnRequest(wishId, pid, TurnRequest.Kind.UI, msg, null));
        };
    }

    // ---------------------------------------------------------------- 상태 전이

    public void transition(Wish w, Wish.State to, String reason) {
        if (w.state == to) return;
        if (w.state.terminal() && to != w.state) {
            // 끝난 소원은 다시 열지 않는다 (새 소원으로 이어 간다)
            return;
        }
        Wish.State from = w.state;
        w.history.add(new Wish.Transition(from, to, reason, System.currentTimeMillis()));
        while (w.history.size() > 40) w.history.remove(0);
        w.state = to;
        w.updated = System.currentTimeMillis();
        ServerPlayer p = online(w);
        if (p != null) eng.ui().wishState(p, w);
        eng.scripts().emit("wish_state", new JsObject().put("wish", w.id).put("state", to.name()), ScriptManager.Ctx.NONE);
        if (to.terminal()) onTerminal(w, from, reason);
    }

    private ServerPlayer online(Wish w) {
        if (w.player == null) return null;
        return eng.player(UUID.fromString(w.player));
    }

    private void onTerminal(Wish w, Wish.State from, String reason) {
        AiState.PlayerProfile prof = w.player == null ? null : eng.state().players.get(w.player);
        if (prof != null && w.id.equals(prof.openWishId)) prof.openWishId = null;
        eng.ui().cancelInteractionsOfWish(w.id);
        eng.agent().cancel(w.id, w.state == Wish.State.CANCELLED);
        // 소원을 위한 보조 스크립트는 정리, lasting 능력은 유지
        for (String sid : new ArrayList<>(w.scriptIds)) {
            ScriptRecord r = eng.state().scripts.get(sid);
            if (r == null || !r.state.live() && r.state != ScriptRecord.State.PENDING_APPROVAL) continue;
            if (w.state == Wish.State.CANCELLED || !r.lasting) {
                eng.scripts().requestStop(sid, "wish " + w.state.name().toLowerCase());
            }
        }
        for (String qid : w.questIds) {
            Quest q = eng.state().quests.get(qid);
            if (q != null && q.state == Quest.State.ACTIVE) {
                eng.quests().finish(qid, w.state == Wish.State.COMPLETED ? Quest.State.COMPLETED : Quest.State.CANCELLED, "소원이 " + stateKo(w.state));
            }
        }
        if (w.outcome == null || w.outcome.isEmpty()) w.outcome = reason == null ? "" : reason;
        // 기억: 결과
        MemoryRecord m = new MemoryRecord();
        m.kind = "outcome";
        m.scope = w.player == null ? MemoryRecord.Scope.WORLD : MemoryRecord.Scope.PLAYER;
        m.owner = w.player;
        m.ownerName = w.playerName;
        m.wishId = w.id;
        String sum = w.summary != null && !w.summary.isEmpty() ? w.summary : w.outcome;
        if ("autonomous".equals(w.origin)) {
            // 자율 사건은 소원이 아니다: 아무 일도 없었으면 기록하지 않는다
            if (w.conversation.isEmpty() && w.scriptIds.isEmpty() && w.questIds.isEmpty()) {
                compactConversation(w);
                return;
            }
            m.kind = "event";
            m.text = "새벽별이 스스로 일으킨 일" + (w.playerName == null ? "" : " (" + w.playerName + " 곁에서)") + ": " + clip(sum == null ? "" : sum, 260);
        } else {
            m.text = (w.playerName == null ? "세계" : w.playerName) + "의 소원 \"" + clip(w.text, 60) + "\" → " + stateKo(w.state)
                    + (sum == null || sum.isEmpty() ? "" : ": " + clip(sum, 240));
        }
        m.importance = w.state == Wish.State.COMPLETED ? 6 : w.state == Wish.State.CANCELLED ? 3 : 5;
        for (String mid : w.memoryIds) if (!m.links.contains(mid) && m.links.size() < 4) m.links.add(mid);
        eng.memory().add(m, System.currentTimeMillis());
        if (!w.memoryIds.contains(m.id)) w.memoryIds.add(m.id);
        // 오래된 대화는 요약만 남긴다
        compactConversation(w);
    }

    public static String stateKo(Wish.State s) {
        return switch (s) {
            case COMPLETED -> "이루어졌다";
            case FAILED -> "이루지 못했다";
            case REJECTED -> "거절되었다";
            case CANCELLED -> "취소되었다";
            default -> s.name().toLowerCase();
        };
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** 대화가 길면 앞부분을 짧은 요약 문자열로 접는다 (AI 호출 없이). */
    public static void compactConversation(Wish w) {
        if (w.conversation.size() <= 30) return;
        StringBuilder sb = new StringBuilder(w.conversationSummary == null ? "" : w.conversationSummary);
        int cut = w.conversation.size() - 20;
        for (int i = 0; i < cut; i++) {
            Wish.Line l = w.conversation.get(i);
            if (sb.length() > 1500) break;
            sb.append(l.role().equals("player") ? " P:" : " S:").append(clip(l.text(), 80));
        }
        w.conversationSummary = sb.toString().strip();
        w.conversation = new ArrayList<>(w.conversation.subList(cut, w.conversation.size()));
    }

    /**
     * 진행 중(ACTIVE)인 소원의 보조 스크립트·퀘스트가 모두 끝났으면 소원도 마무리한다.
     * (지속 능력 스크립트는 소원의 결과물이라 계속 돌아도 소원은 끝난 것으로 본다.)
     */
    public void checkWorkDone(String wishId) {
        Wish w = wishId == null ? null : eng.state().wishes.get(wishId);
        if (w == null || w.state != Wish.State.ACTIVE) return;
        if (eng.agent().busy(w.id)) return;
        for (String sid : w.scriptIds) {
            var r = eng.state().scripts.get(sid);
            if (r != null && !r.lasting && (r.state.live() || r.state == dev.newyear.countdown.ai.script.ScriptRecord.State.PENDING_APPROVAL)) return;
        }
        boolean anyCompleted = false, anyFailed = false;
        for (String qid : w.questIds) {
            Quest q = eng.state().quests.get(qid);
            if (q == null) continue;
            if (q.state == Quest.State.ACTIVE) return;
            if (q.state == Quest.State.COMPLETED) anyCompleted = true;
            if (q.state == Quest.State.FAILED) anyFailed = true;
        }
        for (UiService.Pending pd : eng.ui().pending().values()) if (w.id.equals(pd.wishId)) return;
        if (anyFailed && !anyCompleted) {
            w.outcome = w.outcome == null || w.outcome.isEmpty() ? "과제를 이루지 못했다" : w.outcome;
            transition(w, Wish.State.FAILED, "quest failed");
        } else {
            transition(w, Wish.State.COMPLETED, w.summary == null || w.summary.isEmpty() ? "work finished" : w.summary);
        }
    }

    public void completeFromScript(String wishId, String summary) {
        Wish w = eng.state().wishes.get(wishId);
        if (w == null || w.state.terminal()) return;
        if (summary != null && !summary.isEmpty()) w.summary = summary;
        transition(w, Wish.State.COMPLETED, summary == null || summary.isEmpty() ? "fulfilled" : summary);
        ServerPlayer p = online(w);
        if (p != null) eng.ui().cinematic(p, "소원 성취", clip(w.text, 40), "wish", 4);
    }

    public void failFromScript(String wishId, String reason) {
        Wish w = eng.state().wishes.get(wishId);
        if (w == null || w.state.terminal()) return;
        w.outcome = reason;
        transition(w, Wish.State.FAILED, reason);
    }

    public boolean cancel(String wishId, String reason) {
        Wish w = eng.state().wishes.get(wishId);
        if (w == null || w.state.terminal()) return false;
        w.outcome = reason;
        transition(w, Wish.State.CANCELLED, reason);
        return true;
    }

    /** 오래된 끝난 소원 정리 (플레이어당 최근 100개). */
    public void prune() {
        java.util.Map<String, java.util.List<Wish>> by = new java.util.HashMap<>();
        for (Wish w : eng.state().wishes.values()) if (w.state.terminal()) by.computeIfAbsent(String.valueOf(w.player), k -> new ArrayList<>()).add(w);
        for (var list : by.values()) {
            if (list.size() <= 100) continue;
            list.sort((a, b) -> Long.compare(a.created, b.created));
            for (int i = 0; i < list.size() - 100; i++) eng.state().wishes.remove(list.get(i).id);
        }
    }
}
