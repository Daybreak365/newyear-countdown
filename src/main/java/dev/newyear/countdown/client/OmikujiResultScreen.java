package dev.newyear.countdown.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * 뽑은 운세를 보여 주는 화면. 종이가 펼쳐지고 "받기" 를 누르거나 화면을 닫으면
 * 종이가 인벤토리 옆 오미쿠지 칸으로 날아가 보관함에 들어간다.
 */
public class OmikujiResultScreen extends Screen {
    private final OmikujiBook.Entry entry;
    private final int extraNo; // 0 = 첫 뽑기, 1.. = 추가 뽑기 회차
    private final long openNanos = System.nanoTime();
    private boolean collected;
    private float scale = 1f, cx, cy;

    public OmikujiResultScreen(OmikujiBook.Entry entry, int extraNo) {
        super(Text.translatable("omikuji.newyearcountdown.ui.title"));
        this.entry = entry;
        this.extraNo = extraNo;
    }

    @Override
    protected void init() {
        scale = MathHelper.clamp((height - 56) / (float) OmikujiPaper.H, 0.5f, 1.6f);
        cx = width / 2f;
        cy = (height - 26) / 2f;
        int by = (int) (cy + OmikujiPaper.H * scale / 2f) + 6;
        addDrawableChild(ButtonWidget.builder(Text.translatable("omikuji.newyearcountdown.ui.collect"), b -> close())
                .dimensions(width / 2 - 50, Math.min(by, height - 24), 100, 20).build());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        float t = MathHelper.clamp((System.nanoTime() - openNanos) / 1.0e9f / 0.45f, 0f, 1f);
        float ease = 1f - (1f - t) * (1f - t) * (1f - t);
        // 위에서 아래로 펼쳐지는 종이: 위쪽을 고정하고 세로로 늘린다
        float top = cy - OmikujiPaper.H * scale / 2f;
        float sy = scale * (0.06f + 0.94f * ease);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, top + OmikujiPaper.H * sy / 2f, 0);
        ctx.getMatrices().scale(scale, sy, 1f);
        OmikujiPaper.draw(ctx, textRenderer, entry, 0.4f + 0.6f * ease, extraNo);
        ctx.getMatrices().pop();
    }

    @Override
    public void close() {
        if (!collected) {
            collected = true;
            OmikujiHud.startFlight(entry, extraNo, cx, cy, scale);
        }
        super.close();
    }
}
