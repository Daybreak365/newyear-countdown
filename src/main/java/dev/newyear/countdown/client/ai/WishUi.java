package dev.newyear.countdown.client.ai;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Map;

/**
 * 소원 UI 디자인 시스템. AI 는 내용과 구조만 정하고, 모양은 전부 여기서 나온다.
 * 방향: 2027 새해 카운트다운의 밤하늘 — 짙은 남색 바탕, 절제된 금빛 테두리, 연등 같은 따뜻한 강조색,
 * 픽셀 단위로 깎은 모서리, 작은 별 장식, 짧고 부드러운 애니메이션.
 */
public final class WishUi {
    private WishUi() {}

    // ---------------------------------------------------------------- 색 토큰
    public static final int BG_TOP = 0xF0141830;
    public static final int BG_BOTTOM = 0xF01C1730;
    public static final int BG_SOFT = 0xC8151A2E;
    public static final int EDGE_DARK = 0xFF080A14;
    public static final int EDGE_GOLD = 0xFFB8975A;
    public static final int EDGE_GOLD_DIM = 0xFF6E5A3A;
    public static final int INNER_LINE = 0x33F3D98A;
    public static final int TEXT = 0xFFEDE6D6;
    public static final int TEXT_DIM = 0xFF9D97B5;
    public static final int TEXT_FAINT = 0xFF6E6A86;
    public static final int GOLD = 0xFFF3D98A;
    public static final int STAR = 0xFFFFE9A8;
    public static final int LANTERN = 0xFFF0A65A;
    public static final int SPIRIT_BUBBLE = 0xD0222845;
    public static final int PLAYER_BUBBLE = 0xD03A2A2E;
    public static final int SUCCESS = 0xFF8FD1A0;
    public static final int WARNING = 0xFFE6A06A;
    public static final int DANGER = 0xFFE07A7A;
    public static final int MYSTIC = 0xFFB49AF0;
    public static final int INFO = 0xFF8EC5F0;
    public static final int CURSE = 0xFFC27AD6;
    public static final int BAR_BG = 0xFF0C0F1E;

    public static int kindColor(String kind) {
        if (kind == null) return GOLD;
        return switch (kind) {
            case "success", "blessing" -> SUCCESS;
            case "warning" -> WARNING;
            case "danger" -> DANGER;
            case "mystic" -> MYSTIC;
            case "curse" -> CURSE;
            case "info" -> INFO;
            case "timer" -> 0xFF7FDCE6;
            default -> GOLD;
        };
    }

    public static String kindGlyph(String kind) {
        if (kind == null) return "✦";
        return switch (kind) {
            case "curse" -> "☾";
            case "blessing" -> "❀";
            case "timer" -> "⌛";
            case "info" -> "✧";
            default -> "✦";
        };
    }

    // ---------------------------------------------------------------- 시간·애니메이션

    public static float now() {
        return (System.nanoTime() / 1_000_000L) / 1000f;
    }

    public static float easeOut(float t) {
        t = clamp01(t);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    public static float clamp01(float t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    public static int alpha(int argb, float a) {
        int base = (argb >>> 24) & 0xFF;
        int na = Math.round(base * clamp01(a));
        // 글자는 알파가 너무 낮으면 그려지지 않으므로 최소값을 둔다
        return (Math.max(na, 4) << 24) | (argb & 0xFFFFFF);
    }

    public static int textAlpha(int argb, float a) {
        int na = Math.round(255 * clamp01(a));
        return (Math.max(na, 0x10) << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = clamp01(t);
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    // ---------------------------------------------------------------- 패널

    /** 모서리를 한 픽셀 깎은 이중 테두리 패널. */
    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float a) {
        int w = x1 - x0, h = y1 - y0;
        if (w < 6 || h < 6) return;
        // 바깥 어두운 테두리 (모서리 깎기)
        g.fill(x0 + 1, y0, x1 - 1, y0 + 1, alpha(EDGE_DARK, a));
        g.fill(x0 + 1, y1 - 1, x1 - 1, y1, alpha(EDGE_DARK, a));
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, alpha(EDGE_DARK, a));
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, alpha(EDGE_DARK, a));
        // 바탕
        g.fillGradient(x0 + 1, y0 + 1, x1 - 1, y1 - 1, alpha(BG_TOP, a), alpha(BG_BOTTOM, a));
        // 금빛 테두리 (위는 밝게, 아래는 어둡게)
        g.fill(x0 + 2, y0 + 1, x1 - 2, y0 + 2, alpha(EDGE_GOLD, a));
        g.fill(x0 + 2, y1 - 2, x1 - 2, y1 - 1, alpha(EDGE_GOLD_DIM, a));
        g.fill(x0 + 1, y0 + 2, x0 + 2, y1 - 2, alpha(mix(EDGE_GOLD, EDGE_GOLD_DIM, 0.4f), a));
        g.fill(x1 - 2, y0 + 2, x1 - 1, y1 - 2, alpha(mix(EDGE_GOLD, EDGE_GOLD_DIM, 0.6f), a));
        // 안쪽 가는 선
        g.outline(x0 + 3, y0 + 3, w - 6, h - 6, alpha(INNER_LINE, a));
        // 모서리 별 장식
        star(g, x0 + 3, y0 + 3, alpha(STAR, a * 0.9f));
        star(g, x1 - 4, y0 + 3, alpha(STAR, a * 0.9f));
    }

    /** 가벼운 패널 (HUD 용, 장식 적음). */
    public static void softPanel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int accent, float a) {
        g.fill(x0 + 1, y0, x1 - 1, y1, alpha(BG_SOFT, a));
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, alpha(BG_SOFT, a));
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, alpha(BG_SOFT, a));
        g.fill(x0, y0 + 1, x0 + 2, y1 - 1, alpha(accent, a * 0.95f));
        g.fill(x0 + 2, y0, x1 - 1, y0 + 1, alpha(INNER_LINE, a));
    }

    /** 3x3 픽셀 별. */
    public static void star(GuiGraphicsExtractor g, int cx, int cy, int color) {
        g.fill(cx, cy - 1, cx + 1, cy + 2, color);
        g.fill(cx - 1, cy, cx + 2, cy + 1, color);
    }

    /** 반짝이는 큰 별 (5x5 십자 + 가운데). */
    public static void twinkle(GuiGraphicsExtractor g, int cx, int cy, float phase, int color) {
        float tw = 0.5f + 0.5f * (float) Math.sin(phase);
        int c = alpha(color, 0.35f + 0.65f * tw);
        g.fill(cx, cy - 2, cx + 1, cy + 3, c);
        g.fill(cx - 2, cy, cx + 3, cy + 1, c);
        if (tw > 0.6f) g.fill(cx, cy, cx + 1, cy + 1, 0xFFFFFFFF);
    }

    /** 제목 리본: 금빛 글자 + 양옆 장식선. */
    public static void title(GuiGraphicsExtractor g, Font f, String text, int cx, int y, float a) {
        int w = f.width(text);
        g.centeredText(f, text, cx, y, textAlpha(GOLD, a));
        int lineY = y + 4;
        g.fill(cx - w / 2 - 30, lineY, cx - w / 2 - 6, lineY + 1, alpha(EDGE_GOLD_DIM, a));
        g.fill(cx + w / 2 + 6, lineY, cx + w / 2 + 30, lineY + 1, alpha(EDGE_GOLD_DIM, a));
        star(g, cx - w / 2 - 33, lineY, alpha(STAR, a));
        star(g, cx + w / 2 + 32, lineY, alpha(STAR, a));
    }

    // ---------------------------------------------------------------- 진행·칩·버튼

    /** 픽셀 진행 막대. shown 은 0..1. */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float shown, int color, float a) {
        g.fill(x, y, x + w, y + h, alpha(BAR_BG, a));
        int fw = Math.round((w - 2) * clamp01(shown));
        if (fw > 0) {
            g.fillGradient(x + 1, y + 1, x + 1 + fw, y + h - 1, alpha(mix(color, 0xFFFFFFFF, 0.25f), a), alpha(color, a));
            // 끝 반짝임
            g.fill(x + fw, y + 1, x + 1 + fw, y + h - 1, alpha(0xFFFFFFFF, a * 0.7f));
        }
        g.outline(x, y, w, h, alpha(EDGE_DARK, a));
    }

    public static int chip(GuiGraphicsExtractor g, Font f, String text, int x, int y, int color, float a) {
        int w = f.width(text) + 8;
        g.fill(x, y, x + w, y + 11, alpha(mix(color, 0xFF000000, 0.72f), a));
        g.outline(x, y, w, 11, alpha(color, a));
        g.text(f, text, x + 4, y + 2, textAlpha(color, a), false);
        return w;
    }

    /** 커스텀 버튼 (호버 시 밝아짐). 반환: 마우스가 위에 있는지. */
    public static boolean button(GuiGraphicsExtractor g, Font f, String text, int x, int y, int w, int h, int mx, int my, boolean enabled, int accent, float a) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = !enabled ? 0xC0181A28 : hover ? mix(0xE0262B48, accent, 0.25f) : 0xE01E2238;
        g.fill(x + 1, y, x + w - 1, y + h, alpha(bg, a));
        g.fill(x, y + 1, x + 1, y + h - 1, alpha(bg, a));
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, alpha(bg, a));
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, alpha(hover ? accent : EDGE_GOLD_DIM, a));
        if (hover) g.fill(x + 1, y, x + w - 1, y + 1, alpha(accent, a * 0.6f));
        int tc = !enabled ? TEXT_FAINT : hover ? 0xFFFFFFFF : TEXT;
        g.centeredText(f, text, x + w / 2, y + (h - 8) / 2, textAlpha(tc, a));
        return hover;
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------- 아이콘

    /** AI 가 만든 픽셀 아이콘을 size 픽셀 정사각형 안에 그린다. */
    public static void pixelIcon(GuiGraphicsExtractor g, String[] rows, Map<Character, Integer> palette, int x, int y, int size, int fallback, float a) {
        if (rows == null || rows.length == 0) {
            star(g, x + size / 2, y + size / 2, alpha(fallback, a));
            g.fill(x + size / 2 - 2, y + size / 2, x + size / 2 + 3, y + size / 2 + 1, alpha(fallback, a * 0.6f));
            return;
        }
        int n = Math.max(rows.length, 1);
        for (String r : rows) n = Math.max(n, r.length());
        int px = Math.max(1, size / n);
        int ox = x + (size - px * n) / 2, oy = y + (size - px * rows.length) / 2;
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                if (ch == '.' || ch == ' ') continue;
                Integer col = palette.get(ch);
                int color = col != null ? col : fallback;
                g.fill(ox + c * px, oy + r * px, ox + (c + 1) * px, oy + (r + 1) * px, alpha(color, a));
            }
        }
    }

    // ---------------------------------------------------------------- 텍스트

    public static List<FormattedCharSequence> wrap(Font f, String text, int width) {
        return f.split(Component.literal(text == null ? "" : text), Math.max(20, width));
    }

    public static String time(long ms) {
        if (ms < 0) return "";
        long s = (ms + 999) / 1000;
        if (s >= 3600) return String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    public static String stateKo(String state) {
        if (state == null) return "";
        return switch (state) {
            case "CREATED", "ANALYZING" -> "살피는 중";
            case "NEGOTIATING" -> "대화 중";
            case "AWAITING_ACCEPTANCE" -> "계약 대기";
            case "AWAITING_APPROVAL" -> "별들의 허락 대기";
            case "PREPARING" -> "준비 중";
            case "VALIDATING" -> "마법 엮는 중";
            case "ACTIVE" -> "진행 중";
            case "COMPLETED" -> "이루어짐";
            case "FAILED" -> "이루지 못함";
            case "REJECTED" -> "거절됨";
            case "CANCELLED" -> "취소됨";
            default -> state;
        };
    }

    public static int stateColor(String state) {
        if (state == null) return TEXT_DIM;
        return switch (state) {
            case "COMPLETED" -> SUCCESS;
            case "FAILED", "REJECTED" -> DANGER;
            case "CANCELLED" -> TEXT_FAINT;
            case "ACTIVE" -> GOLD;
            case "AWAITING_ACCEPTANCE", "AWAITING_APPROVAL" -> WARNING;
            default -> MYSTIC;
        };
    }
}
