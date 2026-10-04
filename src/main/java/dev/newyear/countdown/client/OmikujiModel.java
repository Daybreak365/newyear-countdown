package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.omikuji.Fortunes;
import dev.newyear.countdown.omikuji.OmikujiBlockEntity;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 오미쿠지 뽑기대 3D 모델 (구조물 좌표: 마스터 칸 바닥 중심이 원점, 정면 +Z, 3x3칸, 높이 4칸).
 * 작은 사당 모양: 돌 기단 + 붉은 기둥 + 기와 지붕, 안쪽 제단 위에 붉은 팔각 뽑기통, 앞에는 새전함·방울·등롱·에마.
 * 뽑으면 방울이 울리고 통이 흔들리며 막대가 튀어나오고, 앞에 운세 쪽지가 놓인다.
 */
public final class OmikujiModel {
    private static final Identifier T_STONE = mc("stone_bricks");
    private static final Identifier T_DECK = mc("spruce_planks");
    private static final Identifier T_DARK = mc("dark_oak_planks");
    private static final Identifier T_LOG = mc("dark_oak_log");
    private static final Identifier T_PILLAR = mc("red_terracotta");
    private static final Identifier T_ROOF = mc("deepslate_tiles");
    private static final Identifier T_GOLD = mc("gold_block");
    private static final Identifier T_STICK = mc("birch_planks");
    private static final Identifier T_PAPER = mc("white_wool");
    private static final Identifier T_BLACK = mc("polished_blackstone");
    private static final Identifier T_LAMP = mc("shroomlight");
    private static final Identifier T_IRON = mc("polished_blackstone");
    private static final Identifier T_ROPE = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "textures/block/rope.png");

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GOLD = tint(0.92f, 0.76f, 0.45f);
    private static final int ROOF_TINT = tint(0.92f, 0.95f, 1.0f);
    private static final int FULL_BRIGHT = 0xF000F0;

    private OmikujiModel() {}

    private static Identifier mc(String n) {
        return Identifier.withDefaultNamespace("textures/block/" + n + ".png");
    }

    private static int tint(float r, float g, float b) {
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    /**
     * @param age  뽑기 시작 후 경과 틱(소수 포함), 연출이 없으면 매우 큰 값
     * @param time 월드 시간(틱, 소수 포함): 등롱·방울의 잔잔한 흔들림용
     */
    public static void draw(GeoBuffers providers, PoseStack m, int light, float age, int result, float time) {
        draw(new BellModel.Painter(providers, m, light, 0), age, result, time);
    }

    public static void drawGhost(GeoBuffers providers, PoseStack m, int argb) {
        draw(new BellModel.Painter(providers, m, 0xF000F0, argb), 1.0e6f, -1, 0f);
    }

    private static void draw(BellModel.Painter p, float age, int result, float time) {
        boolean live = p.ghost == 0;
        drawBase(p);
        drawShrineFrame(p);
        drawAltar(p);
        drawOffering(p);

        float shake = 0f; // 뽑는 동안 0~1
        if (age >= 0 && age < OmikujiBlockEntity.SHAKE_END + 6) {
            shake = Math.min(1f, age / 6f) * (age < OmikujiBlockEntity.SHAKE_END ? 1f
                    : Math.max(0f, 1f - (age - OmikujiBlockEntity.SHAKE_END) / 6f));
        }

        drawBarrel(p, age, shake);
        drawStick(p, age, result);
        drawSuzu(p, age, shake, time, live);
        drawLanterns(p, time, live);
        drawSlip(p, age, result);
        drawEma(p);
        drawShide(p, time, live);
    }

    // ---------------------------------------------------------------- 기단/틀

    private static void drawBase(BellModel.Painter p) {
        p.use(T_STONE, tint(0.9f, 0.9f, 0.92f));
        p.box(-1.46f, 0f, -1.46f, 1.46f, 0.2f, 1.46f);                                 // 돌 기단
        p.use(T_DECK, WHITE);
        p.box(-1.38f, 0.2f, -1.38f, 1.38f, 0.32f, 1.38f);                              // 나무 마루
        p.use(T_STONE, tint(0.8f, 0.8f, 0.84f));
        p.box(-0.55f, 0f, 1.46f, 0.55f, 0.1f, 1.5f);                                   // 앞 디딤돌
    }

    private static void drawShrineFrame(BellModel.Painter p) {
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float x = sx * 1.2f, z = sz * 1.2f;
                p.use(T_STONE, WHITE);
                p.box(x - 0.17f, 0.32f, z - 0.17f, x + 0.17f, 0.46f, z + 0.17f);        // 주춧돌
                p.use(T_PILLAR, WHITE);
                p.box(x - 0.11f, 0.46f, z - 0.11f, x + 0.11f, 2.85f, z + 0.11f);        // 붉은 기둥
                p.use(T_GOLD, GOLD);
                p.box(x - 0.13f, 2.62f, z - 0.13f, x + 0.13f, 2.7f, z + 0.13f);         // 금빛 띠
                p.box(x - 0.13f, 0.46f, z - 0.13f, x + 0.13f, 0.52f, z + 0.13f);
            }
        }
        // 보 (사방)
        p.use(T_DARK, WHITE);
        for (int s = -1; s <= 1; s += 2) {
            p.box(-1.32f, 2.85f, s * 1.2f - 0.1f, 1.32f, 3.10f, s * 1.2f + 0.1f);       // 앞뒤 보
            p.box(s * 1.2f - 0.1f, 2.85f, -1.32f, s * 1.2f + 0.1f, 3.10f, 1.32f);       // 좌우 보
            p.box(-1.1f, 2.45f, s * 1.2f - 0.04f, 1.1f, 2.55f, s * 1.2f + 0.04f);       // 앞뒤 낮은 가로대(인방)
        }
        p.use(T_GOLD, GOLD);
        for (int s = -1; s <= 1; s += 2) {
            p.box(-1.32f, 2.83f, s * 1.2f - 0.11f, 1.32f, 2.87f, s * 1.2f + 0.11f);
        }
        // 지붕 (OmikujiRoofShapes 의 충돌 모양과 같은 값)
        p.use(T_DARK, WHITE);
        p.box(-1.5f, 3.10f, -1.5f, 1.5f, 3.30f, 1.5f);
        p.use(T_ROOF, ROOF_TINT);
        float[] half = {1.42f, 1.14f, 0.86f, 0.58f, 0.30f};
        float y = 3.30f;
        for (float h : half) {
            p.box(-h, y, -h, h, y + 0.14f, h);
            y += 0.14f;
        }
        p.use(T_GOLD, GOLD);
        for (int sx = -1; sx <= 1; sx += 2) {                                          // 추녀 끝 장식
            for (int sz = -1; sz <= 1; sz += 2) {
                p.box(sx * 1.5f - 0.06f, 3.14f, sz * 1.5f - 0.06f, sx * 1.5f + 0.06f, 3.34f, sz * 1.5f + 0.06f);
            }
        }
    }

    // ---------------------------------------------------------------- 제단/통

    private static void drawAltar(BellModel.Painter p) {
        p.use(T_LOG, WHITE);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                p.box(sx * 0.62f - 0.08f, 0.32f, sz * 0.62f - 0.08f, sx * 0.62f + 0.08f, 0.88f, sz * 0.62f + 0.08f);
            }
        }
        p.use(T_DARK, WHITE);
        p.box(-0.8f, 0.88f, -0.8f, 0.8f, 1.0f, 0.8f);                                   // 제단 상판
        p.use(T_GOLD, GOLD);
        p.box(-0.82f, 0.96f, -0.82f, 0.82f, 1.0f, 0.82f);                               // 상판 금 테두리
        p.use(T_PILLAR, WHITE);
        p.box(-0.5f, 1.0f, -0.5f, 0.5f, 1.01f, 0.5f);                                   // 붉은 방석
    }

    private static void drawBarrel(BellModel.Painter p, float age, float shake) {
        p.m.pushPose();
        p.m.translate(0, 1.0, 0);
        if (shake > 0f) {
            p.m.translate(0, 0.08f * shake * Math.abs(Mth.sin(age * 1.1f)), 0);
            p.m.rotate(Axis.XP.rotation(0.24f * shake * Mth.sin(age * 1.1f)));
            p.m.rotate(Axis.ZP.rotation(0.24f * shake * Mth.cos(age * 1.37f)));
        }
        p.m.translate(0, -1.0, 0);

        p.use(T_PILLAR, WHITE);
        octagon(p, 0.44f, 1.0f, 2.2f);
        p.use(T_GOLD, GOLD);
        octagon(p, 0.485f, 1.0f, 1.1f);
        octagon(p, 0.485f, 1.5f, 1.56f);
        octagon(p, 0.485f, 2.1f, 2.2f);
        // 앞면 금빛 문양(복)
        p.use(T_GOLD, tint(1.0f, 0.88f, 0.55f));
        p.box(-0.14f, 1.62f, 0.455f, 0.14f, 2.0f, 0.475f);
        p.use(T_PILLAR, WHITE);
        p.box(-0.07f, 1.7f, 0.47f, 0.07f, 1.92f, 0.49f);
        p.use(T_DARK, WHITE);
        octagon(p, 0.52f, 2.2f, 2.3f);                                                  // 뚜껑
        octagon(p, 0.38f, 2.3f, 2.34f);
        p.use(T_BLACK, WHITE);
        p.box(-0.12f, 2.34f, -0.12f, 0.12f, 2.345f, 0.12f);                            // 막대 구멍

        // 늘 꽂혀 있는 막대 다발 (구멍에서 부채꼴로 삐죽)
        p.use(T_STICK, WHITE);
        for (int i = 0; i < 6; i++) {
            p.m.pushPose();
            p.m.translate(0, 2.34, 0);
            p.m.rotate(Axis.YP.rotationDegrees(i * 60f));
            p.m.rotate(Axis.ZP.rotation(0.07f + 0.02f * (i % 2)));
            p.box(-0.022f, 0f, -0.04f, 0.022f, 0.3f + 0.04f * (i % 3), 0.04f);
            p.m.popPose();
        }
        p.m.popPose();
    }

    /** 뽑힌 막대: 구멍에서 튀어나와 잠깐 떠 있다가 다시 들어간다. */
    private static void drawStick(BellModel.Painter p, float age, int result) {
        float rise = 0f;
        float spin = 0f;
        if (age >= OmikujiBlockEntity.SHAKE_END - 6) {
            float t = Mth.clamp((age - (OmikujiBlockEntity.SHAKE_END - 6)) / 12f, 0f, 1f);
            rise = 1f - (1f - t) * (1f - t) * (1f - t);
            spin = (1f - t) * 6f;
            if (age > OmikujiBlockEntity.STICK_DOWN_AT) {
                rise *= Math.max(0f, 1f - (age - OmikujiBlockEntity.STICK_DOWN_AT) / 10f);
            }
        }
        if (rise < 0.001f) return;
        float r = 0.58f * rise;
        float bob = age > OmikujiBlockEntity.REVEAL_AT ? 0.015f * Mth.sin(age * 0.3f) : 0f;
        p.m.pushPose();
        p.m.translate(0, 0, 0);
        p.m.rotate(Axis.YP.rotation(spin));
        p.use(T_STICK, WHITE);
        p.box(-0.04f, 1.2f + r + bob, -0.09f, 0.04f, 2.3f + r + bob, 0.09f);
        p.use(T_PILLAR, WHITE);
        p.box(-0.045f, 2.18f + r + bob, -0.095f, 0.045f, 2.3f + r + bob, 0.095f);
        p.use(T_GOLD, GOLD);
        for (int i = 0; i < 3; i++) {                                                   // 막대 번호(금 줄)
            float yy = 2.0f + r + bob - i * 0.06f;
            p.box(-0.045f, yy, -0.092f, 0.045f, yy + 0.03f, 0.092f);
        }
        p.m.popPose();
    }

    // ---------------------------------------------------------------- 앞쪽 장식

    private static void drawOffering(BellModel.Painter p) {
        // 새전함: 격자 상자 + 금 테두리 + 투입구
        p.use(T_DARK, WHITE);
        p.box(-1.12f, 0.32f, 0.88f, -0.46f, 0.74f, 1.3f);
        p.use(T_LOG, WHITE);
        for (int i = 0; i < 4; i++) {
            float x = -1.1f + i * 0.2f;
            p.box(x, 0.74f, 0.9f, x + 0.12f, 0.8f, 1.28f);                              // 뚜껑 살
        }
        p.use(T_BLACK, WHITE);
        p.box(-0.97f, 0.74f, 0.94f, -0.61f, 0.745f, 1.0f);                              // 투입구
        p.use(T_GOLD, GOLD);
        p.box(-1.14f, 0.7f, 0.86f, -0.44f, 0.74f, 1.32f);
        p.box(-1.14f, 0.32f, 0.86f, -0.44f, 0.36f, 1.32f);
    }

    private static void drawSuzu(BellModel.Painter p, float age, float shake, float time, boolean live) {
        float swing = 0f;
        if (live) {
            swing = 0.03f * Mth.sin(time * 0.06f) + 0.5f * shake * Mth.sin(age * 0.9f);
        }
        p.m.pushPose();
        p.m.translate(0, 3.0, 1.2);                                                     // 보에 매달린 점
        p.m.rotate(Axis.XP.rotation(swing));
        p.use(T_ROPE, WHITE);
        p.box(-0.05f, -0.9f, -0.05f, 0.05f, 0f, 0.05f);                                 // 굵은 방울줄
        p.box(-0.13f, -0.9f, -0.03f, -0.07f, -0.2f, 0.03f);                             // 땋은 줄 가닥
        p.box(0.07f, -0.9f, -0.03f, 0.13f, -0.2f, 0.03f);
        p.use(T_PILLAR, WHITE);
        p.box(-0.13f, -0.15f, -0.1f, 0.13f, -0.02f, 0.1f);                              // 붉은 매듭
        p.use(T_GOLD, GOLD);
        p.box(-0.17f, -1.22f, -0.17f, 0.17f, -0.9f, 0.17f);                             // 방울(스즈)
        p.use(T_BLACK, WHITE);
        p.box(-0.17f, -1.07f, -0.175f, 0.17f, -1.04f, 0.175f);                          // 방울 홈
        p.m.popPose();
    }

    private static void drawLanterns(BellModel.Painter p, float time, boolean live) {
        for (int s = -1; s <= 1; s += 2) {
            float sway = live ? 0.05f * Mth.sin(time * 0.045f + s * 1.3f) : 0f;
            p.m.pushPose();
            p.m.translate(s * 0.85f, 2.85, 1.2);
            p.m.rotate(Axis.XP.rotation(sway));
            p.use(T_IRON, WHITE);
            p.box(-0.015f, -0.3f, -0.015f, 0.015f, 0f, 0.015f);                         // 사슬
            p.use(T_DARK, WHITE);
            p.box(-0.14f, -0.36f, -0.14f, 0.14f, -0.3f, 0.14f);                         // 윗뚜껑
            p.box(-0.14f, -0.76f, -0.14f, 0.14f, -0.7f, 0.14f);                         // 밑받침
            p.use(T_LAMP, WHITE);
            int old = p.lightOverride;
            p.lightOverride = FULL_BRIGHT;                                              // 등불은 스스로 빛난다
            p.box(-0.11f, -0.7f, -0.11f, 0.11f, -0.36f, 0.11f);
            p.lightOverride = old;
            p.use(T_DARK, WHITE);
            for (int i = -1; i <= 1; i += 2) {                                          // 창살
                p.box(i * 0.112f - 0.012f, -0.7f, -0.112f, i * 0.112f + 0.012f, -0.36f, 0.112f);
                p.box(-0.112f, -0.7f, i * 0.112f - 0.012f, 0.112f, -0.36f, i * 0.112f + 0.012f);
            }
            p.use(T_PILLAR, WHITE);
            p.box(-0.02f, -0.98f, -0.02f, 0.02f, -0.76f, 0.02f);                        // 붉은 술
            p.m.popPose();
        }
    }

    /** 앞 보에 건 금줄(시메나와)과 지그재그로 접은 흰 종이 장식(시데). */
    private static void drawShide(BellModel.Painter p, float time, boolean live) {
        p.use(T_ROPE, WHITE);
        p.box(-1.12f, 2.74f, 1.17f, 1.12f, 2.84f, 1.23f);
        float[] xs = {-0.62f, -0.34f, 0.34f, 0.62f};
        for (int i = 0; i < xs.length; i++) {
            float sway = live ? 0.08f * Mth.sin(time * 0.07f + i * 1.7f) : 0f;
            p.m.pushPose();
            p.m.translate(xs[i], 2.74f, 1.2f);
            p.m.rotate(Axis.XP.rotation(sway));
            p.use(T_PAPER, WHITE);
            for (int k = 0; k < 3; k++) {                                              // 지그재그 세 마디
                float off = (k % 2 == 0 ? -0.025f : 0.025f);
                p.box(-0.05f + off, -0.1f - k * 0.1f, -0.006f, 0.05f + off, -0.01f - k * 0.1f, 0.006f);
            }
            p.m.popPose();
        }
    }

    private static void drawEma(BellModel.Painter p) {
        // 뒤쪽 보에 걸린 에마(소원 패) 세 장 — 살짝 다른 높이와 색으로
        float[] xs = {-0.72f, 0f, 0.72f};
        float[] hs = {0.0f, 0.1f, 0.03f};
        for (int i = 0; i < 3; i++) {
            float x = xs[i], top = 2.55f - hs[i];
            p.use(T_ROPE, WHITE);
            p.box(x - 0.01f, top, -1.205f, x + 0.01f, 2.85f, -1.195f);
            p.use(T_STICK, WHITE);
            p.box(x - 0.17f, top - 0.34f, -1.23f, x + 0.17f, top, -1.2f);
            p.use(T_PILLAR, WHITE);
            p.box(x - 0.17f, top - 0.34f, -1.245f, x + 0.17f, top - 0.30f, -1.23f);
            p.use(T_GOLD, GOLD);
            p.box(x - 0.1f, top - 0.2f, -1.245f, x + 0.1f, top - 0.17f, -1.23f);
        }
    }

    // ---------------------------------------------------------------- 운세 쪽지

    private static void drawSlip(BellModel.Painter p, float age, int result) {
        if (result < 0 || age < OmikujiBlockEntity.REVEAL_AT || age >= OmikujiBlockEntity.SLIP_UNTIL) return;
        Fortunes.Fortune f = Fortunes.get(result);
        float pop = Mth.clamp((age - OmikujiBlockEntity.REVEAL_AT) / 7f, 0f, 1f);
        float fade = age > OmikujiBlockEntity.SLIP_UNTIL - 10 ? (OmikujiBlockEntity.SLIP_UNTIL - age) / 10f : 1f;
        float s = pop * Math.max(0f, fade);
        float lift = (1f - pop) * 0.25f;
        p.m.pushPose();
        p.m.translate(0, 1.01f + lift, 0.66f);
        p.m.rotate(Axis.XP.rotation(-(1f - pop) * 0.9f));
        p.use(T_PAPER, tint(f.r(), f.g(), f.b()));
        p.box(-0.2f * s, 0f, -0.0f, 0.2f * s, 0.02f * s, 0.34f * s);
        p.use(T_PILLAR, WHITE);
        p.box(-0.2f * s, 0.02f * s, 0.02f * s, 0.2f * s, 0.024f * s, 0.05f * s);        // 윗줄 붉은 띠
        p.box(-0.2f * s, 0.02f * s, 0.29f * s, 0.2f * s, 0.024f * s, 0.32f * s);        // 아랫줄 붉은 띠
        p.use(T_GOLD, GOLD);
        p.box(-0.04f * s, 0.02f * s, 0.12f * s, 0.04f * s, 0.026f * s, 0.22f * s);      // 가운데 금 표식
        p.m.popPose();
    }

    /** 정팔각기둥: 네 개의 직사각형(0/45/90/135도)을 겹쳐서 만든다. apothem = 중심에서 면까지 거리. */
    private static void octagon(BellModel.Painter p, float apothem, float y0, float y1) {
        float w = (float) (apothem * Math.tan(Math.PI / 8));
        for (int k = 0; k < 4; k++) {
            p.m.pushPose();
            p.m.rotate(Axis.YP.rotationDegrees(45f * k));
            float e = 0.0008f * k; // 겹친 윗면의 깜빡임 방지
            p.box(-apothem, y0 + e, -w, apothem, y1 + e, w);
            p.m.popPose();
        }
    }

}
