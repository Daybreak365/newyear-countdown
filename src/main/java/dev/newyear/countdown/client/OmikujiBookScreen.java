package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** 받아 둔 오미쿠지를 다시 보는 보관함. 맨 왼쪽이 첫 뽑기이고 오른쪽으로 넘길수록 나중에 뽑은 종이. */
public class OmikujiBookScreen extends Screen {
    private int index;
    private float scale = 1f, cx, cy;
    private final long openNanos = System.nanoTime();
    private long pageNanos = System.nanoTime();
    private Button prev, next;

    public OmikujiBookScreen() {
        super(Component.translatable("omikuji.newyearcountdown.ui.book"));
    }

    @Override
    protected void init() {
        scale = Mth.clamp((height - 56) / (float) OmikujiPaper.H, 0.5f, 1.6f);
        cx = width / 2f;
        cy = (height - 26) / 2f;
        int by = Math.min((int) (cy + OmikujiPaper.H * scale / 2f) + 6, height - 24);
        prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> go(-1)).bounds(width / 2 - 82, by, 30, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("omikuji.newyearcountdown.ui.close"), b -> onClose())
                .bounds(width / 2 - 46, by, 92, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> go(1)).bounds(width / 2 + 52, by, 30, 20).build());
        updateArrows();
    }

    /** 더 넘길 수 없는 쪽 화살표는 비활성화. */
    private void updateArrows() {
        int n = OmikujiBook.size();
        if (prev != null) prev.active = index > 0;
        if (next != null) next.active = index < n - 1;
    }

    private void go(int delta) {
        int n = OmikujiBook.size();
        if (n == 0) return;
        int target = Mth.clamp(index + delta, 0, n - 1);
        if (target != index) {
            index = target;
            pageNanos = System.nanoTime();
        }
        updateArrows();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_LEFT) { go(-1); return true; }
        if (keyCode == InputConstants.KEY_RIGHT) { go(1); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        int n = OmikujiBook.size();
        if (n == 0) return;
        float t = Mth.clamp((System.nanoTime() - Math.max(openNanos, pageNanos)) / 1.0e9f / 0.25f, 0f, 1f);
        float ease = 1f - (1f - t) * (1f - t);
        ctx.pose().pushMatrix();
        ctx.pose().translate(cx, cy + (1f - ease) * 14f);
        ctx.pose().scale(scale, scale);
        OmikujiPaper.draw(ctx, font, OmikujiBook.display(index), 0.3f + 0.7f * ease, OmikujiBook.extraNo(index));
        ctx.pose().popMatrix();
        Component label = index == 0 ? Component.translatable("omikuji.newyearcountdown.ui.main")
                : Component.translatable("omikuji.newyearcountdown.ui.extra_page", index, n - 1);
        ctx.centeredText(font, label, width / 2, 8, index == 0 ? 0xFFF3D98A : 0xFFBFD3DA);
    }
}
