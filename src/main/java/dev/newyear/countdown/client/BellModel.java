package dev.newyear.countdown.client;

import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.bell.BosingakBellBlockEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/**
 * 보신각 3D 모델 (구조물 좌표계: 마스터 칸 바닥 중심이 원점, 정면이 +Z, 폭은 X).
 * 실제 블록 렌더러와 설치 미리보기(홀로그램)가 함께 쓴다.
 */
public final class BellModel {
    // 바닐라 텍스처
    private static final Identifier T_PILLAR = mc("red_terracotta");
    private static final Identifier T_BEAM = mc("warped_planks");
    private static final Identifier T_STONE = mc("stone_bricks");
    private static final Identifier T_ROOF = mc("deepslate_tiles");
    private static final Identifier T_TRIM = mc("dark_oak_planks");
    private static final Identifier T_GOLD = mc("gold_block");
    private static final Identifier T_COPPER = mc("copper_block");
    private static final Identifier T_CUT_COPPER = mc("cut_copper");
    private static final Identifier T_LOG = mc("dark_oak_log");
    private static final Identifier T_LOG_TOP = mc("dark_oak_log_top");
    private static final Identifier T_IRON = mc("polished_blackstone");
    // 모드 자체 텍스처
    private static final Identifier T_ROPE = Identifier.of(NewYearCountdown.MOD_ID, "textures/block/rope.png");

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BRONZE = tint(0.80f, 0.62f, 0.40f);   // 금 블록 텍스처를 청동색으로
    private static final int BRONZE_DARK = tint(0.66f, 0.50f, 0.36f);
    private static final int ROOF_TINT = tint(0.92f, 0.95f, 1.0f);

    // 대종 층 구조: {아래 y, 위 y, 반폭, 띠 여부(1)}
    private static final float[][] BELL_LAYERS = {
            {0.00f, 0.20f, 1.00f, 1}, {0.20f, 0.36f, 0.93f, 1}, {0.36f, 0.80f, 0.88f, 0},
            {0.80f, 1.40f, 0.84f, 0}, {1.40f, 1.58f, 0.90f, 1}, {1.58f, 2.00f, 0.78f, 0},
            {2.00f, 2.28f, 0.62f, 0}, {2.28f, 2.48f, 0.42f, 0}, {2.48f, 2.60f, 0.22f, 0}
    };

    private BellModel() {}

    private static Identifier mc(String name) {
        return Identifier.ofVanilla("textures/block/" + name + ".png");
    }

    private static int tint(float r, float g, float b) {
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    /** 일반 그리기. 호출자가 이미 구조물 좌표계로 변환해 둔 상태여야 한다. */
    public static void draw(VertexConsumerProvider providers, MatrixStack m, int light,
                            float strikerAngle, float bellSway) {
        Painter p = new Painter(providers, m, light, 0);
        drawFrame(p);
        drawBell(p, bellSway);
        drawStriker(p, strikerAngle);
    }

    /** 홀로그램 그리기 (반투명 단색 틴트). */
    public static void drawGhost(VertexConsumerProvider providers, MatrixStack m, int argb) {
        Painter p = new Painter(providers, m, 0xF000F0, argb);
        drawFrame(p);
        drawBell(p, 0f);
        drawStriker(p, 0f);
    }

    // ======================= 종각 =======================

    private static void drawFrame(Painter p) {
        float hang = BosingakBellBlockEntity.HANG_Y;
        float[] px = {BosingakBellBlockEntity.PILLAR_L, BosingakBellBlockEntity.PILLAR_R};

        p.use(T_STONE, WHITE);
        for (float x : px) p.box(x - 0.5f, 0f, -0.5f, x + 0.5f, 0.3f, 0.5f);                 // 초석(기둥 밑동)
        p.use(T_PILLAR, WHITE);
        for (float x : px) p.box(x - 0.34f, 0.3f, -0.34f, x + 0.34f, hang, 0.34f);            // 기둥
        p.use(T_BEAM, WHITE);
        for (float x : px) {
            p.box(x - 0.8f, hang - 0.4f, -0.55f, x + 0.8f, hang, 0.55f);                       // 공포(기둥머리)
            p.box(x - 0.18f, hang - 0.9f, -1.0f, x + 0.18f, hang - 0.5f, 1.0f);              // 앞뒤 도리 (z ±1 보와 이어짐)
            p.box(x - 0.14f, 2.75f, -1.0f, x + 0.14f, 3.05f, 1.0f);                           // 하방 가로대(보강)
        }
        p.box(-6.9f, hang, -0.42f, 4.9f, hang + 0.5f, 0.42f);                                // 대들보
        p.use(T_GOLD, tint(0.9f, 0.75f, 0.45f));
        p.box(-6.9f, hang - 0.02f, -0.44f, 4.9f, hang + 0.08f, 0.44f);                       // 보 아래 금박 띠

        float cx = -1f;
        float base = hang + 0.5f;
        p.use(T_TRIM, WHITE);
        p.box(cx - 6.5f, base, -2.0f, cx + 6.5f, base + 0.24f, 2.0f);
        p.use(T_ROOF, ROOF_TINT);
        float[][] steps = {{6.1f, 1.8f}, {5.4f, 1.5f}, {4.5f, 1.2f}, {3.5f, 0.9f}, {2.5f, 0.55f}};
        float y = base + 0.24f;
        for (float[] s : steps) {
            p.box(cx - s[0], y, -s[1], cx + s[0], y + 0.28f, s[1]);
            y += 0.28f;
        }
        p.use(T_TRIM, WHITE);
        p.box(cx - 3.0f, y, -0.3f, cx + 3.0f, y + 0.18f, 0.3f);                               // 용마루
        for (int side = -1; side <= 1; side += 2) {                                          // 추녀 끝
            p.box(cx + side * 6.3f - 0.2f, base + 0.24f, -2.1f, cx + side * 6.3f + 0.2f, base + 0.52f, 2.1f);
        }
    }

    // ======================= 대종 =======================

    private static void drawBell(Painter p, float sway) {
        float hang = BosingakBellBlockEntity.HANG_Y;
        float y0 = BosingakBellBlockEntity.BELL_BOTTOM_Y;

        p.m.push();
        p.m.translate(0, hang, 0);
        p.m.multiply(RotationAxis.POSITIVE_Z.rotation(sway));  // 매단 지점을 축으로 흔들림
        p.m.translate(0, -hang, 0);

        for (float[] l : BELL_LAYERS) {
            boolean band = l[3] > 0;
            p.use(band ? T_COPPER : T_GOLD, band ? BRONZE_DARK : BRONZE);
            float hw = l[2];
            if (l[0] < 0.3f) {
                // 맨 아래 입술은 속이 빈 테두리로 (아래에서 올려다보면 비어 보이게)
                float t = 0.18f;
                p.box(-hw, y0 + l[0], -hw, hw, y0 + l[1], -hw + t);
                p.box(-hw, y0 + l[0], hw - t, hw, y0 + l[1], hw);
                p.box(-hw, y0 + l[0], -hw + t, -hw + t, y0 + l[1], hw - t);
                p.box(hw - t, y0 + l[0], -hw + t, hw, y0 + l[1], hw - t);
            } else {
                p.box(-hw, y0 + l[0], -hw, hw, y0 + l[1], hw);
            }
        }
        // 어깨 장식(유두): 윗부분 네 모서리의 작은 돌기
        p.use(T_CUT_COPPER, BRONZE_DARK);
        float sy = y0 + 2.0f;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                p.box(sx * 0.62f - 0.07f, sy, sz * 0.62f - 0.07f, sx * 0.62f + 0.07f, sy + 0.2f, sz * 0.62f + 0.07f);
            }
        }
        // 용뉴(고리)와 걸쇠
        float top = y0 + 2.6f;
        p.use(T_GOLD, tint(0.95f, 0.8f, 0.5f));
        p.box(-0.13f, top, -0.13f, 0.13f, top + 0.3f, 0.13f);
        p.box(-0.22f, top + 0.3f, -0.1f, 0.22f, top + 0.45f, 0.1f);
        p.use(T_IRON, WHITE);
        p.box(-0.06f, top + 0.45f, -0.06f, 0.06f, hang, 0.06f);
        p.m.pop();
    }

    // ======================= 당목 (통나무) =======================

    private static void drawStriker(Painter p, float angle) {
        float len = BosingakBellBlockEntity.ROPE_L;
        float cx = BosingakBellBlockEntity.X_LOG;
        float topY = BosingakBellBlockEntity.HANG_Y;
        float h = BosingakBellBlockEntity.LOG_HALF_LEN;

        // 두 가닥의 줄
        p.use(T_ROPE, WHITE);
        for (int side = -1; side <= 1; side += 2) {
            p.m.push();
            p.m.translate(cx + 0.8f * side, topY, 0);
            p.m.multiply(RotationAxis.POSITIVE_Z.rotation(angle));
            p.box(-0.05f, -len + 0.18f, -0.05f, 0.05f, 0f, 0.05f);
            p.m.pop();
        }

        // 통나무: 줄 길이만큼 내려간 뒤 수평을 유지
        p.m.push();
        p.m.translate(cx, topY, 0);
        p.m.multiply(RotationAxis.POSITIVE_Z.rotation(angle));
        p.m.translate(0, -len, 0);
        p.m.multiply(RotationAxis.POSITIVE_Z.rotation(-angle));

        p.use(T_LOG, WHITE);
        p.boxAlongX(-h, -0.2f, -0.2f, h, 0.2f, 0.2f);
        p.use(T_LOG_TOP, WHITE);
        p.endCapsX(-h, h, -0.2f, 0.2f, -0.2f, 0.2f);
        p.use(T_IRON, WHITE);
        for (int side = -1; side <= 1; side += 2) {
            float x = side * (h - 0.22f);
            p.box(x - 0.07f, -0.23f, -0.23f, x + 0.07f, 0.23f, 0.23f);                         // 쇠띠
        }
        // 뒤쪽 손잡이: 통나무 뒤끝에서 짧은 자루가 나와 가로 막대로 이어진다 (타종자가 이 막대를 잡는다)
        p.use(T_TRIM, WHITE);
        float gx = -h - BosingakBellBlockEntity.GRIP_GAP;
        p.box(gx - 0.05f, -0.06f, -0.06f, -h, 0.06f, 0.06f);
        p.box(gx - 0.05f, -0.05f, -0.42f, gx + 0.05f, 0.05f, 0.42f);
        p.use(T_IRON, WHITE);
        p.box(gx - 0.06f, -0.06f, -0.44f, gx + 0.06f, 0.06f, -0.38f);
        p.box(gx - 0.06f, -0.06f, 0.38f, gx + 0.06f, 0.06f, 0.44f);
        p.use(T_ROPE, WHITE);
        for (int side = -1; side <= 1; side += 2) {
            p.box(side * 0.8f - 0.08f, 0.2f, -0.08f, side * 0.8f + 0.08f, 0.3f, 0.08f);        // 줄 매듭
        }
        p.m.pop();
    }

    // ======================= 그리기 도우미 =======================

    /** 재질(텍스처+색)을 바꿔가며 박스를 그리는 도우미. 16px = 1블록 밀도로 텍스처를 타일링한다. */
    static final class Painter {
        final VertexConsumerProvider providers;
        final MatrixStack m;
        final int light;
        final int ghost; // 0 이면 일반, 아니면 홀로그램 색(ARGB)
        Identifier current;
        int color = WHITE;
        int lightOverride = -1; // 0 이상이면 이 밝기로 그린다(등불 등 발광)
        boolean translucent;    // true 면 반투명 레이어(색의 알파 사용)
        VertexConsumer vc;

        Painter(VertexConsumerProvider providers, MatrixStack m, int light, int ghost) {
            this.providers = providers;
            this.m = m;
            this.light = light;
            this.ghost = ghost;
        }

        void setTranslucent(boolean t) {
            if (t != translucent) {
                translucent = t;
                current = null; // 레이어가 바뀌므로 버퍼를 다시 가져온다
            }
        }

        void use(Identifier tex, int color) {
            if (ghost != 0) {
                // 원래 색의 명암만 살짝 남기고 홀로그램 색을 입힌다
                int lum = ((color >> 16 & 255) + (color >> 8 & 255) + (color & 255)) / 3;
                float k = 0.55f + 0.45f * lum / 255f;
                int a = ghost >>> 24;
                int r = (int) ((ghost >> 16 & 255) * k), g = (int) ((ghost >> 8 & 255) * k), b = (int) ((ghost & 255) * k);
                color = (a << 24) | (r << 16) | (g << 8) | b;
            }
            this.color = color;
            if (!tex.equals(current)) {
                current = tex;
                vc = providers.getBuffer((ghost != 0 || translucent) ? RenderLayer.getEntityTranslucent(tex) : RenderLayer.getEntityCutoutNoCull(tex));
            }
        }

        /** 여섯 면을 모두 그리는 일반 박스. */
        void box(float x1, float y1, float z1, float x2, float y2, float z2) {
            MatrixStack.Entry e = m.peek();
            faceXp(e, x1, y1, z1, x2, y2, z2, false);
            faceXn(e, x1, y1, z1, x2, y2, z2, false);
            faceZp(e, x1, y1, z1, x2, y2, z2, false);
            faceZn(e, x1, y1, z1, x2, y2, z2, false);
            faceYp(e, x1, y1, z1, x2, y2, z2, false);
            faceYn(e, x1, y1, z1, x2, y2, z2, false);
        }

        /** X 방향으로 누운 통나무의 옆면(±Y, ±Z): 나뭇결이 X 방향으로 흐르도록 UV 를 돌린다. */
        void boxAlongX(float x1, float y1, float z1, float x2, float y2, float z2) {
            MatrixStack.Entry e = m.peek();
            faceZp(e, x1, y1, z1, x2, y2, z2, true);
            faceZn(e, x1, y1, z1, x2, y2, z2, true);
            faceYp(e, x1, y1, z1, x2, y2, z2, true);
            faceYn(e, x1, y1, z1, x2, y2, z2, true);
        }

        /** 통나무 양 끝면(±X). */
        void endCapsX(float x1, float x2, float y1, float y2, float z1, float z2) {
            MatrixStack.Entry e = m.peek();
            faceXp(e, x1, y1, z1, x2, y2, z2, false);
            faceXn(e, x1, y1, z1, x2, y2, z2, false);
        }

        private void faceXp(MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, boolean r) {
            tile(e, x2, y1, z2, 0, 0, -1, z2 - z1, 0, 1, 0, y2 - y1, 1, 0, 0, r);
        }

        private void faceXn(MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, boolean r) {
            tile(e, x1, y1, z1, 0, 0, 1, z2 - z1, 0, 1, 0, y2 - y1, -1, 0, 0, r);
        }

        private void faceZp(MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, boolean r) {
            tile(e, x1, y1, z2, 1, 0, 0, x2 - x1, 0, 1, 0, y2 - y1, 0, 0, 1, r);
        }

        private void faceZn(MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, boolean r) {
            tile(e, x2, y1, z1, -1, 0, 0, x2 - x1, 0, 1, 0, y2 - y1, 0, 0, -1, r);
        }

        private void faceYp(MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, boolean r) {
            tile(e, x1, y2, z2, 1, 0, 0, x2 - x1, 0, 0, -1, z2 - z1, 0, 1, 0, r);
        }

        private void faceYn(MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, boolean r) {
            tile(e, x1, y1, z1, 1, 0, 0, x2 - x1, 0, 0, 1, z2 - z1, 0, -1, 0, r);
        }

        /**
         * 한 면을 1블록 단위 타일로 쪼개 그린다 (각 타일은 텍스처 전체 한 장).
         * O: 시작점, U/V: 단위 방향(길이 lu, lv), N: 법선.
         */
        private void tile(MatrixStack.Entry e, float ox, float oy, float oz,
                          float ux, float uy, float uz, float lu,
                          float vx, float vy, float vz, float lv,
                          float nx, float ny, float nz, boolean rotate) {
            for (float s = 0; s < lu - 1e-4f; s += 1f) {
                float su = Math.min(1f, lu - s);
                for (float t = 0; t < lv - 1e-4f; t += 1f) {
                    float tv = Math.min(1f, lv - t);
                    // 네 모서리 (왼쪽 아래, 왼쪽 위, 오른쪽 위, 오른쪽 아래)
                    vertex(e, ox + ux * s + vx * t, oy + uy * s + vy * t, oz + uz * s + vz * t, 0f, 1f, rotate, nx, ny, nz);
                    vertex(e, ox + ux * s + vx * (t + tv), oy + uy * s + vy * (t + tv), oz + uz * s + vz * (t + tv), 0f, 1f - tv, rotate, nx, ny, nz);
                    vertex(e, ox + ux * (s + su) + vx * (t + tv), oy + uy * (s + su) + vy * (t + tv), oz + uz * (s + su) + vz * (t + tv), su, 1f - tv, rotate, nx, ny, nz);
                    vertex(e, ox + ux * (s + su) + vx * t, oy + uy * (s + su) + vy * t, oz + uz * (s + su) + vz * t, su, 1f, rotate, nx, ny, nz);
                }
            }
        }

        private void vertex(MatrixStack.Entry e, float x, float y, float z, float u, float v, boolean rotate,
                            float nx, float ny, float nz) {
            float uu = rotate ? v : u;
            float vv = rotate ? 1f - u : v;
            vc.vertex(e, x, y, z).color(color).texture(uu, vv)
                    .overlay(OverlayTexture.DEFAULT_UV).light(lightOverride >= 0 ? lightOverride : light).normal(e, nx, ny, nz);
        }
    }
}
