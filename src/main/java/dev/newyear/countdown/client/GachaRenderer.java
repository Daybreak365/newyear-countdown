package dev.newyear.countdown.client;

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
 * 손잡이를 돌리면 캡슐들이 달그락거리고, 앞쪽 배출구로 캡슐 하나가 굴러 떨어진다.
 */
public class GachaRenderer implements BlockEntityRenderer<GachaMachineBlockEntity> {
    private static final Identifier T_RED = mc("red_concrete");
    private static final Identifier T_WHITE = mc("white_concrete");
    private static final Identifier T_GOLD = mc("gold_block");
    private static final Identifier T_DARK = mc("polished_blackstone");
    private static final Identifier T_LAMP = mc("shroomlight");

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GLASS = 0x4DCFEAFF;
    private static final int FULL = 0xF000F0;

    /** 돔 안 캡슐 더미 위치(x,y,z). */
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
                l.add(new float[]{MathHelper.sin(a) * r, ys[layer], MathHelper.cos(a) * r});
            }
        }
        return l.toArray(new float[0][]);
    }

    @Override
    public boolean rendersOutsideBoundingBox(GachaMachineBlockEntity be) {
        return true;
    }

    @Override
    public void render(GachaMachineBlockEntity be, float tickDelta, MatrixStack m,
                       VertexConsumerProvider providers, int light, int overlay) {
        World world = be.getWorld();
        if (world == null) return;
        float age = (world.getTime() - be.animStart) + tickDelta;
        boolean anim = age >= 0 && age < GachaMachineBlockEntity.TOTAL + 2;
        float time = world.getTime() + tickDelta;

        m.push();
        m.translate(0.5, 0, 0.5);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-be.getCachedState().get(GachaMachineBlock.FACING).asRotation()));
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

        // 손잡이(돌아간다)
        float turn = anim ? MathHelper.clamp(age / 18f, 0f, 1f) : 0f;
        float ease = turn * turn * (3f - 2f * turn);
        m.push();
        m.translate(0, 0.59f, 0.47f);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-ease * 360f));
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

        // 마퀴 전구
        p.use(T_LAMP, WHITE);
        p.lightOverride = FULL;
        for (int i = 0; i < 5; i++) {
            boolean on = !anim || ((int) (age / 2f) + i) % 2 == 0;
            if (!on) continue;
            float x = -0.36f + i * 0.18f;
            p.box(x - 0.035f, 0.82f, 0.45f, x + 0.035f, 0.86f, 0.49f);
        }
        p.lightOverride = -1;

        // ---- 돔 속 캡슐들 ----
        float shake = anim && age < 20f ? 1f : 0f;
        for (int i = 0; i < BALLS.length; i++) {
            float[] b = BALLS[i];
            float jx = shake * 0.022f * MathHelper.sin(age * 1.9f + i * 2.1f);
            float jy = shake * Math.abs(0.03f * MathHelper.sin(age * 1.5f + i * 1.3f));
            float jz = shake * 0.022f * MathHelper.cos(age * 1.7f + i * 1.7f);
            ball(p, m, b[0] + jx, b[1] + jy, b[2] + jz, 0.085f, CapsuleItem.RGB[i % CapsuleItem.RGB.length], i * 37f + shake * age * 9f);
        }

        // ---- 떨어지는 캡슐 ----
        if (anim && age >= 20f) {
            float t = MathHelper.clamp((age - 20f) / 8f, 0f, 1f);
            float restY = 0.14f + 0.085f;
            float y = 0.34f - (0.34f - restY) * t * t;
            if (t >= 1f && age < 31f) y += 0.03f * Math.abs(MathHelper.sin((age - 28f) * 1.6f)) * (1f - (age - 28f) / 3f);
            ball(p, m, 0f, y, 0.50f, 0.085f, CapsuleItem.RGB[be.animColor % CapsuleItem.RGB.length], 20f);
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
        p.setTranslucent(false);

        // ---- 간판: 2027 ----
        m.push();
        m.translate(0, 1.92f, 0.125f);
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

    /** 위는 색, 아래는 흰색인 팔각 캡슐. 중심 기준. */
    private static void ball(BellModel.Painter p, MatrixStack m, float x, float y, float z, float r, int rgb, float yaw) {
        m.push();
        m.translate(x, y, z);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        float w = r * 0.82f;
        for (int k = 0; k < 2; k++) {
            m.push();
            if (k == 1) m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
            p.use(T_WHITE, 0xFF000000 | rgb);
            p.box(-w, 0.02f * r, -w, w, r, w);
            p.use(T_WHITE, 0xFFF3F3F3);
            p.box(-w, -r, -w, w, -0.02f * r, w);
            p.use(T_WHITE, 0xFFD8D8D8);
            p.box(-w - 0.002f, -0.08f * r, -w - 0.002f, w + 0.002f, 0.08f * r, w + 0.002f);
            m.pop();
        }
        p.use(T_WHITE, 0xFF000000 | rgb);
        p.box(-w * 0.55f, r, -w * 0.55f, w * 0.55f, r * 1.12f, w * 0.55f);
        p.use(T_WHITE, 0xFFF3F3F3);
        p.box(-w * 0.55f, -r * 1.12f, -w * 0.55f, w * 0.55f, -r, w * 0.55f);
        m.pop();
    }
}
