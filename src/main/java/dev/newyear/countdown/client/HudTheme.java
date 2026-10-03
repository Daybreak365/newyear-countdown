package dev.newyear.countdown.client;

/** HUD 테마 목록. 설정 화면에서 순서대로 바뀐다. 기본값은 CHRISTMAS. */
public enum HudTheme {
    //            배경        테두리      안쪽 프레임  제목/단위   강조(종료문구) 숫자        경고        글리프     장식
    CHRISTMAS(0xC0102A1C, 0xFFB3262E, 0x55E8C26A, 0xFFF2D58A, 0xFFFFF4D6, 0xFFFFFFFF, 0xFFFF6B6B, "★", Deco.CANDY),
    WINTER   (0xC00B1220, 0xFF7FB6D6, 0x55BFE6F7, 0xFFBFE6F7, 0xFFF4FBFF, 0xFFFFFFFF, 0xFFFF8A8A, "❄", Deco.SNOW),
    CLASSIC  (0xE0100010, 0xFF3A1A78, 0x40FFFFFF, 0xFFAAAAAA, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFF5555, "",       Deco.NONE),
    GOLD     (0xD0141008, 0xFFD4A63A, 0x55FFE08A, 0xFFE9C46A, 0xFFFFE08A, 0xFFFFFFFF, 0xFFFF7A5A, "✦", Deco.GOLD_LINE);

    public enum Deco { NONE, SNOW, CANDY, GOLD_LINE }

    public final int bg, border, inner, label, highlight, number, alert;
    public final String glyph;
    public final Deco deco;

    HudTheme(int bg, int border, int inner, int label, int highlight, int number, int alert, String glyph, Deco deco) {
        this.bg = bg;
        this.border = border;
        this.inner = inner;
        this.label = label;
        this.highlight = highlight;
        this.number = number;
        this.alert = alert;
        this.glyph = glyph;
        this.deco = deco;
    }

    public HudTheme next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return "theme.newyearcountdown." + name().toLowerCase();
    }
}
