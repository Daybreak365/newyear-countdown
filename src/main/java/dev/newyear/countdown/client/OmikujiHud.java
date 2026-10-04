package dev.newyear.countdown.client;

import com.mojang.math.Axis;
import dev.newyear.countdown.ModSounds;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;

/**
 * 받은 운세 종이가 화면 아래 핫바(인벤토리) 쪽으로 빨려 들어가는 연출.
 * 화면에 따로 남는 칸은 없고, 받아 둔 운세는 인벤토리 화면의 오미쿠지 칸에서만 볼 수 있다.
 */
public final class OmikujiHud {
    private static final float FLIGHT_SECONDS = 0.7f;
    private static final float LAND_SECONDS = 0.45f;

    private static boolean flying;
    private static long flightStart;
    private static OmikujiBook.Entry flightEntry;
    private static int flightExtra;
    private static float fromX, fromY, fromScale;
    private static long landStart = -1;

    private OmikujiHud() {}

    /** 접속/기록 동기화 때 진행 중인 연출을 끝낸다. */
    public static void sync() {
        flying = false;
        landStart = -1;
    }

    public static void startFlight(OmikujiBook.Entry e, int extraNo, float cx, float cy, float scale) {
        flying = true;
        flightStart = System.nanoTime();
        flightEntry = e;
        flightExtra = extraNo;
        fromX = cx;
        fromY = cy;
        fromScale = scale;
        landStart = -1;
        play(ModSounds.OMIKUJI_WHOOSH, 1.0f, 0.9f);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    public static void render(GuiGraphicsExtractor ctx, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!flying && landStart < 0) return;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        float tx = sw / 2f, ty = sh - 12f;    // 핫바 한가운데

        if (flying) {
            float t = (System.nanoTime() - flightStart) / 1.0e9f / FLIGHT_SECONDS;
            if (t >= 1f) {
                flying = false;
                landStart = System.nanoTime();
                play(ModSounds.OMIKUJI_SUZU, 1.7f, 0.5f);
                mc.player.sendOverlayMessage(Component.translatable("omikuji.newyearcountdown.ui.stored"));
            } else {
                drawFlight(ctx, mc, t, tx, ty);
            }
        }
        if (landStart > 0) {
            float t = (System.nanoTime() - landStart) / 1.0e9f / LAND_SECONDS;
            if (t >= 1f) landStart = -1;
            else drawLanding(ctx, t, tx, ty);
        }
    }

    /** 살짝 위로 떠올랐다가 아래로 가속하며 빨려 들어가는 곡선. */
    private static float[] pos(float t, float tx, float ty) {
        float e = t * t * (3f - 2f * t);
        e = e * e;
        float cxp = fromX + (tx - fromX) * 0.5f;
        float cyp = fromY - 40f;
        float u = 1f - e;
        float x = u * u * fromX + 2f * u * e * cxp + e * e * tx;
        float y = u * u * fromY + 2f * u * e * cyp + e * e * ty;
        return new float[]{x, y, e};
    }

    private static void drawFlight(GuiGraphicsExtractor ctx, Minecraft mc, float t, float tx, float ty) {
        float[] p = pos(t, tx, ty);
        float e = p[2];
        // 끝으로 갈수록 작아지고 가로로 접히듯 납작해진다
        float scale = Mth.lerp(e, fromScale, 0.04f);
        float squash = 1f - 0.6f * e;
        float alpha = 1f - Math.max(0f, (e - 0.85f) / 0.15f);
        ctx.pose().pushMatrix();
        ctx.pose().translate(p[0], p[1]);
        ctx.pose().rotate((float) Math.toRadians(e * 12f));
        ctx.pose().scale(scale * squash, scale);
        OmikujiPaper.draw(ctx, mc.font, flightEntry, alpha, flightExtra);
        ctx.pose().popMatrix();
    }

    /** 핫바에 닿는 순간 퍼지는 작은 금빛 고리. */
    private static void drawLanding(GuiGraphicsExtractor ctx, float t, float tx, float ty) {
        float r = 4f + 16f * t;
        int al = (int) ((1f - t) * 200);
        int col = (al << 24) | 0xF6D775;
        int x = (int) tx, y = (int) ty, ri = (int) r;
        ctx.fill(x - ri, y - ri, x + ri, y - ri + 1, col);
        ctx.fill(x - ri, y + ri - 1, x + ri, y + ri, col);
        ctx.fill(x - ri, y - ri, x - ri + 1, y + ri, col);
        ctx.fill(x + ri - 1, y - ri, x + ri, y + ri, col);
    }
}
