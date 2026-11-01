package dev.newyear.countdown.client.ai;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** One-way credential entry. The field is never populated from server state or retained on close. */
public final class AdminSessionKeyScreen extends Screen {
    private final Screen parent;
    private EditBox field;
    private Button apply, clear;
    private JsonObject seenResult;
    private int x0, y0, w;
    private boolean pending;
    private long sentAt;
    private String source = "loading", status = "서버 상태를 불러오는 중…";

    public AdminSessionKeyScreen(Screen parent) {
        super(Component.literal("임시 API 키"));
        this.parent = parent;
        AdminClient.sessionKeyResult = null;
        AdminClient.request("session_key");
    }

    @Override protected void init() {
        // Resizing also clears any unsubmitted secret.
        if (field != null) field.setValue("");
        w = Math.min(430, width - 16);
        x0 = (width - w) / 2;
        y0 = Math.max(4, (height - 200) / 2);
        field = new EditBox(font, x0 + 14, y0 + 88, w - 28, 20, Component.literal("API 키 (입력 숨김)")) {
            @Override protected MutableComponent createNarrationMessage() {
                return Component.literal("API 키 입력란. 입력 내용은 숨겨져 있습니다.");
            }
            @Override public boolean keyPressed(KeyEvent event) {
                // Do not copy/cut the hidden value back into the clipboard. Paste remains available.
                if (event.hasControlDown() && (event.key() == InputConstants.KEY_C || event.key() == InputConstants.KEY_X)) return true;
                return super.keyPressed(event);
            }
        };
        field.setMaxLength(512);
        field.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(field);
        setInitialFocus(field);
        int bw = (w - 36) / 3;
        apply = addRenderableWidget(Button.builder(Component.literal("임시 적용"), b -> send("set"))
                .bounds(x0 + 14, y0 + 118, bw, 20).build());
        clear = addRenderableWidget(Button.builder(Component.literal("임시 키 삭제"), b -> send("clear"))
                .bounds(x0 + 18 + bw, y0 + 118, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("돌아가기"), b -> onClose())
                .bounds(x0 + 22 + bw * 2, y0 + 118, bw, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        field.setEditable(!pending);
        apply.active = !pending && !source.equals("loading") && !field.getValue().isBlank();
        clear.active = !pending && source.equals("session");
    }

    private void send(String action) {
        if (pending || source.equals("loading")) return;
        JsonObject request = new JsonObject();
        request.addProperty("op", "session_key");
        request.addProperty("action", action);
        if (action.equals("set")) {
            if (field.getValue().isBlank()) return;
            request.addProperty("key", field.getValue());
        }
        field.setValue("");
        pending = true;
        sentAt = System.currentTimeMillis();
        status = "서버에 적용하는 중…";
        AdminClient.send(request);
        request.remove("key");
        updateButtons();
    }

    @Override public void tick() {
        if (AdminClient.sessionKeyResult != null && AdminClient.sessionKeyResult != seenResult) {
            seenResult = AdminClient.sessionKeyResult;
            source = seenResult.get("source").getAsString();
            status = seenResult.get("text").getAsString();
            pending = false;
        } else if (pending && System.currentTimeMillis() - sentAt > 15000) {
            pending = false;
            source = "loading";
            status = "응답이 늦어 상태를 다시 확인합니다.";
            AdminClient.request("session_key");
        }
        updateButtons();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        field.setValue("");
        minecraft.gui.setScreen(parent);
    }
    @Override public void removed() {
        if (field != null) field.setValue("");
        super.removed();
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            if (apply.active) send("set");
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        WishUi.panel(g, x0, y0, x0 + w, y0 + 200, 1f);
        g.text(font, "✦ 임시 API 키 · 관리자 전용", x0 + 14, y0 + 12, WishUi.GOLD, false);
        String state = switch (source) {
            case "session" -> "현재: 임시 키 사용 중";
            case "environment" -> "현재: 환경 변수 키 사용 중";
            case "none" -> "현재: 키 없음";
            default -> "현재 상태 확인 중…";
        };
        g.text(font, state, x0 + 14, y0 + 33, WishUi.TEXT, false);
        g.text(font, "현재 서버의 AI 요청에 사용합니다.", x0 + 14, y0 + 49, WishUi.TEXT_DIM, false);
        g.text(font, "입력한 관리자 퇴장 또는 서버·월드 종료 시 삭제됩니다.", x0 + 14, y0 + 62, WishUi.TEXT_DIM, false);
        g.text(font, "환경 변수 키보다 우선하며, 파일에는 저장하지 않습니다.", x0 + 14, y0 + 75, WishUi.TEXT_FAINT, false);
        int y = y0 + 150;
        for (var line : font.split(Component.literal(status), w - 28)) {
            g.text(font, line, x0 + 14, y, WishUi.GOLD, false);
            y += 10;
            if (y > y0 + 185) break;
        }
        super.extractRenderState(g, mx, my, delta);
    }
}
