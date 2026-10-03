package dev.newyear.countdown.client;

import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.gacha.CapsuleItem;
import dev.newyear.countdown.gacha.GachaMachineBlock;
import dev.newyear.countdown.gacha.GachaMachineBlockEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 가챠 머신 3D 모델 (1x2x1). 붉은 몸체 + 금장 + 유리 돔 안의 캡슐 더미.
 *
 * 연출 타임라인 (틱, GachaMachineBlockEntity.TOTAL 과 같은 기준):
 *   0~4   동전 투입: 머신이 움찔(눌림), 손잡이가 살짝 뒤로 젖혀짐
 *   4~26  손잡이 1.5바퀴, 머신이 떨리고 돔 안 캡슐이 소용돌이치며 튀고, 마퀴 전구가 쫓아감
 *   24~36 배출구 덮개가 열렸다 닫힘
 *   25~32 캡슐이 덮개 밖으로 밀려나와 받침대로 구르며 떨어짐
 *   32~38 받침대에서 두 번 튕기며 안착
 *   36~42 반짝이 + 캡슐이 빛남, 마지막에 팡 하고 사라지며 인벤토리로
 */
public class GachaRenderer implements BlockEntityRenderer<GachaMachineBlockEntity> {
    private static final Identifier T_RED = mc("red_concrete");
    private static final Identifier T_WHITE = mc("white_concrete");
    private static final Identifier T_GOLD = mc("gold_block");
    private static final Identifier T_DARK = mc("polished_blackstone");
    private static final Identifier T_LAMP = mc("shroomlight");
    /** 캡슐 전용 광택 텍스처(틴트로 색을 입힌다)와 이음매 띠 */
    private static final Identifier T_SHELL = Identifier.of(NewYearCountdown.MOD_ID, "textures/entity/capsule_shell.png");
    private static final Identifier T_SEAM = Identifier.of(NewYearCountdown.MOD_ID, "textures/entity/capsule_seam.png");

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GLASS = 0x4DCFEAFF;
    private static final int FULL = 0xF000F0;
    private static final int T = GachaMachineBlockEntity.TOTAL;

    /** 돔 안 캡슐 더미 위치 {x, y, z, 층}. */
    private static final float[][] BALLS = buildBalls();

    private final TextRenderer textRenderer;

    public GachaRenderer(BlockEntityRendererFactory.Context ctx) {
        this.textRenderer = ctx.getTextRenderer();
    }

    private static Identifier mc(String n) {
        return Identifier.ofVanilla("textures/block/" + n + ".png");
    }

    private static float[][] buildBalls() {
        java.util.List<float[]> l = new java.util.ArrayList<>();
        float[] ys = {0.99f, 1.15f, 1.31f, 1.46f};
        int[] counts = {7, 6, 4, 2};
        float[] rad = {0.20f, 0.17f, 0.13f, 0.07f};
        for (int layer = 0; layer < ys.length; layer++) {
            for (int i = 0; i < counts[layer]; i++) {
                float a = (float) (i * Math.PI * 2 / counts[layer] + layer * 0.9);
                float r = (layer == 0 && i == 0) ? 0f : rad[layer];
                l.add(new float[]{MathHelper.sin(a) * r, ys[layer], MathHelper.cos(a) * r, layer});
            }
        }
        return l.toArray(new float[0][]);
    }

    @Override
    public boolean rendersOutsideBoundingBox(GachaMachineBlockEntity be) {
        return true;
    }

    private static float clamp01(float x) {
        return MathHelper.clamp(x, 0f, 1f);
    }

    private static float ease(float x) {
        x = clamp01(x);
        return x * x * (3f - 2f * x);
    }

    @Override
    public void render(GachaMachineBlockEntity be, float tickDelta, MatrixStack m,
                       VertexConsumerProvider providers, int light, int overlay) {
        World world = be.getWorld();
        if (world == null) return;
        float age = (world.getTime() - be.animStart) + tickDelta;
        boolean anim = age >= 0 && age < T + 2;
        float time = world.getTime() + tickDelta;

        // 소용돌이 세기: 4~8 에 올라갔다가 22~28 에 가라앉는다
        float swirl = anim ? ease((age - 4f) / 4f) * (1f - ease((age - 22f) / 6f)) : 0f;
        // 머신 움찔(0~4) + 손잡이 돌리는 동안의 진동
        float press = anim && age < 5f ? MathHelper.sin(clamp01(age / 5f) * (float) Math.PI) : 0f;

        m.push();
        m.translate(0.5, 0, 0.5);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-be.getCachedState().get(GachaMachineBlock.FACING).asRotation()));
        if (anim) {
            m.translate(swirl * 0.0045f * MathHelper.sin(age * 2.7f), -press * 0.018f, swirl * 0.0045f * MathHelper.cos(age * 2.3f));
            m.scale(1f + press * 0.025f, 1f - press * 0.03f, 1f + press * 0.025f);
        }
        BellModel.Painter p = new BellModel.Painter(providers, m, light, 0);

        // ---- 몸체 ----
        p.use(T_DARK, WHITE);
        p.box(-0.47f, 0f, -0.47f, 0.47f, 0.10f, 0.47f);
        p.use(T_RED, WHITE);
        p.box(-0.42f, 0.10f, -0.42f, 0.42f, 0.80f, 0.42f);
        p.use(T_GOLD, WHITE);
        p.box(-0.45f, 0.80f, -0.45f, 0.45f, 0.88f, 0.45f);
        p.box(-0.45f, 0.10f, -0.45f, -0.41f, 0.80f, -0.41f);
        p.box(0.41f, 0.10f, -0.45f, 0.45f, 0.80f, -0.41f);
        p.box(-0.45f, 0.10f, 0.41f, -0.41f, 0.80f, 0.45f);
        p.box(0.41f, 0.10f, 0.41f, 0.45f, 0.80f, 0.45f);

        // 앞면 패널 + 다이얼
        p.use(T_WHITE, WHITE);
        p.box(-0.30f, 0.42f, 0.42f, 0.30f, 0.76f, 0.455f);
        p.use(T_DARK, WHITE);
        p.box(-0.14f, 0.45f, 0.455f, 0.14f, 0.73f, 0.47f);

        // 손잡이: 살짝 젖혔다가(0~4) 1.5바퀴 돈다(4~26), 끝에서 약간 되돌아와 멈춘다
        float turn;
        if (!anim) {
            turn = 0f;
        } else if (age < 4f) {
            turn = -0.06f * ease(age / 4f);
        } else if (age < 26f) {
            turn = -0.06f + 1.56f * ease((age - 4f) / 22f);
        } else {
            turn = 1.5f + 0.04f * MathHelper.sin(clamp01((age - 26f) / 6f) * (float) Math.PI);
        }
        m.push();
        m.translate(0, 0.59f, 0.47f);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-turn * 360f));
        p.use(T_GOLD, WHITE);
        p.box(-0.11f, -0.03f, 0f, 0.11f, 0.03f, 0.04f);
        p.box(-0.03f, -0.11f, 0f, 0.03f, 0.11f, 0.04f);
        p.box(-0.045f, -0.045f, 0f, 0.045f, 0.045f, 0.065f);
        m.pop();

        // 배출구와 받침
        p.use(T_DARK, WHITE);
        p.box(-0.11f, 0.30f, 0.42f, 0.11f, 0.38f, 0.435f);
        p.box(-0.21f, 0.10f, 0.42f, 0.21f, 0.14f, 0.57f);
        p.box(-0.21f, 0.14f, 0.42f, -0.18f, 0.30f, 0.57f);
        p.box(0.18f, 0.14f, 0.42f, 0.21f, 0.30f, 0.57f);
        // 배출구 안쪽 어둠
        p.use(T_DARK, 0xFF222222);
        p.box(-0.10f, 0.16f, 0.425f, 0.10f, 0.29f, 0.44f);

        // 배출구 덮개: 위쪽 경첩을 축으로 바깥으로 열렸다 닫힌다
        float flap = anim ? ease((age - 24f) / 3f) * 56f - ease((age - 33f) / 3f) * 56f : 0f;
        m.push();
        m.translate(0, 0.30f, 0.465f);
        m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-flap));
        p.use(T_DARK, 0xFFDDDDDD);
        p.box(-0.105f, -0.14f, -0.012f, 0.105f, 0f, 0.012f);
        p.use(T_GOLD, WHITE);
        p.box(-0.105f, -0.145f, -0.016f, 0.105f, -0.125f, 0.016f);
        m.pop();

        // 마퀴 전구: 평소엔 천천히 깜빡, 돌릴 때는 쫓아가고, 배출될 때는 한꺼번에 번쩍
        p.use(T_LAMP, WHITE);
        p.lightOverride = FULL;
        for (int i = 0; i < 5; i++) {
            boolean on;
            if (!anim) {
                on = ((int) (time / 7f) + i) % 5 != 0;
            } else if (age < 24f) {
                int head = (int) (age / 1.5f) % 5;
                on = i == head || i == (head + 2) % 5;
            } else {
                on = ((int) (age / 2f)) % 2 == 0;
            }
            if (!on) continue;
            float x = -0.36f + i * 0.18f;
            p.box(x - 0.035f, 0.82f, 0.45f, x + 0.035f, 0.86f, 0.49f);
        }
        p.lightOverride = -1;

        // ---- 돔 속 캡슐들: 평소엔 가만히, 돌릴 때는 층마다 반대 방향으로 소용돌이치며 튄다 ----
        float spin = swirl > 0f ? age * 0.27f : 0f;
        for (int i = 0; i < BALLS.length; i++) {
            float[] b = BALLS[i];
            int layer = (int) b[3];
            float dir = (layer & 1) == 0 ? 1f : -1f;
            float w = spin * dir * (1f + layer * 0.25f);
            float sa = MathHelper.sin(w), ca = MathHelper.cos(w);
            float rx = b[0] * ca - b[2] * sa;
            float rz = b[0] * sa + b[2] * ca;
            float bounce = swirl * Math.abs(0.05f * MathHelper.sin(age * 0.95f + i * 1.3f));
            float push = swirl * 0.03f * MathHelper.sin(age * 0.7f + i * 2.1f);    // 반지름 방향으로도 출렁
            ball(p, m, rx * (1f + push * 3f), b[1] + bounce, rz * (1f + push * 3f), 0.085f,
                    CapsuleItem.RGB[i % CapsuleItem.RGB.length], i * 37f + swirl * age * 11f * dir,
                    swirl * 22f * MathHelper.sin(age * 0.45f + i));
        }

        // ---- 배출되는 캡슐 ----
        if (anim && age >= 25f && age < T) {
            float r = 0.085f;
            float restY = 0.14f + r;
            float y, z, roll = 0f, pop = 1f;
            if (age < 28f) {                                   // 덮개 밖으로 밀려나온다
                float t = ease((age - 25f) / 3f);
                y = 0.285f; z = MathHelper.lerp(t, 0.40f, 0.51f);
                roll = -t * 40f;
            } else if (age < 32f) {                            // 받침대로 구르며 떨어진다
                float t = (age - 28f) / 4f;
                y = 0.285f - (0.285f - restY) * t * t;
                z = 0.51f + 0.05f * t;
                roll = -40f - t * 150f;
            } else {                                           // 두 번 튕기고 안착
                float t = (age - 32f) / 6f;
                float amp = 0.055f * (1f - clamp01(t)) * (1f - clamp01(t));
                y = restY + amp * Math.abs(MathHelper.sin(clamp01(t) * (float) Math.PI * 2f));
                z = 0.56f + 0.012f * (1f - clamp01(t));
                roll = -190f - 40f * (1f - (1f - clamp01(t)) * (1f - clamp01(t)));
            }
            boolean glow = age >= 36f;
            if (age >= T - 2f) pop = 1f - ease((age - (T - 2f)) / 2f) * 0.9f;
            if (glow) p.lightOverride = FULL;
            m.push();
            m.translate(0, y, z);
            m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(roll));
            m.scale(pop, pop, pop);
            ball(p, m, 0f, 0f, 0f, r, CapsuleItem.RGB[be.animColor % CapsuleItem.RGB.length], 20f, 0f);
            m.pop();
            p.lightOverride = -1;

            // 반짝이: 받침대 위로 피어오르는 십자 불꽃
            if (age >= 35f) {
                p.use(T_LAMP, 0xFFFFF4B0);
                p.lightOverride = FULL;
                for (int k = 0; k < 4; k++) {
                    float t = clamp01((age - 35f) / 7f);
                    float a = k * (float) (Math.PI / 2) + age * 0.18f;
                    float sx = MathHelper.cos(a) * (0.10f + 0.05f * t);
                    float sz = 0.56f + MathHelper.sin(a) * 0.06f;
                    float sy = restY + 0.05f + t * 0.16f + 0.02f * k;
                    float s = 0.016f * (1f - t * 0.6f);
                    p.box(sx - s * 2.2f, sy - s * 0.4f, sz - s * 0.4f, sx + s * 2.2f, sy + s * 0.4f, sz + s * 0.4f);
                    p.box(sx - s * 0.4f, sy - s * 2.2f, sz - s * 0.4f, sx + s * 0.4f, sy + s * 2.2f, sz + s * 0.4f);
                }
                p.lightOverride = -1;
            }
        }

        // ---- 유리 돔 / 윗장식 ----
        p.use(T_GOLD, WHITE);
        p.box(-0.40f, 0.88f, -0.40f, 0.40f, 0.92f, 0.40f);
        p.box(-0.37f, 1.72f, -0.37f, 0.37f, 1.78f, 0.37f);
        p.use(T_RED, WHITE);
        p.box(-0.26f, 1.78f, -0.26f, 0.26f, 1.84f, 0.26f);
        p.use(T_DARK, WHITE);
        p.box(-0.34f, 1.84f, -0.12f, 0.34f, 2.00f, 0.12f);

        p.setTranslucent(true);
        p.use(T_WHITE, GLASS);
        for (int k = 0; k < 2; k++) {
            m.push();
            if (k == 1) m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
            p.box(-0.33f, 0.92f, -0.33f, 0.33f, 1.72f, 0.33f);
            m.pop();
        }
        p.use(T_WHITE, 0x66FFFFFF);
        p.box(-0.26f, 1.05f, 0.335f, -0.20f, 1.62f, 0.345f); // 유리 반사광
        if (swirl > 0.05f) {
            // 소용돌이칠 때 돔 안이 따뜻한 빛으로 맥동한다
            int a = (int) (swirl * (28f + 22f * MathHelper.sin(age * 0.9f)));
            p.lightOverride = FULL;
            p.use(T_WHITE, (Math.max(a, 0) << 24) | 0xFFD98A);
            p.box(-0.31f, 0.93f, -0.31f, 0.31f, 1.71f, 0.31f);
            p.lightOverride = -1;
        }
        p.setTranslucent(false);

        // ---- 간판: 2027 (돌리는 동안 반짝이며 통통 튄다) ----
        m.push();
        float bob = anim && age > 4f && age < 30f ? 0.012f * Math.abs(MathHelper.sin(age * 0.8f)) : 0f;
        m.translate(0, 1.92f + bob, 0.125f);
        float s = 0.0185f;
        m.scale(s, -s, s);
        String txt = "2027";
        float w = textRenderer.getWidth(txt);
        int gold = anim && ((int) (age / 3f)) % 2 == 0 ? 0xFFFFF2A0 : 0xFFF6C945;
        textRenderer.draw(txt, -w / 2f, -4f, gold, false, m.peek().getPositionMatrix(), providers,
                TextRenderer.TextLayerType.NORMAL, 0, FULL);
        m.pop();

        m.pop();
    }

    /** 정팔각 단면의 기둥(변마다 얇은 직사각 박스 4개가 중심을 지난다). a = 중심에서 면까지 거리. */
    private static void octagon(BellModel.Painter p, MatrixStack m, float a, float y1, float y2) {
        float b = a * 0.4142f;
        for (int k = 0; k < 4; k++) {
            m.push();
            if (k > 0) m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(k * 45f));
            float e = k * 0.0005f;   // 윗면/아랫면 z-fighting 방지
            p.box(-a, y1 + e, -b, a, y2 - e, b);
            m.pop();
        }
    }

    /**
     * 위는 색, 아래는 흰색인 캡슐. 중심 기준. 전용 광택 텍스처 + 정팔각 3단(구에 가깝게) + 이음매 띠.
     * @param tilt 앞뒤 기울기(도) — 돔 안에서 구를 때의 뒤척임
     */
    private static void ball(BellModel.Painter p, MatrixStack m, float x, float y, float z, float r, int rgb, float yaw, float tilt) {
        m.push();
        m.translate(x, y, z);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        if (tilt != 0f) m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(tilt));
        p.fullUv = true;
        int top = 0xFF000000 | rgb;
        // 위 반구
        p.use(T_SHELL, top);
        octagon(p, m, r * 0.97f, 0.03f * r, 0.46f * r);
        octagon(p, m, r * 0.80f, 0.46f * r, 0.80f * r);
        octagon(p, m, r * 0.50f, 0.80f * r, 1.00f * r);
        // 아래 반구
        p.use(T_SHELL, 0xFFF4F4F6);
        octagon(p, m, r * 0.97f, -0.46f * r, -0.03f * r);
        octagon(p, m, r * 0.80f, -0.80f * r, -0.46f * r);
        octagon(p, m, r * 0.50f, -1.00f * r, -0.80f * r);
        // 이음매
        p.use(T_SEAM, 0xFFFFFFFF);
        octagon(p, m, r * 1.0f, -0.035f * r, 0.035f * r);
        p.fullUv = false;
        m.pop();
    }
}
