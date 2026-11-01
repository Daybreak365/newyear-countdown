package dev.newyear.countdown.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 관리자 화면 (/wishai gui 또는 단축키). 소원 UI 와 같은 디자인 시스템(WishUi)을 쓴다.
 * 탭: 개요(상태·비용·킬 스위치) / 스크립트(검사기·승인·롤백·코드 편집·블록 미리보기) / 소원 / 기억(공유·삭제).
 * 화면은 서버 응답을 그리기만 하고, 모든 동작은 서버가 권한을 확인한 뒤 실행한다.
 */
public class AdminScreen extends Screen {
    private enum Tab { OVERVIEW, SCRIPTS, WISHES, MEMORY }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();
    private Tab tab = Tab.OVERVIEW;
    private int x0, y0, x1, y1;
    private int listScroll, detailScroll;
    private String selected;
    private String armed = "";
    private long armedAt;
    private int memoryPlayer;

    public AdminScreen() {
        super(Component.literal("새벽별 관리"));
    }

    @Override
    protected void init() {
        int w = Math.min(480, width - 12), h = Math.min(300, height - 12);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        refresh();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void refresh() {
        switch (tab) {
            case OVERVIEW -> AdminClient.request("overview");
            case SCRIPTS -> {
                AdminClient.request("scripts", "filter", AdminClient.scriptFilter);
                if (selected != null) AdminClient.request("script", "id", selected);
            }
            case WISHES -> AdminClient.request("wishes", "filter", "open");
            case MEMORY -> AdminClient.request("memory", "who", currentMemoryWho());
        }
    }

    private String currentMemoryWho() {
        JsonObject m = AdminClient.memory;
        if (m == null || !m.has("players")) return "world";
        JsonArray ps = m.getAsJsonArray("players");
        if (ps.isEmpty()) return "world";
        return ps.get(Math.floorMod(memoryPlayer, ps.size())).getAsString();
    }

    /** 위험한 동작은 두 번 눌러야 한다 (3초 안에). */
    private boolean confirm(String key) {
        if (armed.equals(key) && System.currentTimeMillis() - armedAt < 3000) {
            armed = "";
            return true;
        }
        armed = key;
        armedAt = System.currentTimeMillis();
        AdminClient.message = "한 번 더 누르면 실행합니다.";
        AdminClient.messageAt = armedAt;
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB) {
            switchTab(Tab.values()[(tab.ordinal() + 1) % Tab.values().length]);
            return true;
        }
        if (event.key() == InputConstants.KEY_F5) {
            refresh();
            return true;
        }
        return super.keyPressed(event);
    }

    private void switchTab(Tab t) {
        tab = t;
        listScroll = 0;
        detailScroll = 0;
        refresh();
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
        int split = x0 + (x1 - x0) * 2 / 5;
        if (tab == Tab.SCRIPTS && mx > split) detailScroll = Math.max(0, detailScroll - (int) (sy * 14));
        else listScroll = Math.max(0, listScroll - (int) (sy * 14));
        return true;
    }

    // ---------------------------------------------------------------- 그리기

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        hits.clear();
        WishUi.panel(g, x0, y0, x1, y1, 1f);
        float t = WishUi.now();
        WishUi.twinkle(g, x0 + 14, y0 + 12, t * 3, WishUi.STAR);
        g.text(font, "✦ 새벽별 관리", x0 + 22, y0 + 8, WishUi.GOLD, false);
        JsonObject ov = AdminClient.overview;
        if (ov != null) {
            boolean kill = b(ov, "killSwitch");
            String st = kill ? "킬 스위치 ON" : b(ov, "enabled") ? "AI 켜짐" : "AI 꺼짐(설정)";
            int cw = font.width(st) + 8;
            WishUi.chip(g, font, st, x1 - 12 - cw, y0 + 7, kill ? WishUi.DANGER : b(ov, "enabled") ? WishUi.SUCCESS : WishUi.TEXT_FAINT, 1f);
            if (i(ov, "pendingApproval") > 0) {
                String pa = "승인 대기 " + i(ov, "pendingApproval");
                int pw = font.width(pa) + 8;
                WishUi.chip(g, font, pa, x1 - 16 - cw - pw, y0 + 7, WishUi.WARNING, 1f);
            }
        }
        // 탭
        int tx = x0 + 12, ty = y0 + 21;
        String[] names = {"개요", "스크립트", "소원", "기억"};
        for (int k = 0; k < names.length; k++) {
            int w = font.width(names[k]) + 12;
            boolean sel = tab.ordinal() == k;
            boolean hover = WishUi.inside(mx, my, tx, ty, w, 12);
            g.fill(tx, ty, tx + w, ty + 12, sel ? 0xE0303658 : hover ? 0xC0262A44 : 0x801A1D30);
            if (sel) g.fill(tx, ty + 11, tx + w, ty + 12, WishUi.GOLD);
            g.text(font, names[k], tx + 6, ty + 2, sel ? WishUi.GOLD : WishUi.TEXT_DIM, false);
            Tab target = Tab.values()[k];
            hits.add(new Hit(tx, ty, w, 12, () -> switchTab(target)));
            tx += w + 3;
        }
        WishUi.button(g, font, "설정", tx, ty, 36, 12, mx, my, true, WishUi.GOLD, 1f);
        hits.add(new Hit(tx, ty, 36, 12, () -> minecraft.gui.setScreen(new AdminSettingsScreen(this))));
        WishUi.button(g, font, "새로 고침", x1 - 12 - 56, ty - 1, 56, 13, mx, my, true, WishUi.INFO, 1f);
        hits.add(new Hit(x1 - 12 - 56, ty - 1, 56, 13, this::refresh));
        g.fill(x0 + 8, ty + 12, x1 - 8, ty + 13, WishUi.INNER_LINE);

        int top = ty + 17, bottom = y1 - 20;
        switch (tab) {
            case OVERVIEW -> drawOverview(g, mx, my, top, bottom);
            case SCRIPTS -> drawScripts(g, mx, my, top, bottom);
            case WISHES -> drawWishes(g, mx, my, top, bottom);
            case MEMORY -> drawMemory(g, mx, my, top, bottom);
        }
        // 아래 알림 줄
        if (!AdminClient.message.isEmpty() && System.currentTimeMillis() - AdminClient.messageAt < 12_000) {
            g.text(font, font.plainSubstrByWidth("» " + AdminClient.message, x1 - x0 - 100), x0 + 12, y1 - 14, WishUi.GOLD, false);
        } else {
            g.text(font, "Tab: 탭 · F5: 새로 고침 · 휠: 스크롤", x0 + 12, y1 - 14, WishUi.TEXT_FAINT, false);
        }
        WishUi.button(g, font, "닫기", x1 - 12 - 60, y1 - 17, 60, 13, mx, my, true, WishUi.GOLD, 1f);
        hits.add(new Hit(x1 - 12 - 60, y1 - 17, 60, 13, this::onClose));
        super.extractRenderState(g, mx, my, delta);
    }

    private void drawOverview(GuiGraphicsExtractor g, int mx, int my, int top, int bottom) {
        JsonObject o = AdminClient.overview;
        if (o == null) {
            g.centeredText(font, "서버에 묻는 중…", (x0 + x1) / 2, top + 40, WishUi.TEXT_DIM);
            return;
        }
        int col = (x1 - x0 - 24) / 2;
        String[][] rows = {
                {"모델", s(o, "model") + " (" + s(o, "effort") + ")"},
                {"API 키", b(o, "apiKey") ? ("session".equals(s(o, "apiKeySource")) ? "임시 키 사용 중" : "환경 변수 키 있음") : "없음 (설정 → 세션 키)"},
                {"오늘 비용", String.format("$%.4f / $%.2f", d(o, "costToday"), d(o, "budget"))},
                {"누적 비용", String.format("$%.4f", d(o, "costLifetime"))},
                {"오늘 턴·요청·오류", i(o, "turnsToday") + " · " + i(o, "requestsToday") + " · " + i(o, "errorsToday")},
                {"토큰 (입력/캐시/출력)", String.format("%,d / %,d / %,d", l(o, "inputTokens"), l(o, "cachedTokens"), l(o, "outputTokens"))},
                {"추론·임베딩 토큰", String.format("%,d · %,d", l(o, "reasoningTokens"), l(o, "embeddingTokens"))},
                {"응답 시간·오류율", String.format("%.1fs · %.0f%%", d(o, "avgLatencyMs") / 1000, d(o, "errorRate") * 100)},
                {"진행·대기", i(o, "active") + " · " + i(o, "queued")},
                {"스크립트", i(o, "liveScripts") + " 실행 / " + i(o, "scripts") + " 기록, 승인 대기 " + i(o, "pendingApproval")},
                {"소원·퀘스트", i(o, "openWishes") + " 열림 / " + i(o, "wishes") + ", 퀘스트 " + i(o, "quests")},
                {"기억·의미 검색", i(o, "memories") + " (벡터 " + i(o, "vectors") + ", " + semanticKo(s(o, "semantic")) + ")"},
                {"버려진 이벤트", String.valueOf(l(o, "droppedEvents"))},
        };
        int y = top;
        for (int k = 0; k < rows.length; k++) {
            int cx = x0 + 14 + (k % 2) * col;
            if (k % 2 == 0 && k > 0) y += 20;
            g.text(font, rows[k][0], cx, y, WishUi.TEXT_FAINT, false);
            g.text(font, font.plainSubstrByWidth(rows[k][1], col - 8), cx, y + 9, WishUi.TEXT, false);
        }
        y += 24;
        if (o.has("costByModel") && !o.getAsJsonObject("costByModel").isEmpty()) {
            StringBuilder sb = new StringBuilder("모델별 오늘: ");
            for (var e : o.getAsJsonObject("costByModel").entrySet()) sb.append(e.getKey()).append(String.format(" $%.4f  ", e.getValue().getAsDouble()));
            g.text(font, font.plainSubstrByWidth(sb.toString(), x1 - x0 - 28), x0 + 14, y, WishUi.TEXT_DIM, false);
            y += 12;
        }
        int by = Math.max(y + 4, bottom - 18);
        boolean kill = b(o, "killSwitch");
        int bx = x0 + 14;
        WishUi.button(g, font, kill ? "킬 스위치 해제" : "킬 스위치 켜기", bx, by, 92, 14, mx, my, true, kill ? WishUi.SUCCESS : WishUi.DANGER, 1f);
        hits.add(new Hit(bx, by, 92, 14, () -> {
            if (!kill && !confirm("kill")) return;
            JsonObject req = new JsonObject();
            req.addProperty("op", "killswitch");
            req.addProperty("on", !kill);
            AdminClient.send(req);
        }));
        bx += 96;
        WishUi.button(g, font, "설정 다시 읽기", bx, by, 86, 14, mx, my, true, WishUi.INFO, 1f);
        hits.add(new Hit(bx, by, 86, 14, () -> AdminClient.request("reload")));
        bx += 90;
        WishUi.button(g, font, "지금 저장", bx, by, 66, 14, mx, my, true, WishUi.GOLD, 1f);
        hits.add(new Hit(bx, by, 66, 14, () -> AdminClient.request("save")));
    }

    private static String semanticKo(String s) {
        return switch (s) {
            case "on" -> "켜짐";
            case "waiting" -> "대기: 키/예산";
            default -> "꺼짐";
        };
    }

    // ---------------------------------------------------------------- 스크립트

    private void drawScripts(GuiGraphicsExtractor g, int mx, int my, int top, int bottom) {
        int split = x0 + (x1 - x0) * 2 / 5;
        // 필터
        int fx = x0 + 12;
        String[][] filters = {{"live", "실행·대기"}, {"pending", "승인 대기"}, {"all", "전체"}};
        for (String[] f : filters) {
            boolean sel = AdminClient.scriptFilter.equals(f[0]);
            int w = font.width(f[1]) + 8;
            WishUi.chip(g, font, f[1], fx, top, sel ? WishUi.GOLD : WishUi.TEXT_FAINT, 1f);
            hits.add(new Hit(fx, top, w, 11, () -> {
                AdminClient.scriptFilter = f[0];
                listScroll = 0;
                AdminClient.request("scripts", "filter", f[0]);
            }));
            fx += w + 3;
        }
        int listTop = top + 15;
        g.enableScissor(x0 + 6, listTop, split, bottom);
        JsonObject sc = AdminClient.scripts;
        int y = listTop - listScroll;
        if (sc == null || !sc.has("list") || sc.getAsJsonArray("list").isEmpty()) {
            g.text(font, sc == null ? "불러오는 중…" : "스크립트가 없습니다.", x0 + 14, listTop + 4, WishUi.TEXT_DIM, false);
        } else {
            for (JsonElement e : sc.getAsJsonArray("list")) {
                JsonObject r = e.getAsJsonObject();
                String id = s(r, "id");
                boolean sel = id.equals(selected);
                boolean hover = WishUi.inside(mx, my, x0 + 10, y, split - x0 - 14, 20) && my >= listTop && my < bottom;
                if (sel || hover) g.fill(x0 + 10, y, split - 4, y + 20, sel ? 0xC0303658 : 0x80262A44);
                int col = stateColor(s(r, "state"));
                g.fill(x0 + 10, y + 1, x0 + 12, y + 19, col);
                String label = s(r, "label").isEmpty() ? s(r, "name") : s(r, "label");
                g.text(font, font.plainSubstrByWidth(label, split - x0 - 30), x0 + 16, y + 2, WishUi.TEXT, false);
                String sub = s(r, "owner") + " · " + stateKo(s(r, "state")) + " · v" + i(r, "version") + (i(r, "errors") > 0 ? " · 오류 " + i(r, "errors") : "");
                g.text(font, font.plainSubstrByWidth(sub, split - x0 - 30), x0 + 16, y + 11, WishUi.TEXT_FAINT, false);
                if (my >= listTop && my < bottom) hits.add(new Hit(x0 + 10, Math.max(y, listTop), split - x0 - 14, 20, () -> {
                    selected = id;
                    detailScroll = 0;
                    AdminClient.request("script", "id", id);
                }));
                y += 22;
            }
        }
        g.disableScissor();
        g.fill(split, top, split + 1, bottom, WishUi.INNER_LINE);
        drawScriptDetail(g, mx, my, split + 8, top, x1 - 10, bottom);
    }

    private void drawScriptDetail(GuiGraphicsExtractor g, int mx, int my, int dx0, int top, int dx1, int bottom) {
        JsonObject r = AdminClient.script;
        if (selected == null || r == null || !s(r, "id").equals(selected)) {
            g.text(font, selected == null ? "← 스크립트를 고르세요" : "불러오는 중…", dx0 + 4, top + 4, WishUi.TEXT_DIM, false);
            return;
        }
        String id = s(r, "id");
        String state = s(r, "state");
        // 동작 버튼 (위쪽 고정)
        List<String[]> actions = new ArrayList<>();
        if (state.equals("PENDING_APPROVAL")) {
            actions.add(new String[]{"approve", "승인"});
            actions.add(new String[]{"deny", "거부"});
        }
        if (state.equals("ACTIVE")) {
            actions.add(new String[]{"stop", "멈춤"});
            actions.add(new String[]{"suspend", "일시정지"});
        }
        if (state.equals("SUSPENDED") || state.equals("STOPPED") || state.equals("FAULTED") || state.equals("EXPIRED")) actions.add(new String[]{"resume", "재개"});
        if (i(r, "blocks") > 0) actions.add(new String[]{"rollback", "블록 롤백"});
        if (i(r, "conflicts") > 0) actions.add(new String[]{"rollback_force", "강제 롤백"});
        if (r.has("history") && !r.getAsJsonArray("history").isEmpty()) actions.add(new String[]{"revert", "이전 버전"});
        actions.add(new String[]{"edit", "코드 편집"});
        if (i(r, "preview") > 0 || i(r, "blocks") > 0) {
            actions.add(new String[]{"preview_screen", "미리보기"});
            actions.add(new String[]{"preview", "월드에 표시"});
        }
        actions.add(new String[]{"delete", "삭제"});
        int bx = dx0, by = top;
        for (String[] a : actions) {
            int w = font.width(a[1]) + 10;
            if (bx + w > dx1) {
                bx = dx0;
                by += 15;
            }
            int accent = switch (a[0]) {
                case "approve", "resume" -> WishUi.SUCCESS;
                case "deny", "delete", "rollback_force" -> WishUi.DANGER;
                case "edit", "preview_screen", "preview" -> WishUi.MYSTIC;
                default -> WishUi.WARNING;
            };
            WishUi.button(g, font, a[1], bx, by, w, 13, mx, my, true, accent, 1f);
            String act = a[0];
            hits.add(new Hit(bx, by, w, 13, () -> {
                switch (act) {
                    case "edit" -> minecraft.gui.setScreen(new AdminCodeScreen(this, id, s(r, "name"), s(r, "code")));
                    case "preview_screen" -> AdminClient.request("preview_data", "id", id);
                    case "delete", "rollback_force", "deny" -> {
                        if (confirm(act + id)) AdminClient.scriptAction(id, act);
                    }
                    default -> AdminClient.scriptAction(id, act);
                }
            }));
            bx += w + 3;
        }
        int infoTop = by + 18;
        g.enableScissor(dx0 - 2, infoTop, dx1 + 2, bottom);
        int y = infoTop - detailScroll;
        int w = dx1 - dx0;
        y = line(g, "✦ " + (s(r, "label").isEmpty() ? s(r, "name") : s(r, "label") + " (" + s(r, "name") + ")"), dx0, y, w, WishUi.GOLD);
        y = line(g, id + " · " + s(r, "owner") + " · " + stateKo(state) + (s(r, "reason").isEmpty() ? "" : " (" + s(r, "reason") + ")"), dx0, y, w, stateColor(state));
        if (!s(r, "description").isEmpty()) y = wrap(g, s(r, "description"), dx0, y, w, WishUi.TEXT);
        long dur = l(r, "duration"), left = l(r, "left");
        y = line(g, "v" + i(r, "version") + " · " + (s(r, "lasting").equals("true") ? "지속 능력" : "소원 보조") + (b(r, "autonomous") ? " · 자율" : "")
                + " · 기간 " + (dur < 0 ? "무기한" : dur + "초") + (left >= 0 ? " (남음 " + left + "초)" : ""), dx0, y, w, WishUi.TEXT_DIM);
        y = line(g, "실행 " + l(r, "runs") + "회 · 단계 " + String.format("%,d", l(r, "steps")) + " · 힙 ≈ " + human(l(r, "heap")), dx0, y, w, WishUi.TEXT_DIM);
        y = line(g, "엔티티 " + i(r, "entities") + " (남김 " + i(r, "kept") + ") · 블록 " + i(r, "blocks") + " (충돌 " + i(r, "conflicts") + ", 계획 " + i(r, "preview") + ")", dx0, y, w, WishUi.TEXT_DIM);
        y = line(g, "목표(AI) " + i(r, "goals") + " · 모델 " + i(r, "models") + " · 이름 타이머 " + i(r, "timers") + (r.has("resumable") ? " · 재시작 이어짐: " + s(r, "resumable") : ""), dx0, y, w, WishUi.TEXT_DIM);
        y = line(g, "이벤트: " + join(r, "events"), dx0, y, w, WishUi.TEXT_DIM);
        y = wrap(g, "API: " + join(r, "api"), dx0, y, w, WishUi.TEXT_FAINT);
        if (!s(r, "approvedBy").isEmpty()) y = line(g, "승인: " + s(r, "approvedBy"), dx0, y, w, WishUi.SUCCESS);
        if (i(r, "errors") > 0 || !s(r, "lastError").isEmpty()) y = wrap(g, "오류 " + i(r, "errors") + ": " + s(r, "lastError"), dx0, y, w, WishUi.DANGER);
        if (r.has("history") && !r.getAsJsonArray("history").isEmpty()) y = wrap(g, "이전 버전: " + join(r, "history"), dx0, y, w, WishUi.TEXT_FAINT);
        y = wrap(g, "state: " + s(r, "stateJson"), dx0, y + 2, w, WishUi.TEXT_FAINT);
        y += 4;
        g.text(font, "코드", dx0, y, WishUi.GOLD, false);
        y += 10;
        String[] code = s(r, "code").split("\n");
        for (int k = 0; k < code.length && k < 400; k++) {
            g.text(font, String.format("%3d", k + 1), dx0, y, WishUi.TEXT_FAINT, false);
            g.text(font, font.plainSubstrByWidth(code[k].replace("\t", "  "), w - 22), dx0 + 20, y, 0xFFB8C4E0, false);
            y += 9;
        }
        int total = y + detailScroll - infoTop;
        detailScroll = Math.min(detailScroll, Math.max(0, total - (bottom - infoTop)));
        g.disableScissor();
    }

    // ---------------------------------------------------------------- 소원·기억

    private void drawWishes(GuiGraphicsExtractor g, int mx, int my, int top, int bottom) {
        JsonObject o = AdminClient.wishes;
        g.enableScissor(x0 + 6, top, x1 - 6, bottom);
        int y = top - listScroll;
        if (o == null || o.getAsJsonArray("list").isEmpty()) g.text(font, o == null ? "불러오는 중…" : "열린 소원이 없습니다.", x0 + 14, top + 4, WishUi.TEXT_DIM, false);
        SimpleDateFormat fmt = new SimpleDateFormat("MM.dd HH:mm");
        if (o != null) for (JsonElement e : o.getAsJsonArray("list")) {
            JsonObject w = e.getAsJsonObject();
            String st = s(w, "state");
            g.fill(x0 + 10, y + 1, x0 + 12, y + 19, WishUi.stateColor(st));
            g.text(font, fmt.format(new Date(l(w, "created"))) + "  " + s(w, "player"), x0 + 16, y + 2, WishUi.TEXT_DIM, false);
            g.text(font, font.plainSubstrByWidth("\"" + s(w, "text") + "\"", x1 - x0 - 190), x0 + 120, y + 2, WishUi.TEXT, false);
            String meta = WishUi.stateKo(st) + " · 턴 " + i(w, "turns") + " · 스크립트 " + i(w, "scripts") + " · 퀘스트 " + i(w, "quests")
                    + (s(w, "outcome").isEmpty() ? "" : " · " + s(w, "outcome"));
            g.text(font, font.plainSubstrByWidth(meta, x1 - x0 - 100), x0 + 16, y + 11, WishUi.TEXT_FAINT, false);
            String id = s(w, "id");
            boolean open = !List.of("COMPLETED", "FAILED", "REJECTED", "CANCELLED").contains(st);
            if (open) {
                int bx = x1 - 14 - 44;
                WishUi.button(g, font, "취소", bx, y + 3, 44, 13, mx, my, true, WishUi.DANGER, 1f);
                if (my >= top && my < bottom) hits.add(new Hit(bx, y + 3, 44, 13, () -> {
                    if (confirm("wish" + id)) AdminClient.request("wish_cancel", "id", id);
                }));
            }
            y += 22;
        }
        listScroll = Math.min(listScroll, Math.max(0, y + listScroll - bottom));
        g.disableScissor();
    }

    private void drawMemory(GuiGraphicsExtractor g, int mx, int my, int top, int bottom) {
        JsonObject o = AdminClient.memory;
        String who = o != null && o.has("who") ? s(o, "who") : "world";
        int bx = x0 + 12;
        WishUi.button(g, font, "◀", bx, top, 16, 12, mx, my, true, WishUi.GOLD, 1f);
        hits.add(new Hit(bx, top, 16, 12, () -> { memoryPlayer--; listScroll = 0; AdminClient.request("memory", "who", currentMemoryWho()); }));
        String label = who.equals("world") ? "세계의 기억" : who + "의 기억" + (o != null && b(o, "locked") ? " (잠금)" : "");
        g.text(font, label, bx + 22, top + 2, WishUi.GOLD, false);
        int lw = font.width(label);
        WishUi.button(g, font, "▶", bx + 28 + lw, top, 16, 12, mx, my, true, WishUi.GOLD, 1f);
        hits.add(new Hit(bx + 28 + lw, top, 16, 12, () -> { memoryPlayer++; listScroll = 0; AdminClient.request("memory", "who", currentMemoryWho()); }));
        g.text(font, "∿ = 의미 검색 벡터 있음, ↔ = 공유됨", x1 - 14 - font.width("∿ = 의미 검색 벡터 있음, ↔ = 공유됨"), top + 2, WishUi.TEXT_FAINT, false);
        int listTop = top + 16;
        g.enableScissor(x0 + 6, listTop, x1 - 6, bottom);
        int y = listTop - listScroll;
        if (o == null || o.getAsJsonArray("list").isEmpty()) g.text(font, o == null ? "불러오는 중…" : "기억이 없습니다.", x0 + 14, listTop + 4, WishUi.TEXT_DIM, false);
        if (o != null) for (JsonElement e : o.getAsJsonArray("list")) {
            JsonObject m = e.getAsJsonObject();
            String id = s(m, "id");
            int imp = i(m, "importance");
            String head = id + " [" + s(m, "kind") + " " + imp + "]" + (b(m, "vector") ? " ∿" : "") + (b(m, "archived") ? " 보관됨" : "") + (b(m, "summary") ? " 요약" : "")
                    + (m.has("thread") ? " «" + s(m, "thread") + "»" : "") + (s(m, "shared").isEmpty() ? "" : " ↔ " + s(m, "shared"));
            g.text(font, font.plainSubstrByWidth(head, x1 - x0 - 140), x0 + 14, y + 1, s(m, "shared").isEmpty() ? WishUi.TEXT_FAINT : WishUi.INFO, false);
            g.text(font, font.plainSubstrByWidth(s(m, "text"), x1 - x0 - 140), x0 + 14, y + 10, b(m, "archived") ? WishUi.TEXT_FAINT : WishUi.TEXT, false);
            int rb = x1 - 14 - 40;
            WishUi.button(g, font, "잊기", rb, y + 3, 40, 13, mx, my, true, WishUi.DANGER, 1f);
            if (my >= listTop && my < bottom) hits.add(new Hit(rb, y + 3, 40, 13, () -> {
                if (confirm("forget" + id)) AdminClient.request("memory_forget", "id", id);
            }));
            if (!s(m, "shared").isEmpty()) {
                WishUi.button(g, font, "공유 거둠", rb - 62, y + 3, 58, 13, mx, my, true, WishUi.WARNING, 1f);
                if (my >= listTop && my < bottom) hits.add(new Hit(rb - 62, y + 3, 58, 13, () -> AdminClient.request("memory_unshare", "id", id)));
            }
            y += 22;
        }
        listScroll = Math.min(listScroll, Math.max(0, y + listScroll - bottom));
        g.disableScissor();
    }

    // ---------------------------------------------------------------- 도우미

    private int line(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        g.text(font, font.plainSubstrByWidth(text, w), x, y, color, false);
        return y + 10;
    }

    private int wrap(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        for (var seq : WishUi.wrap(font, text, w)) {
            g.text(font, seq, x, y, color, false);
            y += 9;
        }
        return y + 1;
    }

    static int stateColor(String st) {
        return switch (st) {
            case "ACTIVE" -> WishUi.SUCCESS;
            case "PENDING_APPROVAL" -> WishUi.WARNING;
            case "SUSPENDED" -> WishUi.INFO;
            case "FAULTED" -> WishUi.DANGER;
            default -> WishUi.TEXT_FAINT;
        };
    }

    static String stateKo(String st) {
        return switch (st) {
            case "ACTIVE" -> "실행 중";
            case "PENDING_APPROVAL" -> "승인 대기";
            case "SUSPENDED" -> "일시정지";
            case "STOPPED" -> "멈춤";
            case "EXPIRED" -> "만료";
            case "FAULTED" -> "오류로 멈춤";
            case "DRAFT" -> "초안";
            default -> st;
        };
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    private static String join(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return "";
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(k)) out.add(e.getAsString());
        return String.join(", ", out);
    }

    static String s(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    static int i(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
    }

    static long l(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
    }

    static double d(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }

    static boolean b(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
