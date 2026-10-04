package dev.newyear.countdown.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 뽑은 운세를 보여 주는 화면. 종이가 펼쳐지고 "받기" 를 누르거나 화면을 닫으면
 * 종이가 화면 아래 인벤토리(핫바) 쪽으로 빨려 들어간다. 다시 보려면 인벤토리의 오미쿠지 칸을 누른다.
 */
public class OmikujiResultScreen extends Screen {
    private final OmikujiBook.Entry entry;
    private final int extraNo; // 0 = 첫 뽑기, 1.. = 추가 뽑기 회차
    private final long openNanos = System.nanoTime();
    private boolean collected;
    private float scale = 1f, cx, cy;

    public OmikujiResultScreen(OmikujiBook.Entry entry, int extraNo) {
        super(Component.translatable("omikuji.newyearcountdown.ui.title"));
        this.entry = entry;
        this.extraNo = extraNo;
    }

    @Override
    protected void init() {
        scale = Mth.clamp((height - 56) / (float) OmikujiPaper.H, 0.5f, 1.6f);
        cx = width / 2f;
        cy = (height - 26) / 2f;
        int by = (int) (cy + OmikujiPaper.H * scale / 2f) + 6;
        addRenderableWidget(Button.builder(Component.translatable("omikuji.newyearcountdown.ui.collect"), b -> onClose())
                .bounds(width / 2 - 50, Math.min(by, height - 24), 100, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        float t = Mth.clamp((System.nanoTime() - openNanos) / 1.0e9f / 0.45f, 0f, 1f);
        float ease = 1f - (1f - t) * (1f - t) * (1f - t);
        // 위에서 아래로 펼쳐지는 종이: 위쪽을 고정하고 세로로 늘린다
        float top = cy - OmikujiPaper.H * scale / 2f;
        float sy = scale * (0.06f + 0.94f * ease);
        ctx.pose().pushMatrix();
        ctx.pose().translate(cx, top + OmikujiPaper.H * sy / 2f);
        ctx.pose().scale(scale, sy);
        OmikujiPaper.draw(ctx, font, entry, 0.4f + 0.6f * ease, extraNo);
        ctx.pose().popMatrix();
    }

    @Override
    public void onClose() {
        if (!collected) {
            collected = true;
            OmikujiHud.startFlight(entry, extraNo, cx, cy, scale);
        }
        super.onClose();
    }
}
