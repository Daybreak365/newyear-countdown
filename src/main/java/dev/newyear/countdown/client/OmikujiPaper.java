package dev.newyear.countdown.client;

import dev.newyear.countdown.omikuji.Fortunes;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.RotationAxis;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 오미쿠지 종이를 GUI 에 그린다. 호출하는 쪽이 행렬(이동/크기/회전)을 맞춰 두면, 종이 중심이 (0,0) 이다.
 * extraNo == 0 이면 "첫 뽑기(메인)": 붉은색·금빛의 정식 종이.
 * extraNo >= 1 이면 "추가 뽑기": 푸른 회색 톤의 종이에 '추가 뽑기 N회차' 표시.
 */
public final class OmikujiPaper {
    public static final int W = 148, H = 232;

    private static final int INK = 0xFF3A2C1A;
    /** 분야(소원/재물/연애)마다 운세별로 준비된 문구 수. lang: omikuji.newyearcountdown.line.<분야>.<운세>.<0~N-1> */
    public static final int LINE_VARIANTS = 5;

    private OmikujiPaper() {}

    public static int fortuneColor(int result) {
        return Fortunes.get(result).paper();
    }

    private static int a(int argb, float alpha) {
        int base = argb >>> 24;
        int out = Math.round(base * Math.max(0f, Math.min(1f, alpha)));
        return (out << 24) | (argb & 0xFFFFFF);
    }

    public static void draw(DrawContext ctx, TextRenderer tr, OmikujiBook.Entry e, float alpha, int extraNo) {
        boolean main = extraNo == 0;
        Fortunes.Fortune f = Fortunes.get(e.result);
        int fc = fortuneColor(e.result);
        int frame = main ? 0xFF9B2C2C : 0xFF3F5A66;       // 바깥 테두리/머리띠
        int accent = main ? 0xFFC9A24A : 0xFF9FB4BC;      // 안쪽 선/장식
        int paperTop = main ? 0xFFF9F1DE : 0xFFEEF0EA;
        int paperBot = main ? 0xFFEADBBA : 0xFFD9DED8;
        int headText = main ? 0xFFF3D98A : 0xFFDDE8EC;
        int x0 = -W / 2, y0 = -H / 2, x1 = W / 2, y1 = H / 2;

        // 그림자 + 종이
        ctx.fill(x0 + 4, y0 + 5, x1 + 4, y1 + 5, a(0x55000000, alpha));
        ctx.fillGradient(x0, y0, x1, y1, a(paperTop, alpha), a(paperBot, alpha));
        ctx.drawBorder(x0, y0, W, H, a(frame, alpha));
        ctx.drawBorder(x0 + 1, y0 + 1, W - 2, H - 2, a(frame, alpha));
        if (main) {
            ctx.drawBorder(x0 + 4, y0 + 4, W - 8, H - 8, a(accent, alpha));
        } else { // 점선 테두리
            for (int x = x0 + 5; x < x1 - 5; x += 6) {
                ctx.fill(x, y0 + 4, x + 3, y0 + 5, a(accent, alpha));
                ctx.fill(x, y1 - 5, x + 3, y1 - 4, a(accent, alpha));
            }
            for (int y = y0 + 5; y < y1 - 5; y += 6) {
                ctx.fill(x0 + 4, y, x0 + 5, y + 3, a(accent, alpha));
                ctx.fill(x1 - 5, y, x1 - 4, y + 3, a(accent, alpha));
            }
        }

        // 머리띠
        ctx.fill(x0 + 8, y0 + 8, x1 - 8, y0 + 32, a(frame, alpha));
        ctx.fill(x0 + 8, y0 + 31, x1 - 8, y0 + 32, a(accent, alpha));
        ctx.fill(x0 + 8, y0 + 8, x1 - 8, y0 + 9, a(accent, alpha));
        Text head = Text.translatable(main ? "omikuji.newyearcountdown.ui.head.main" : "omikuji.newyearcountdown.ui.head.extra");
        centered(ctx, tr, head, 0, y0 + 11, 1.4f, a(headText, alpha), true);

        // 구분 표시: 첫 뽑기 / 추가 뽑기 N회차
        Text tag = main ? Text.translatable("omikuji.newyearcountdown.ui.main")
                : Text.translatable("omikuji.newyearcountdown.ui.extra", extraNo);
        int tw = tr.getWidth(tag);
        int tagBg = main ? 0xFFC9A24A : 0xFF5F7C88;
        ctx.fill(-tw / 2 - 5, y0 + 36, tw / 2 + 5, y0 + 47, a(tagBg, alpha));
        ctx.drawText(tr, tag, -tw / 2, y0 + 38, a(main ? 0xFF3A2410 : 0xFFF4FAFC, alpha), false);

        // 번호
        centered(ctx, tr, Text.translatable("omikuji.newyearcountdown.ui.number", e.number), 0, y0 + 51, 0.85f, a(0xFF6B4F2A, alpha), false);

        // 운세 이름 + 한자 (엄..., 줴줴이야~! 는 한자 없음)
        Text name = Text.translatable("omikuji.newyearcountdown.name." + f.key()).formatted(Formatting.BOLD);
        int nw = tr.getWidth(name);
        float nameScale = Math.min(main ? 3.0f : 2.6f, (W - 20) / (float) Math.max(1, nw));   // 긴 이름은 종이 폭에 맞춰 줄인다
        centered(ctx, tr, name, 0, y0 + 62 + (int) ((3.0f - nameScale) * 4), nameScale, a(fc, alpha), main);
        if (f.kanji()) {
            Text kanji = Text.translatable("omikuji.newyearcountdown.kanji." + f.key());
            centered(ctx, tr, kanji, 0, y0 + 90, 1.5f, a(fc, alpha), false);
        }
        int ly = y0 + 102;
        ctx.fill(x0 + 14, ly, -24, ly + 1, a(accent, alpha));
        ctx.fill(24, ly, x1 - 14, ly + 1, a(accent, alpha));
        ctx.fill(-3, ly - 2, 3, ly + 4, a(fc, alpha));

        // 총평
        Text msg = Text.translatable("omikuji.newyearcountdown.msg." + f.key());
        int ty = y0 + 109;
        for (OrderedText line : tr.wrapLines(msg, W - 28)) {
            ctx.drawText(tr, line, -tr.getWidth(line) / 2, ty, a(INK, alpha), false);
            ty += 10;
        }

        // 분야별 풀이
        int ry = y0 + 142;
        for (String cat : new String[]{"wish", "money", "love"}) {
            Text catName = Text.translatable("omikuji.newyearcountdown.cat." + cat);
            ctx.fill(x0 + 10, ry, x0 + 38, ry + 12, a(frame, alpha));
            int cw = tr.getWidth(catName);
            ctx.drawText(tr, catName, x0 + 24 - cw / 2, ry + 2, a(0xFFFBEFD0, alpha), false);
            Text line = Text.translatable("omikuji.newyearcountdown.line." + cat + "." + f.key() + "." + variant(e, cat));
            int ly2 = ry + 1;
            for (OrderedText l : tr.wrapLines(line, W - 56)) {
                ctx.drawText(tr, l, x0 + 44, ly2, a(INK, alpha), false);
                ly2 += 9;
            }
            ry += 25;
        }

        // 날짜 + 인장
        String date = new SimpleDateFormat("yyyy.MM.dd").format(new Date(e.time));
        ctx.drawText(tr, date, x0 + 10, y1 - 15, a(0xFF8A7656, alpha), false);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x1 - 24, y1 - 22, 0);
        ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-8f));
        if (main) {
            ctx.fill(-10, -10, 10, 10, a(0xFFB33030, alpha));
            ctx.drawBorder(-8, -8, 16, 16, a(0xFFF3D98A, alpha));
            centered(ctx, tr, Text.literal("福"), 0, -6, 1.1f, a(0xFFF8E8C0, alpha), false);
        } else { // 추가 뽑기는 작은 둥근 느낌의 청회색 도장
            ctx.fill(-8, -8, 8, 8, a(0xFF4F6F7C, alpha));
            ctx.drawBorder(-7, -7, 14, 14, a(0xFFDDE8EC, alpha));
            centered(ctx, tr, Text.literal("追"), 0, -5, 0.95f, a(0xFFEAF2F4, alpha), false);
        }
        ctx.getMatrices().pop();
    }

    /** 종이마다 고정된 무작위 문구 번호: 같은 종이는 언제 다시 봐도 같은 문구가 나온다. */
    private static int variant(OmikujiBook.Entry e, String cat) {
        long h = e.time * 31L + e.number * 1_000_003L + cat.hashCode() * 7919L;
        h ^= (h >>> 17);
        h *= 0x9E3779B97F4A7C15L;
        return (int) Math.floorMod(h >>> 33, (long) LINE_VARIANTS);
    }

    private static void centered(DrawContext ctx, TextRenderer tr, Text t, int cx, int y, float scale, int color, boolean shadow) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, y, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        ctx.drawText(tr, t, -tr.getWidth(t) / 2, 0, color, shadow);
        ctx.getMatrices().pop();
    }
}
