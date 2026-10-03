package dev.newyear.countdown.client;

import dev.newyear.countdown.gacha.YutBoardBlock;
import dev.newyear.countdown.gacha.YutBoardBlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 윷판 위의 말과 윷가락. 말판은 블록 모델(yut_board)이 그리고, 여기서는 움직이는 것들만 그린다.
 * 던지기: 던지는 자리 위로 가락 4개가 포물선으로 날며 회전하다가 앞/뒷면(평평한 밝은 면/둥근 어두운 면)이 정해져 내려앉는다.
 */
public class YutBoardRenderer implements BlockEntityRenderer<YutBoardBlockEntity> {
    private static final Identifier T_FLAT = mc("birch_planks");
    private static final Identifier T_ROUND = mc("dark_oak_planks");
    private static final Identifier T_RED = mc("red_concrete");
    private static final Identifier T_BLUE = mc("blue_concrete");
    private static final Identifier T_WHITE = mc("white_concrete");
    private static final int WHITE = 0xFFFFFFFF;
    private static final int FULL = 0xF000F0;
    private static final float TOP = 2f / 16f;

    public YutBoardRenderer(BlockEntityRendererFactory.Context ctx) {
    }

    private static Identifier mc(String n) {
        return Identifier.ofVanilla("textures/block/" + n + ".png");
    }

    @Override
    public boolean rendersOutsideBoundingBox(YutBoardBlockEntity be) {
        return true;
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    @Override
    public void render(YutBoardBlockEntity be, float tickDelta, MatrixStack m, VertexConsumerProvider providers, int light, int overlay) {
        World world = be.getWorld();
        if (world == null) return;
        float time = world.getTime() + tickDelta;

        m.push();
        m.translate(0.5, 0, 0.5);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-be.getCachedState().get(YutBoardBlock.FACING).asRotation()));
        BellModel.Painter p = new BellModel.Painter(providers, m, light, 0);

        // ---- 말 ----
        int[] stack = new int[YutBoardBlockEntity.POINTS.length];
        for (int i = 0; i < be.pieces.length; i++) {
            int idx = be.pieces[i];
            if (idx < 0 || idx >= stack.length) continue;
            float px = YutBoardBlockEntity.POINTS[idx][0] - 0.5f;
            float pz = YutBoardBlockEntity.POINTS[idx][1] - 0.5f;
            int k = stack[idx]++;
            boolean picked = i == be.hilite;
            float y = TOP + k * 0.075f + (picked ? 0.07f + 0.015f * MathHelper.sin(time * 0.35f) : 0f);
            Identifier tex = i < 4 ? T_RED : T_BLUE;
            if (picked) p.lightOverride = FULL;
            p.use(tex, WHITE);
            p.box(px - 0.05f, y, pz - 0.05f, px + 0.05f, y + 0.045f, pz + 0.05f);
            p.box(px - 0.036f, y + 0.045f, pz - 0.036f, px + 0.036f, y + 0.078f, pz + 0.036f);
            p.use(picked ? T_WHITE : tex, picked ? 0xFFFFF0A0 : 0xFFDDDDDD);
            p.box(px - 0.02f, y + 0.078f, pz - 0.02f, px + 0.02f, y + 0.092f, pz + 0.02f);
            p.lightOverride = -1;
        }

        // ---- 윷가락 ----
        if (be.lastResult >= 1) {
            float age = (world.getTime() - be.animStart) + tickDelta;
            float t = MathHelper.clamp(age / YutBoardBlockEntity.THROW_TICKS, 0f, 1f);
            boolean flying = age >= 0 && age < YutBoardBlockEntity.THROW_TICKS + 8;
            for (int i = 0; i < 4; i++) {
                boolean flatUp = (be.sticks >> i & 1) != 0;
                // 착지 위치/방향: 가락마다 조금씩 다르게(결정적)
                float landX = (i * 7 % 5 - 2) * 0.014f;
                float landZ = THROW_Z0 + i * 0.052f - 0.5f;
                float yaw = (i * 11 % 5 - 2) * 4f;
                float x = landX, y = TOP + 0.014f, z = landZ, spin = 0f, ys = yaw;
                if (flying && t < 1f) {
                    float u = ease(t);
                    float turns = 1 + ((be.animStart + i * 3) % 3);
                    float arc = 4f * t * (1f - t);
                    x = MathHelper.lerp(u, (i - 1.5f) * 0.03f, landX);
                    z = MathHelper.lerp(u, 0.05f, landZ);      // 판 중앙 위쪽에서 던져 앞쪽 자리로
                    y = TOP + 0.014f + 0.5f * arc;
                    spin = (1f - u) * 360f * turns;
                    ys = yaw + (1f - u) * (i % 2 == 0 ? 90f : -70f);
                } else if (flying) {
                    float b = (age - YutBoardBlockEntity.THROW_TICKS) / 8f;   // 착지 후 짧은 통통 튐
                    y += 0.035f * Math.abs(MathHelper.sin(b * (float) Math.PI * 2f)) * (1f - b);
                }
                m.push();
                m.translate(x, y, z);
                m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(ys));
                m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(spin + (flatUp ? 0f : 180f)));
                // 위쪽 절반 = 평평한 밝은 면, 아래쪽 절반 = 둥근 어두운 면 (뒤집히면 어두운 면이 위)
                p.use(T_ROUND, WHITE);
                p.box(-0.16f, -0.014f, -0.022f, 0.16f, 0f, 0.022f);
                p.use(T_FLAT, WHITE);
                p.box(-0.16f, 0f, -0.022f, 0.16f, 0.014f, 0.022f);
                m.pop();
            }
        }
        m.pop();
    }

    /** 던지는 자리 첫 가락의 z (판 기준 0~1). */
    private static final float THROW_Z0 = 0.775f;
}
