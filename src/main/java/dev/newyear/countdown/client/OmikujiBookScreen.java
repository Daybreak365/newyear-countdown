package dev.newyear.countdown.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/** 받아 둔 오미쿠지를 다시 보는 보관함 (최근 것부터). */
public class OmikujiBookScreen extends Screen {
    private int index;
    private float scale = 1f, cx, cy;
    private final long openNanos = System.nanoTime();
    private long pageNanos = System.nanoTime();

    public OmikujiBookScreen() {
        super(Text.translatable("omikuji.newyearcountdown.ui.book", OmikujiBook.extraCount()));
    }

    @Override
    protected void init() {
        scale = MathHelper.clamp((height - 56) / (float) OmikujiPaper.H, 0.5f, 1.6f);
        cx = width / 2f;
        cy = (height - 26) / 2f;
        int by = Math.min((int) (cy + OmikujiPaper.H * scale / 2f) + 6, height - 24);
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> go(1)).dimensions(width / 2 - 82, by, 30, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("omikuji.newyearcountdown.ui.close"), b -> close())
                .dimensions(width / 2 - 46, by, 92, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> go(-1)).dimensions(width / 2 + 52, by, 30, 20).build());
    }

    private void go(int delta) {
        int n = OmikujiBook.size();
        if (n == 0) return;
        int next = MathHelper.clamp(index + delta, 0, n - 1);
        if (next != index) {
            index = next;
            pageNanos = System.nanoTime();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT) { go(1); return true; }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) { go(-1); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int n = OmikujiBook.size();
        if (n == 0) return;
        float t = MathHelper.clamp((System.nanoTime() - Math.max(openNanos, pageNanos)) / 1.0e9f / 0.25f, 0f, 1f);
        float ease = 1f - (1f - t) * (1f - t);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, cy + (1f - ease) * 14f, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        OmikujiPaper.draw(ctx, textRenderer, OmikujiBook.display(index), 0.3f + 0.7f * ease, OmikujiBook.extraNo(index));
        ctx.getMatrices().pop();
        Text label = index == 0 ? Text.translatable("omikuji.newyearcountdown.ui.main")
                : Text.translatable("omikuji.newyearcountdown.ui.extra_page", index, n - 1);
        ctx.drawCenteredTextWithShadow(textRenderer, label, width / 2, 8, index == 0 ? 0xFFF3D98A : 0xFFBFD3DA);
    }
}
