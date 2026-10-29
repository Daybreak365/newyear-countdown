package dev.newyear.countdown.client.ai;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 새벽별과의 대화 화면. 대화 기록, 응답 대기 애니메이션, 빠른 답, AI 가 띄운 선택지·계약·입력 카드를
 * 한 화면에서 통일된 모양으로 보여 준다. 탭: 대화 / 과제 / 기록.
 */
public class WishChatScreen extends Screen {
    private enum Tab { CHAT, QUESTS, HISTORY }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();
    private final long openedAt = System.currentTimeMillis();
    private Tab tab = Tab.CHAT;
    private EditBox input;
    private int scroll;          // 위로 올린 픽셀 수 (0 = 맨 아래)
    private int contentHeight;
    private int lastLineCount = -1;
    private int questScroll;
    private int x0, y0, x1, y1;

    public WishChatScreen() {
        super(Component.literal("소원 대화"));
    }

    @Override
    protected void init() {
        int w = Math.min(400, width - 16), h = Math.min(280, height - 16);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        input = new EditBox(font, x0 + 12, y1 - 46, w - 24 - 56, 14, Component.literal("소원"));
        input.setMaxLength(300);
        input.setBordered(false);
        input.setTextColor(WishUi.TEXT);
        addRenderableWidget(input);
        setInitialFocus(input);
        AiClientState.unread = 0;
        AiClientState.action("sync", "");
        updateHint();
    }

    private void updateHint() {
        AiClientState.Interaction it = AiClientState.firstInteraction();
        if (it != null && it.kind.equals("input")) {
            input.setHint(Component.literal(it.placeholder.isEmpty() ? "답을 적어라…" : it.placeholder).withColor(WishUi.TEXT_FAINT));
        } else if (AiClientState.wishId.isEmpty() || AiClientState.wishState.isEmpty() || isTerminal(AiClientState.wishState)) {
            input.setHint(Component.literal("새벽별에게 소원을 빌어 보세요…").withColor(WishUi.TEXT_FAINT));
        } else {
            input.setHint(Component.literal("대답하기…").withColor(WishUi.TEXT_FAINT));
        }
    }

    private static boolean isTerminal(String s) {
        return s.equals("COMPLETED") || s.equals("FAILED") || s.equals("REJECTED") || s.equals("CANCELLED");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        updateHint();
    }

    private void submit() {
        String t = input.getValue().strip();
        if (t.isEmpty()) return;
        AiClientState.Interaction it = AiClientState.firstInteraction();
        if (it != null && it.kind.equals("input")) {
            AiClientState.answer(it, 0, t);
            AiClientState.lines.add(new AiClientState.Line("player", t, ""));
        } else {
            if (isTerminal(AiClientState.wishState)) AiClientState.newWish();
            AiClientState.sendChat(t);
        }
        input.setValue("");
        scroll = 0;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if ((k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) && tab == Tab.CHAT) {
            submit();
            return true;
        }
        if (k == InputConstants.KEY_TAB) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Hit h : new ArrayList<>(hits)) {
                if (WishUi.inside(event.x(), event.y(), h.x, h.y, h.w, h.h)) {
                    h.action.run();
                    if (minecraft != null && minecraft.player != null) minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.3f);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (tab == Tab.CHAT) scroll = Math.max(0, scroll + (int) (sy * 18));
        else questScroll = Math.max(0, questScroll - (int) (sy * 18));
        return true;
    }

    // ---------------------------------------------------------------- 그리기

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        hits.clear();
        float open = WishUi.easeOut((System.currentTimeMillis() - openedAt) / 220f);
        float t = WishUi.now();
        int slide = Math.round((1 - open) * 10);
        int py0 = y0 + slide, py1 = y1 + slide;
        WishUi.panel(g, x0, py0, x1, py1, open);

        // 헤더
        int cx = (x0 + x1) / 2;
        WishUi.twinkle(g, x0 + 14, py0 + 12, t * 3, WishUi.STAR);
        g.text(font, "✦ " + AiClientState.spiritName, x0 + 22, py0 + 8, WishUi.textAlpha(WishUi.GOLD, open), false);
        if (!AiClientState.wishState.isEmpty()) {
            String st = WishUi.stateKo(AiClientState.wishState);
            int cw = font.width(st) + 8;
            WishUi.chip(g, font, st, x1 - 12 - cw, py0 + 7, WishUi.stateColor(AiClientState.wishState), open);
        }
        if (!AiClientState.enabled) g.text(font, "AI 꺼짐", x1 - 60, py0 + 20, WishUi.textAlpha(WishUi.DANGER, open), false);

        // 탭
        int tx = x0 + 12, ty = py0 + 21;
        int activeQuests = 0;
        for (AiClientState.Quest q : AiClientState.quests.values()) if (q.active()) activeQuests++;
        String[] names = {"대화", "과제" + (activeQuests > 0 ? " " + activeQuests : ""), "기록"};
        for (int i = 0; i < names.length; i++) {
            int w = font.width(names[i]) + 12;
            boolean sel = tab.ordinal() == i;
            boolean hover = WishUi.inside(mx, my, tx, ty, w, 12);
            g.fill(tx, ty, tx + w, ty + 12, WishUi.alpha(sel ? 0xE0303658 : hover ? 0xC0262A44 : 0x801A1D30, open));
            if (sel) g.fill(tx, ty + 11, tx + w, ty + 12, WishUi.alpha(WishUi.GOLD, open));
            g.text(font, names[i], tx + 6, ty + 2, WishUi.textAlpha(sel ? WishUi.GOLD : WishUi.TEXT_DIM, open), false);
            int idx = i;
            hits.add(new Hit(tx, ty, w, 12, () -> tab = Tab.values()[idx]));
            tx += w + 3;
        }
        g.fill(x0 + 8, ty + 12, x1 - 8, ty + 13, WishUi.alpha(WishUi.INNER_LINE, open));

        int top = ty + 17;
        switch (tab) {
            case CHAT -> drawChat(g, mx, my, top, py1, open, t);
            case QUESTS -> drawQuests(g, mx, my, top, py1, open);
            case HISTORY -> drawHistory(g, top, py1, open);
        }
        input.visible = tab == Tab.CHAT;
        super.extractRenderState(g, mx, my, delta);
    }

    private void drawChat(GuiGraphicsExtractor g, int mx, int my, int top, int bottom, float open, float t) {
        int inputY = bottom - 48;
        // 상호작용 카드 높이 먼저 계산
        AiClientState.Interaction it = AiClientState.firstInteraction();
        int cardH = it == null ? 0 : interactionHeight(it);
        int quickH = AiClientState.quick.isEmpty() || it != null ? 0 : 14;
        int areaBottom = inputY - 6 - cardH - quickH;

        // 대화 기록 (아래에서 위로 쌓는다)
        int maxBubble = (x1 - x0) - 70;
        List<int[]> layout = new ArrayList<>();
        List<List<FormattedCharSequence>> wrapped = new ArrayList<>();
        int total = 0;
        for (AiClientState.Line l : AiClientState.lines) {
            List<FormattedCharSequence> ws = WishUi.wrap(font, l.text, maxBubble);
            wrapped.add(ws);
            int h = ws.size() * 10 + 8;
            layout.add(new int[]{h});
            total += h + 5;
        }
        if (AiClientState.thinking) total += 22;
        contentHeight = total;
        if (lastLineCount != AiClientState.lines.size()) {
            lastLineCount = AiClientState.lines.size();
            scroll = 0;
        }
        int viewH = areaBottom - top;
        scroll = Math.min(scroll, Math.max(0, contentHeight - viewH));
        g.enableScissor(x0 + 4, top, x1 - 4, areaBottom);
        int y = areaBottom - contentHeight + scroll;
        if (AiClientState.lines.isEmpty() && !AiClientState.thinking) {
            g.centeredText(font, "무엇을 바라느냐?", (x0 + x1) / 2, top + viewH / 2 - 10, WishUi.textAlpha(WishUi.TEXT_DIM, open));
            g.centeredText(font, "아래에 소원을 적고 Enter — 들어줄지는 내가 정한다.", (x0 + x1) / 2, top + viewH / 2 + 4, WishUi.textAlpha(WishUi.TEXT_FAINT, open));
        }
        for (int i = 0; i < AiClientState.lines.size(); i++) {
            AiClientState.Line l = AiClientState.lines.get(i);
            int h = layout.get(i)[0];
            List<FormattedCharSequence> ws = wrapped.get(i);
            int w = 0;
            for (FormattedCharSequence s : ws) w = Math.max(w, font.width(s));
            w += 12;
            float age = WishUi.easeOut((System.currentTimeMillis() - l.at) / 260f);
            boolean spirit = l.role.equals("spirit");
            int bx = spirit ? x0 + 12 + Math.round((1 - age) * -6) : x1 - 12 - w + Math.round((1 - age) * 6);
            if (y + h > top - 20 && y < areaBottom + 20) {
                int bg = spirit ? WishUi.SPIRIT_BUBBLE : WishUi.PLAYER_BUBBLE;
                g.fill(bx + 1, y, bx + w - 1, y + h, WishUi.alpha(bg, open * age));
                g.fill(bx, y + 1, bx + 1, y + h - 1, WishUi.alpha(bg, open * age));
                g.fill(bx + w - 1, y + 1, bx + w, y + h - 1, WishUi.alpha(bg, open * age));
                g.fill(spirit ? bx : bx + w - 2, y + 2, spirit ? bx + 2 : bx + w, y + h - 2, WishUi.alpha(spirit ? WishUi.GOLD : WishUi.LANTERN, open * age * 0.8f));
                int ly = y + 4;
                for (FormattedCharSequence s : ws) {
                    g.text(font, s, bx + 6, ly, WishUi.textAlpha(spirit ? WishUi.TEXT : 0xFFF6E2CC, open * age), false);
                    ly += 10;
                }
            }
            y += h + 5;
        }
        if (AiClientState.thinking) {
            int bx = x0 + 12;
            String label = AiClientState.thinkingLabel.isEmpty() ? "별빛을 읽는 중" : AiClientState.thinkingLabel;
            int w = font.width(label) + 44;
            g.fill(bx, y, bx + w, y + 16, WishUi.alpha(WishUi.SPIRIT_BUBBLE, open));
            for (int k = 0; k < 3; k++) WishUi.twinkle(g, bx + 8 + k * 7, y + 8, t * 5 - k * 0.9f, WishUi.STAR);
            g.text(font, label, bx + 32, y + 4, WishUi.textAlpha(WishUi.TEXT_DIM, open), false);
            long secs = (System.currentTimeMillis() - AiClientState.thinkingSince) / 1000;
            if (secs > 4) g.text(font, secs + "s", bx + w + 4, y + 4, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
        }
        g.disableScissor();
        if (scroll > 0) g.centeredText(font, "▼ 최근 대화로", (x0 + x1) / 2, areaBottom - 10, WishUi.textAlpha(WishUi.TEXT_FAINT, open));

        // 빠른 답
        int qy = areaBottom + 2;
        if (quickH > 0) {
            int qx = x0 + 12;
            for (String q : AiClientState.quick) {
                int w = font.width(q) + 10;
                if (qx + w > x1 - 12) break;
                boolean hover = WishUi.inside(mx, my, qx, qy, w, 12);
                g.fill(qx, qy, qx + w, qy + 12, WishUi.alpha(hover ? 0xE0343A60 : 0xC0242844, open));
                g.outline(qx, qy, w, 12, WishUi.alpha(WishUi.EDGE_GOLD_DIM, open));
                g.text(font, q, qx + 5, qy + 2, WishUi.textAlpha(hover ? WishUi.GOLD : WishUi.TEXT, open), false);
                hits.add(new Hit(qx, qy, w, 12, () -> {
                    AiClientState.sendChat(q);
                    scroll = 0;
                }));
                qx += w + 4;
            }
        }

        // 상호작용 카드
        if (it != null) drawInteraction(g, it, mx, my, inputY - 4 - cardH, cardH, open);

        // 입력 줄
        int fieldW = (x1 - x0) - 24 - 56;
        g.fill(x0 + 10, inputY - 3, x0 + 12 + fieldW + 2, inputY + 13, WishUi.alpha(0xE00C0F1E, open));
        g.outline(x0 + 10, inputY - 3, fieldW + 4, 16, WishUi.alpha(input.isFocused() ? WishUi.EDGE_GOLD : WishUi.EDGE_GOLD_DIM, open));
        input.setX(x0 + 14);
        input.setY(inputY + 1);
        input.setWidth(fieldW - 4);
        boolean answering = it != null && it.kind.equals("input");
        int sendX = x1 - 12 - 50;
        WishUi.button(g, font, answering ? "답하기" : "보내기", sendX, inputY - 3, 50, 16, mx, my, !input.getValue().isBlank(), WishUi.GOLD, open);
        hits.add(new Hit(sendX, inputY - 3, 50, 16, this::submit));
        String count = input.getValue().length() + "/300";
        g.text(font, count, x0 + 12, inputY + 16, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);

        // 아래 버튼
        int by = bottom - 22;
        int bw = 70;
        WishUi.button(g, font, "새 소원", x0 + 12, by, bw, 14, mx, my, true, WishUi.MYSTIC, open);
        hits.add(new Hit(x0 + 12, by, bw, 14, () -> {
            AiClientState.newWish();
            updateHint();
        }));
        boolean canCancel = !AiClientState.wishId.isEmpty() && !AiClientState.wishState.isEmpty() && !isTerminal(AiClientState.wishState);
        WishUi.button(g, font, "소원 취소", x0 + 16 + bw, by, bw, 14, mx, my, canCancel, WishUi.DANGER, open);
        if (canCancel) hits.add(new Hit(x0 + 16 + bw, by, bw, 14, () -> AiClientState.action("cancel", AiClientState.wishId)));
        WishUi.button(g, font, "닫기", x1 - 12 - bw, by, bw, 14, mx, my, true, WishUi.GOLD, open);
        hits.add(new Hit(x1 - 12 - bw, by, bw, 14, this::onClose));
        g.text(font, "Tab: 탭 전환", x0 + 24 + bw * 2, by + 3, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
    }

    private int interactionHeight(AiClientState.Interaction it) {
        int w = (x1 - x0) - 40;
        int lines = WishUi.wrap(font, it.prompt, w).size();
        int base = 18 + lines * 10;
        return switch (it.kind) {
            case "input" -> base + 14;
            case "choices" -> base + ((it.options.size() + 1) / 2) * 17 + 4;
            default -> base + 22;
        };
    }

    private void drawInteraction(GuiGraphicsExtractor g, AiClientState.Interaction it, int mx, int my, int y, int h, float open) {
        int cx0 = x0 + 10, cx1 = x1 - 10;
        int accent = it.kind.equals("contract") ? WishUi.WARNING : it.kind.equals("consent") ? WishUi.INFO : WishUi.MYSTIC;
        g.fillGradient(cx0, y, cx1, y + h, WishUi.alpha(0xF0201C36, open), WishUi.alpha(0xF0181A2C, open));
        g.outline(cx0, y, cx1 - cx0, h, WishUi.alpha(accent, open));
        String head = !it.title.isEmpty() ? it.title : switch (it.kind) {
            case "contract" -> "소원의 계약";
            case "input" -> "새벽별의 물음";
            case "consent" -> "동의 요청";
            default -> "운명의 갈림길";
        };
        g.text(font, "✦ " + head, cx0 + 6, y + 4, WishUi.textAlpha(accent, open), false);
        long rem = AiClientState.remaining(it.deadlineLocal);
        if (rem >= 0) {
            String tm = WishUi.time(rem);
            g.text(font, tm, cx1 - 6 - font.width(tm), y + 4, WishUi.textAlpha(rem < 10_000 ? WishUi.DANGER : WishUi.TEXT_DIM, open), false);
            float frac = it.totalMs > 0 ? (float) rem / it.totalMs : 0;
            WishUi.bar(g, cx0 + 6, y + 14, cx1 - cx0 - 12, 3, frac, rem < 10_000 ? WishUi.DANGER : accent, open);
        }
        int ty = y + 19;
        for (FormattedCharSequence s : WishUi.wrap(font, it.prompt, cx1 - cx0 - 20)) {
            g.text(font, s, cx0 + 8, ty, WishUi.textAlpha(WishUi.TEXT, open), false);
            ty += 10;
        }
        switch (it.kind) {
            case "input" -> g.text(font, "↓ 아래 입력칸에 답을 적고 Enter", cx0 + 8, ty + 1, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
            case "choices" -> {
                int colW = (cx1 - cx0 - 20) / 2;
                for (int i = 0; i < it.options.size(); i++) {
                    int bx = cx0 + 8 + (i % 2) * (colW + 4), by = ty + 2 + (i / 2) * 17;
                    WishUi.button(g, font, it.options.get(i), bx, by, colW, 15, mx, my, true, accent, open);
                    int idx = i;
                    hits.add(new Hit(bx, by, colW, 15, () -> {
                        AiClientState.answer(it, idx, it.options.get(idx));
                        AiClientState.lines.add(new AiClientState.Line("player", "[" + it.options.get(idx) + "]", ""));
                    }));
                }
            }
            default -> {
                int bw = 80;
                int bx = (cx0 + cx1) / 2 - bw - 4, by = ty + 3;
                String yes = it.options.isEmpty() ? "수락" : it.options.get(0);
                String no = it.options.size() > 1 ? it.options.get(1) : "거절";
                WishUi.button(g, font, yes, bx, by, bw, 15, mx, my, true, WishUi.SUCCESS, open);
                WishUi.button(g, font, no, bx + bw + 8, by, bw, 15, mx, my, true, WishUi.DANGER, open);
                hits.add(new Hit(bx, by, bw, 15, () -> {
                    AiClientState.answer(it, 0, yes);
                    AiClientState.lines.add(new AiClientState.Line("player", "[" + yes + "]", ""));
                }));
                hits.add(new Hit(bx + bw + 8, by, bw, 15, () -> {
                    AiClientState.answer(it, 1, no);
                    AiClientState.lines.add(new AiClientState.Line("player", "[" + no + "]", ""));
                }));
                if (it.kind.equals("contract") && rem < 0) {
                    String hint = "말로 조건을 바꾸자고 해도 된다";
                    g.text(font, hint, cx1 - 6 - font.width(hint), y + 4, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
                }
            }
        }
    }

    private void drawQuests(GuiGraphicsExtractor g, int mx, int my, int top, int bottom, float open) {
        List<AiClientState.Quest> list = AiClientState.sortedQuests(false);
        int areaBottom = bottom - 26;
        g.enableScissor(x0 + 4, top, x1 - 4, areaBottom);
        int y = top - questScroll;
        if (list.isEmpty()) g.centeredText(font, "아직 받은 과제가 없다.", (x0 + x1) / 2, top + 40, WishUi.textAlpha(WishUi.TEXT_DIM, open));
        int w = x1 - x0 - 24;
        for (AiClientState.Quest q : list) {
            int startY = y;
            int accent = q.active() ? WishUi.GOLD : "COMPLETED".equals(q.state) ? WishUi.SUCCESS : "FAILED".equals(q.state) ? WishUi.DANGER : WishUi.TEXT_FAINT;
            // 내용 높이 계산은 그리면서
            y += 4;
            g.text(font, "✦ " + q.title, x0 + 18, y, WishUi.textAlpha(accent, open), false);
            String st = q.active() ? "진행 중" : WishUi.stateKo(q.state);
            g.text(font, st, x1 - 18 - font.width(st), y, WishUi.textAlpha(accent, open), false);
            y += 12;
            long rem = AiClientState.remaining(q.deadlineLocal);
            if (rem >= 0 && q.active()) {
                g.text(font, "남은 시간 " + WishUi.time(rem), x0 + 18, y, WishUi.textAlpha(rem < 30_000 ? WishUi.DANGER : WishUi.TEXT_DIM, open), false);
                WishUi.bar(g, x0 + 100, y + 2, w - 92, 4, q.totalMs > 0 ? (float) rem / q.totalMs : 0, WishUi.WARNING, open);
                y += 11;
            }
            for (String section : new String[]{q.description, q.conditions.isEmpty() ? "" : "조건: " + q.conditions}) {
                if (section == null || section.isEmpty()) continue;
                for (FormattedCharSequence s : WishUi.wrap(font, section, w - 16)) {
                    g.text(font, s, x0 + 18, y, WishUi.textAlpha(WishUi.TEXT, open), false);
                    y += 10;
                }
            }
            for (AiClientState.Objective o : q.objectives) {
                float target = (float) (o.target <= 0 ? 1 : Math.min(1, o.progress / o.target));
                o.shown += (target - o.shown) * 0.15f;
                String mark = o.done ? "■" : "□";
                g.text(font, mark + " " + o.text + (o.optional ? " (선택)" : ""), x0 + 22, y, WishUi.textAlpha(o.done ? WishUi.SUCCESS : WishUi.TEXT, open), false);
                String num = (int) o.progress + "/" + (int) o.target;
                g.text(font, num, x1 - 18 - font.width(num), y, WishUi.textAlpha(WishUi.TEXT_DIM, open), false);
                y += 10;
                WishUi.bar(g, x0 + 22, y, w - 12, 4, o.shown, o.done ? WishUi.SUCCESS : WishUi.GOLD, open);
                y += 8;
            }
            if (!q.reward.isEmpty()) {
                g.text(font, "보상: " + q.reward, x0 + 18, y, WishUi.textAlpha(WishUi.SUCCESS, open), false);
                y += 10;
            }
            if (!q.failure.isEmpty()) {
                g.text(font, "실패: " + q.failure, x0 + 18, y, WishUi.textAlpha(WishUi.WARNING, open), false);
                y += 10;
            }
            if (!q.note.isEmpty()) {
                g.text(font, q.note, x0 + 18, y, WishUi.textAlpha(WishUi.TEXT_DIM, open), false);
                y += 10;
            }
            y += 4;
            g.fill(x0 + 12, startY, x0 + 14, y, WishUi.alpha(accent, open));
            g.fill(x0 + 14, y, x1 - 12, y + 1, WishUi.alpha(WishUi.INNER_LINE, open));
            y += 6;
        }
        int total = y + questScroll - top;
        questScroll = Math.min(questScroll, Math.max(0, total - (areaBottom - top)));
        g.disableScissor();
        int bw = 70;
        WishUi.button(g, font, "닫기", x1 - 12 - bw, bottom - 22, bw, 14, mx, my, true, WishUi.GOLD, open);
        hits.add(new Hit(x1 - 12 - bw, bottom - 22, bw, 14, this::onClose));
    }

    private void drawHistory(GuiGraphicsExtractor g, int top, int bottom, float open) {
        SimpleDateFormat fmt = new SimpleDateFormat("MM.dd HH:mm");
        int y = top;
        if (AiClientState.history.isEmpty()) g.centeredText(font, "아직 빈 소원이 없다.", (x0 + x1) / 2, top + 40, WishUi.textAlpha(WishUi.TEXT_DIM, open));
        for (AiClientState.PastWish p : AiClientState.history) {
            if (y > bottom - 40) break;
            g.text(font, fmt.format(new Date(p.time)), x0 + 14, y, WishUi.textAlpha(WishUi.TEXT_FAINT, open), false);
            String st = WishUi.stateKo(p.state);
            g.text(font, st, x1 - 14 - font.width(st), y, WishUi.textAlpha(WishUi.stateColor(p.state), open), false);
            String text = p.text.startsWith("(자율)") ? "✧ 새벽별의 이야기" : "\"" + p.text + "\"";
            g.text(font, font.plainSubstrByWidth(text, x1 - x0 - 140), x0 + 74, y, WishUi.textAlpha(WishUi.TEXT, open), false);
            y += 10;
            if (p.outcome != null && !p.outcome.isEmpty()) {
                g.text(font, font.plainSubstrByWidth("  └ " + p.outcome, x1 - x0 - 90), x0 + 74, y, WishUi.textAlpha(WishUi.TEXT_DIM, open), false);
                y += 10;
            }
            y += 3;
        }
    }
}
