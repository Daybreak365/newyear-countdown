package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 화면에 그려지는 카운트다운 패널.
 * 바닐라 툴팁 같은 각진 패널. 색/장식은 HudTheme 로 바뀐다 (기본: 크리스마스).
 */
public final class CountdownHud {
    private static final int PAD = 6;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private CountdownHud() {}

    /** 디버그 화면 상태 표시용. */
    public static String formatTarget() {
        ZonedDateTime t = Instant.ofEpochMilli(ClientCountdown.targetMs()).atZone(ClientCountdown.zone());
        return DATE_FMT.format(t);
    }

    public static void render(GuiGraphicsExtractor ctx, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        ClientConfig cfg = ClientConfig.get();
        if (!cfg.enabled || mc.player == null) return;

        long remMs = ClientCountdown.targetMs() - ClientCountdown.now();
        boolean finished = remMs <= 0;
        if (finished && -remMs > 10 * 60_000L) return; // 종료 후 10분이 지나면 숨김

        Font tr = mc.font;
        HudTheme th = cfg.theme;
        long remSec = Math.max(0, (remMs + 999) / 1000);
        long days = remSec / 86400, hours = remSec / 3600 % 24, mins = remSec / 60 % 60, secs = remSec % 60;

        // 앞쪽의 0 단위는 숨긴다 (0일, 0시간 ...). 초는 항상 표시.
        List<String> nums = new ArrayList<>();
        List<Component> units = new ArrayList<>();
        if (days > 0) { nums.add(String.valueOf(days)); units.add(Component.translatable("hud.newyearcountdown.days")); }
        if (days > 0 || hours > 0) { nums.add(String.format("%02d", hours)); units.add(Component.translatable("hud.newyearcountdown.hours")); }
        if (days > 0 || hours > 0 || mins > 0) { nums.add(String.format("%02d", mins)); units.add(Component.translatable("hud.newyearcountdown.minutes")); }
        nums.add(String.format("%02d", secs)); units.add(Component.translatable("hud.newyearcountdown.seconds"));

        final int numScale = 2;        // 정수 배율이라 픽셀이 뭉개지지 않는다
        final int segGap = 7;
        Component title = Component.literal("❄ ").append(Component.translatable(finished ? "hud.newyearcountdown.finished" : "hud.newyearcountdown.title"));
        Component debugText = Component.translatable("hud.newyearcountdown.debug");
        boolean debug = ClientCountdown.isDebug();

        // 폭 계산
        int rowW = 0;
        if (!finished) {
            for (int i = 0; i < nums.size(); i++) {
                rowW += tr.width(nums.get(i)) * numScale + 2 + tr.width(units.get(i));
                if (i < nums.size() - 1) rowW += segGap;
            }
        }
        int titleW = tr.width(title) + (debug ? tr.width(debugText) + 10 : 0);
        int w = Math.max(rowW, titleW) + PAD * 2;
        int h = finished ? PAD * 2 + 9 : PAD + 9 + 4 + 8 * numScale + PAD;

        float s = cfg.scale();
        int margin = 6;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        int pw = Math.round(w * s), ph = Math.round(h * s);
        int px = switch (cfg.position) {
            case TOP_LEFT -> margin;
            case TOP_RIGHT -> sw - pw - margin;
            default -> (sw - pw) / 2;
        };
        int py = cfg.position == ClientConfig.Position.BOTTOM_CENTER ? sh - ph - 40 : margin;

        var m = ctx.pose();
        m.pushMatrix();
        m.translate(px, py);
        m.scale(s, s);

        drawPanel(ctx, w, h, th);

        int y = PAD;
        ctx.text(tr, title, PAD, y, finished ? th.highlight : th.label);
        if (debug) {
            ctx.text(tr, debugText, w - PAD - tr.width(debugText), y, 0xFFFF6B6B);
        }

        if (!finished) {
            int x = PAD;
            int ny = y + 9 + 4;
            for (int i = 0; i < nums.size(); i++) {
                boolean last = i == nums.size() - 1;
                int color = th.number;
                if (last && remSec <= 10) { // 마지막 10초: 초 숫자가 깜빡
                    color = (System.currentTimeMillis() / 250 % 2 == 0) ? th.alert : th.number;
                }
                String num = nums.get(i);
                m.pushMatrix();
                m.translate(x, ny);
                m.scale(numScale, numScale);
                ctx.text(tr, num, 0, 0, color);
                m.popMatrix();
                x += tr.width(num) * numScale + 2;

                ctx.text(tr, units.get(i), x, ny + 8 * numScale - 8, th.label);
                x += tr.width(units.get(i)) + segGap;
            }
        }

        m.popMatrix();
    }

    /** 각진 패널 + 테마 테두리 + 테마별 윗면 장식. */
    private static void drawPanel(GuiGraphicsExtractor ctx, int w, int h, HudTheme th) {
        ctx.fill(0, 0, w, h, th.border);                // 바깥 테두리 (1px)
        ctx.fill(1, 1, w - 1, h - 1, th.bg);            // 본체
        ctx.fill(2, 2, w - 2, 3, th.inner);             // 안쪽 얇은 프레임
        ctx.fill(2, h - 3, w - 2, h - 2, th.inner);
        ctx.fill(2, 2, 3, h - 2, th.inner);
        ctx.fill(w - 3, 2, w - 2, h - 2, th.inner);

        switch (th.deco) {
            case SNOW -> {
                int snow = th.highlight;
                ctx.fill(0, 0, w, 1, snow);
                for (int x = 3; x < w - 3; x++) {
                    int k = x % 11;
                    if (k == 2 || k == 3 || k == 7) ctx.fill(x, 1, x + 1, 2, snow);
                    if (k == 3) ctx.fill(x, 2, x + 1, 3, snow);
                }
                ctx.fill(0, 0, 2, 2, snow);
                ctx.fill(w - 2, 0, w, 2, snow);
            }
            case CANDY -> {
                // 캔디케인 줄무늬 (위 2px, 아래 1px)
                for (int x = 0; x < w; x++) {
                    int c = ((x / 3) % 2 == 0) ? 0xFFE04B4B : 0xFFFFFFFF;
                    ctx.fill(x, 0, x + 1, 2, c);
                    ctx.fill(x, h - 1, x + 1, h, c);
                }
            }
            case GOLD_LINE -> {
                ctx.fill(0, 0, w, 2, th.highlight);
                ctx.fill(0, h - 1, w, h, th.highlight);
            }
            default -> { }
        }
    }
}
