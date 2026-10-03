package dev.newyear.countdown.client;

import dev.newyear.countdown.NewYearCountdown;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * 소원 연등(풍등) 3D 모델. 로컬 좌표: 원점은 아래 테두리 중심, +Y 위쪽, 높이 약 1.45.
 * 구성: 팔각 뿔대 종이 몸통(반투명 + 안쪽 불빛 번짐) → 어깨 → 꼭대기 매듭, 아래 대나무 테두리와 십자 살,
 * 연료 심지 위의 일렁이는 불꽃. 모두 스스로 빛난다.
 */
public final class WishLanternModel {
    public static final Identifier PAPER = Identifier.of(NewYearCountdown.MOD_ID, "textures/entity/lantern_paper.png");
    public static final Identifier GLOW = Identifier.of(NewYearCountdown.MOD_ID, "textures/entity/lantern_glow.png");
    private static final Identifier T_BAMBOO = Identifier.ofVanilla("textures/block/bamboo_planks.png");
    private static final Identifier T_DARK = Identifier.ofVanilla("textures/block/dark_oak_planks.png");
    private static final Identifier T_GOLD = Identifier.ofVanilla("textures/block/gold_block.png");
    private static final Identifier T_ORANGE = Identifier.ofVanilla("textures/block/orange_concrete.png");
    private static final Identifier T_YELLOW = Identifier.ofVanilla("textures/block/yellow_concrete.png");
    private static final Identifier T_WHITE = Identifier.ofVanilla("textures/block/white_concrete.png");

    public static final float BODY_BOTTOM_Y = 0.15f, BODY_TOP_Y = 1.20f, SHOULDER_TOP_Y = 1.30f;
    public static final float R_BOTTOM = 0.46f, R_TOP = 0.44f, R_CAP = 0.30f; // 사진 속 풍등처럼 거의 곧은 통
    public static final int SIDES = 8;
    private static final int FULL = 0xF000F0;
    private static final float OFFSET = (float) (Math.PI / SIDES); // 면의 한가운데가 0°, 90°… 가 되도록 꼭짓점을 반 칸 돌린다

    private WishLanternModel() {}

    /** 면(패널) 중앙의 반지름(= 중심에서 면까지 거리, apothem) y 높이에서. */
    public static float apothemAt(float y) {
        float r = y <= BODY_TOP_Y
                ? MathHelper.lerp((y - BODY_BOTTOM_Y) / (BODY_TOP_Y - BODY_BOTTOM_Y), R_BOTTOM, R_TOP)
                : MathHelper.lerp((y - BODY_TOP_Y) / (SHOULDER_TOP_Y - BODY_TOP_Y), R_TOP, R_CAP);
        return r * (float) Math.cos(Math.PI / SIDES);
    }

    /**
     * @param rgb      종이 색 (0xRRGGBB)
     * @param time     틱(소수 포함): 불꽃 일렁임용
     * @param seed     연등마다 다른 값(0~1)
     */
    public static void draw(VertexConsumerProvider providers, MatrixStack m, int rgb, float time, float seed) {
        BellModel.Painter p = new BellModel.Painter(providers, m, FULL, 0);
        float flick = 0.88f + 0.12f * MathHelper.sin(time * 1.9f + seed * 9f) + 0.06f * MathHelper.sin(time * 3.7f + seed * 3f);

        drawFrame(p);
        drawFlame(p, flick, time, seed);

        // ---- 종이 ----
        p.setTranslucent(true);
        p.use(PAPER, 0xFFFFFFFF);
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        int glowBottom = argb(0xF4, Math.min(255, r + 25), Math.min(255, g + 55), Math.min(255, b + 85)); // 불에 가까운 아래는 거의 하얗게 환하게
        int paperTop = argb(0xE0, (int) (r * 0.95f), (int) (g * 0.82f), (int) (b * 0.66f));  // 위로 갈수록 호박빛
        int glowMul = argb(0xF0, (int) Math.min(255, 255 * flick), (int) Math.min(255, 255 * flick), (int) Math.min(255, 255 * flick));
        glowBottom = mulColor(glowBottom, glowMul);

        // 바깥 종이: 몸통
        ring(p, R_BOTTOM, BODY_BOTTOM_Y, R_TOP, BODY_TOP_Y, glowBottom, paperTop, 1.0f);
        // 어깨(위로 좁아짐) + 꼭대기
        ring(p, R_TOP, BODY_TOP_Y, R_CAP, SHOULDER_TOP_Y, paperTop, argb(0xE6, (int) (r * 0.88f), (int) (g * 0.7f), (int) (b * 0.52f)), 0.25f);
        cap(p, R_CAP, SHOULDER_TOP_Y, argb(0xEA, (int) (r * 0.86f), (int) (g * 0.68f), (int) (b * 0.5f)));
        // 안쪽 불빛 번짐: 조금 작은 뿔대를 밝은 불꽃색으로 한 겹 더
        int inner = argb((int) (190 * flick), 255, 226, 150);   // 안쪽 불빛이 종이에 환하게 비친다
        int innerTop = argb(60, 255, 170, 80);
        ring(p, R_BOTTOM * 0.9f, BODY_BOTTOM_Y + 0.02f, R_TOP * 0.9f, BODY_TOP_Y - 0.08f, inner, innerTop, 1.0f);
        p.setTranslucent(false);

        // 꼭대기 매듭과 술
        p.use(T_GOLD, tintOf(0.95f, 0.8f, 0.45f));
        p.box(-0.05f, SHOULDER_TOP_Y, -0.05f, 0.05f, SHOULDER_TOP_Y + 0.07f, 0.05f);
        p.use(T_DARK, 0xFFFFFFFF);
        p.box(-0.012f, SHOULDER_TOP_Y + 0.07f, -0.012f, 0.012f, SHOULDER_TOP_Y + 0.14f, 0.012f);
    }

    private static void drawFrame(BellModel.Painter p) {
        // 아래 대나무 테두리 (팔각)
        float side = (float) (2 * R_BOTTOM * Math.sin(Math.PI / SIDES));
        float ap = R_BOTTOM * (float) Math.cos(Math.PI / SIDES);
        p.use(T_BAMBOO, 0xFFFFFFFF);
        for (int i = 0; i < SIDES; i++) {
            p.m.push();
            p.m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * 360f / SIDES));
            p.box(-side / 2f - 0.01f, 0.10f, ap - 0.02f, side / 2f + 0.01f, 0.17f, ap + 0.02f);
            p.m.pop();
        }
        // 십자 살 + 연료 받침
        p.box(-ap, 0.11f, -0.012f, ap, 0.14f, 0.012f);
        p.box(-0.012f, 0.11f, -ap, 0.012f, 0.14f, ap);
        p.use(T_DARK, 0xFFFFFFFF);
        p.box(-0.075f, 0.13f, -0.075f, 0.075f, 0.185f, 0.075f);                        // 심지/연료 덩이
        // 위쪽 둥근 보강 살 (몸통 안쪽 반원 느낌)
        p.use(T_BAMBOO, 0xFFFFFFFF);
        float ap2 = R_TOP * (float) Math.cos(Math.PI / SIDES);
        float side2 = (float) (2 * R_TOP * Math.sin(Math.PI / SIDES));
        for (int i = 0; i < SIDES; i++) {
            p.m.push();
            p.m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * 360f / SIDES));
            p.box(-side2 / 2f, BODY_TOP_Y - 0.015f, ap2 - 0.012f, side2 / 2f, BODY_TOP_Y + 0.015f, ap2 + 0.012f);
            p.m.pop();
        }
    }

    private static void drawFlame(BellModel.Painter p, float f, float time, float seed) {
        p.m.push();
        p.m.translate(0, 0.185f, 0);
        // 일렁임: 위로 갈수록 살짝 흔들리고 키가 변한다
        float sway = 0.015f * MathHelper.sin(time * 2.3f + seed * 6f);
        p.m.multiply(RotationAxis.POSITIVE_Z.rotation(sway));
        p.m.scale(1f, f, 1f);
        p.use(T_ORANGE, 0xFFFFFFFF);
        p.lightOverride = FULL;
        p.box(-0.055f, 0f, -0.055f, 0.055f, 0.12f, 0.055f);
        p.use(T_YELLOW, 0xFFFFFFFF);
        p.box(-0.04f, 0.04f, -0.04f, 0.04f, 0.21f, 0.04f);
        p.use(T_WHITE, 0xFFFFF4C8);
        p.box(-0.022f, 0.08f, -0.022f, 0.022f, 0.26f, 0.022f);
        p.use(T_YELLOW, 0xFFFFFFFF);
        p.box(-0.012f, 0.24f, -0.012f, 0.012f, 0.32f, 0.012f);
        p.lightOverride = -1;
        p.m.pop();
    }

    // ---------------------------------------------------------------- 기하 도우미

    private static int argb(int a, int r, int g, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (Math.max(0, Math.min(255, r)) << 16)
                | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
    }

    private static int tintOf(float r, float g, float b) {
        return argb(255, (int) (r * 255), (int) (g * 255), (int) (b * 255));
    }

    private static int mulColor(int a, int b) {
        int al = (a >>> 24) * (b >>> 24) / 255;
        int r = (a >> 16 & 255) * (b >> 16 & 255) / 255;
        int g = (a >> 8 & 255) * (b >> 8 & 255) / 255;
        int bl = (a & 255) * (b & 255) / 255;
        return argb(al, r, g, bl);
    }

    private static float cx(int i, float r) { return (float) Math.sin(i * 2 * Math.PI / SIDES - OFFSET) * r; }
    private static float cz(int i, float r) { return (float) Math.cos(i * 2 * Math.PI / SIDES - OFFSET) * r; }

    /** 팔각 뿔대의 옆면. (bottom → top 으로 색이 변한다; vTile 은 세로 텍스처 반복 비율) */
    private static void ring(BellModel.Painter p, float rBottom, float yBottom, float rTop, float yTop,
                             int colorBottom, int colorTop, float vTile) {
        MatrixStack.Entry e = p.m.peek();
        for (int i = 0; i < SIDES; i++) {
            int j = i + 1;
            float mid = (i + 0.5f) * (float) (2 * Math.PI / SIDES) - OFFSET;
            float nx = (float) Math.sin(mid), nz = (float) Math.cos(mid);
            vert(p, e, cx(i, rBottom), yBottom, cz(i, rBottom), 0f, 1f, colorBottom, nx, nz);
            vert(p, e, cx(i, rTop), yTop, cz(i, rTop), 0f, 1f - vTile, colorTop, nx, nz);
            vert(p, e, cx(j, rTop), yTop, cz(j, rTop), 1f, 1f - vTile, colorTop, nx, nz);
            vert(p, e, cx(j, rBottom), yBottom, cz(j, rBottom), 1f, 1f, colorBottom, nx, nz);
        }
    }

    /** 꼭대기 덮개(팔각 부채꼴). */
    private static void cap(BellModel.Painter p, float r, float y, int color) {
        MatrixStack.Entry e = p.m.peek();
        for (int i = 0; i < SIDES; i++) {
            int j = i + 1;
            vert(p, e, 0f, y + 0.01f, 0f, 0.5f, 0.5f, color, 0f, 0f);
            vert(p, e, cx(i, r), y, cz(i, r), 0f, 0f, color, 0f, 0f);
            vert(p, e, cx(j, r), y, cz(j, r), 1f, 0f, color, 0f, 0f);
            vert(p, e, 0f, y + 0.01f, 0f, 0.5f, 0.5f, color, 0f, 0f);
        }
    }

    private static void vert(BellModel.Painter p, MatrixStack.Entry e, float x, float y, float z, float u, float v,
                             int color, float nx, float nz) {
        p.vc.vertex(e, x, y, z).color(color).texture(u, v).overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL).normal(e, nx, nz == 0 && nx == 0 ? 1f : 0f, nz);
    }
}
