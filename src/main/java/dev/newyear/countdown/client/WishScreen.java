package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import dev.newyear.countdown.wish.WishPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 소원 쓰기 화면. 적고 "연등 날리기"를 누르면 서버가 연등을 띄운다. */
public class WishScreen extends Screen {
    private EditBox field;
    private Button send;
    private final long openNanos = System.nanoTime();

    public WishScreen() {
        super(Component.translatable("wish.newyearcountdown.ui.title"));
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;
        field = new EditBox(font, cx - 110, cy - 6, 220, 20, Component.translatable("wish.newyearcountdown.ui.hint"));
        field.setMaxLength(WishPackets.MAX_LEN);
        field.setHint(Component.translatable("wish.newyearcountdown.ui.hint").withStyle(net.minecraft.ChatFormatting.GRAY));
        field.setResponder(t -> { if (send != null) send.active = !t.trim().isEmpty(); });
        addRenderableWidget(field);
        setInitialFocus(field);

        send = Button.builder(Component.translatable("wish.newyearcountdown.ui.send"), b -> submit())
                .bounds(cx - 110, cy + 24, 106, 20).build();
        send.active = false;
        addRenderableWidget(send);
        addRenderableWidget(Button.builder(Component.translatable("wish.newyearcountdown.ui.cancel"), b -> onClose())
                .bounds(cx + 4, cy + 24, 106, 20).build());
    }

    private void submit() {
        String t = field.getValue().trim();
        if (t.isEmpty()) return;
        if (ClientPlayNetworking.canSend(WishPackets.WishC2S.ID)) {
            ClientPlayNetworking.send(new WishPackets.WishC2S(t));
        }
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractBackground(ctx, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;
        float t = (System.nanoTime() - openNanos) / 1.0e9f;
        // 밤하늘 느낌의 패널
        int x0 = cx - 130, y0 = cy - 62, x1 = cx + 130, y1 = cy + 54;
        ctx.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF2A2230);
        ctx.fillGradient(x0, y0, x1, y1, 0xF0120E22, 0xF02A1A30);
        ctx.outline(x0 + 2, y0 + 2, x1 - x0 - 4, y1 - y0 - 4, 0xFF8A6A3A);
        // 반짝이는 별
        for (int i = 0; i < 18; i++) {
            int sx = x0 + 8 + (i * 53) % (x1 - x0 - 16);
            int sy = y0 + 8 + (i * 29) % 30;
            float tw = 0.5f + 0.5f * (float) Math.sin(t * 2f + i * 1.7f);
            ctx.fill(sx, sy, sx + 1, sy + 1, ((int) (60 + 150 * tw) << 24) | 0xFFF2C8);
        }
        drawLantern(ctx, x0 + 24, y0 + 34, t, 0f);
        drawLantern(ctx, x1 - 24, y0 + 34, t, 2.1f);
        ctx.centeredText(font, title, cx, y0 + 10, 0xFFF3D98A);
        ctx.centeredText(font, Component.translatable("wish.newyearcountdown.ui.sub"), cx, y0 + 24, 0xFFC9B8A0);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;
        String count = field.getValue().length() + " / " + WishPackets.MAX_LEN;
        ctx.text(font, count, cx + 110 - font.width(count), cy - 18, 0xFF8C7C68, false);
    }

    /** 작은 연등 그림 (GUI 용): 몸통 사다리꼴 + 일렁이는 불빛 + 꼭대기 매듭. */
    static void drawLantern(GuiGraphicsExtractor ctx, int cx, int cy, float time, float phase) {
        int bob = Math.round(2f * (float) Math.sin(time * 1.4f + phase));
        cy += bob;
        int h = 26;
        float flick = 0.85f + 0.15f * (float) Math.sin(time * 6f + phase);
        // 번짐
        for (int r = 0; r < 4; r++) {
            int a = (int) (22 * flick) - r * 4;
            if (a > 0) ctx.fill(cx - 18 - r * 2, cy - 4 - r * 2, cx + 18 + r * 2, cy + h + 3 + r * 2, (a << 24) | 0xFFB050);
        }
        for (int y = 0; y < h; y++) {
            float k = y / (float) (h - 1);
            int half = Math.round(8 + 6 * k);                   // 위는 좁고 아래는 넓다
            int rr = 255, gg = (int) (190 + 40 * (1 - k) * flick), bb = (int) (120 + 60 * (1 - k));
            ctx.fill(cx - half, cy + y, cx + half, cy + y + 1, 0xFF000000 | (rr << 16) | (Math.min(255, gg) << 8) | Math.min(255, bb));
            ctx.fill(cx - half, cy + y, cx - half + 1, cy + y + 1, 0xFFB06A30);
            ctx.fill(cx + half - 1, cy + y, cx + half, cy + y + 1, 0xFFB06A30);
        }
        ctx.fill(cx - 5, cy - 3, cx + 5, cy, 0xFFD9A441);       // 윗부분
        ctx.fill(cx - 1, cy - 6, cx + 1, cy - 3, 0xFF7A4A20);   // 매듭
        ctx.fill(cx - 15, cy + h, cx + 15, cy + h + 2, 0xFF7A5230); // 아래 테두리
        int fh = (int) (5 * flick);
        ctx.fill(cx - 1, cy + h - fh - 1, cx + 1, cy + h, 0xFFFFF0B0); // 불꽃
    }
}
