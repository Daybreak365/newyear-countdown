package dev.newyear.countdown.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import dev.newyear.countdown.ModSounds;

/**
 * 핫바 옆 오미쿠지 칸(처음 받기 전에는 없다가 종이가 날아와 닿는 순간 나타남)과,
 * 종이가 그 칸으로 "슈웅" 날아가는 연출.
 */
public final class OmikujiHud {
    private static final float FLIGHT_SECONDS = 0.95f;
    private static final float LAND_SECONDS = 0.5f;

    // 날아가는 중인 종이
    private static boolean flying;
    private static long flightStart;
    private static OmikujiBook.Entry flightEntry;
    private static int flightExtra;
    private static float fromX, fromY, fromScale;

    // 칸에 표시되는 개수/착지 효과
    private static int shown = -1;     // 칸이 보여 주는 개수 (착지 시점에 갱신)
    private static long landStart = -1;
    private static boolean firstAppear;

    private OmikujiHud() {}

    /** 접속/기록 동기화 때: 칸이 보여 주는 개수를 현재 기록에 맞춘다 (연출 없이). */
    public static void sync() {
        flying = false;
        landStart = -1;
        shown = OmikujiBook.size();
    }

    public static void startFlight(OmikujiBook.Entry e, int extraNo, float cx, float cy, float scale) {
        flying = true;
        flightStart = System.nanoTime();
        flightEntry = e;
        flightExtra = extraNo;
        fromX = cx;
        fromY = cy;
        fromScale = scale;
        play(ModSounds.OMIKUJI_WHOOSH, 1.0f, 0.9f);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch, volume));
    }

    /** 핫바 오른쪽의 칸 위치 (GUI 좌표). 반환: {x, y, size}. */
    public static int[] slotRect(int sw, int sh) {
        return new int[]{sw / 2 + 91 + 32, sh - 24, 22};
    }

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int[] r = slotRect(sw, sh);
        float tx = r[0] + r[2] / 2f, ty = r[1] + r[2] / 2f;

        if (flying) {
            float t = (System.nanoTime() - flightStart) / 1.0e9f / FLIGHT_SECONDS;
            if (t >= 1f) {
                flying = false;
                firstAppear = shown <= 0;
                shown = OmikujiBook.size();
                landStart = System.nanoTime();
                play(ModSounds.OMIKUJI_SUZU, 1.7f, 0.5f);
            } else {
                drawFlight(ctx, mc, t, tx, ty);
            }
        }

        if (shown > 0) drawSlot(ctx, mc, r, sw);
    }

    private static float[] pos(float t, float tx, float ty) {
        // 위쪽으로 크게 휘어지는 곡선 (2차 베지어), 점점 빨라진다
        float e = (float) Math.pow(t, 1.9);
        float cxp = fromX + (tx - fromX) * 0.25f;
        float cyp = Math.min(fromY, ty) - 90f;
        float u = 1f - e;
        float x = u * u * fromX + 2f * u * e * cxp + e * e * tx;
        float y = u * u * fromY + 2f * u * e * cyp + e * e * ty;
        return new float[]{x, y, e};
    }

    private static void drawFlight(DrawContext ctx, MinecraftClient mc, float t, float tx, float ty) {
        // 꼬리: 금빛 반짝임
        for (int i = 1; i <= 10; i++) {
            float tt = Math.max(0f, t - i * 0.022f);
            float[] p = pos(tt, tx, ty);
            float al = (1f - i / 11f) * 0.8f * Math.min(1f, t * 6f);
            int s = Math.max(1, Math.round(5f * (1f - i / 11f)));
            int col = ((int) (al * 255) << 24) | 0xF6D775;
            ctx.fill((int) p[0] - s, (int) p[1] - s, (int) p[0] + s, (int) p[1] + s, col);
        }
        float[] p = pos(t, tx, ty);
        float e = p[2];
        float scale = MathHelper.lerp((float) Math.pow(e, 0.6), fromScale, 0.11f);
        float spin = e * 540f;                  // 가속하며 팽글팽글
        float alpha = 1f - Math.max(0f, (e - 0.9f) / 0.1f);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(p[0], p[1], 0);
        ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(spin));
        ctx.getMatrices().scale(scale, scale, 1f);
        OmikujiPaper.draw(ctx, mc.textRenderer, flightEntry, alpha, flightExtra);
        ctx.getMatrices().pop();
    }

    private static void drawSlot(DrawContext ctx, MinecraftClient mc, int[] r, int sw) {
        float pulse = 0f, pop = 1f;
        if (landStart > 0) {
            float t = (System.nanoTime() - landStart) / 1.0e9f / LAND_SECONDS;
            if (t >= 1f) landStart = -1;
            else {
                pulse = (float) Math.sin(Math.PI * t);
                if (firstAppear) pop = Math.min(1f, t * 2.5f) + 0.25f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.6f));
            }
        }
        int x = r[0], y = r[1], s = r[2];
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x + s / 2f, y + s / 2f, 0);
        float sc = pop * (1f + 0.3f * pulse);
        ctx.getMatrices().scale(sc, sc, 1f);
        ctx.getMatrices().translate(-s / 2f, -s / 2f, 0);
        OmikujiSlotWidget.drawSlotFace(ctx, mc, 0, 0, s, false, pulse);
        ctx.getMatrices().pop();
    }
}
