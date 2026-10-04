package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.gacha.YutBoardBlock;
import dev.newyear.countdown.gacha.YutBoardBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * 윷판 위의 말과 윷가락. 말판은 블록 모델(yut_board)이 그리고, 여기서는 움직이는 것들만 그린다.
 * 던지기: 던지는 자리 위로 가락 4개가 포물선으로 날며 회전하다가 앞/뒷면(평평한 밝은 면/둥근 어두운 면)이 정해져 내려앉는다.
 */
public class YutBoardRenderer implements BlockEntityRenderer<YutBoardBlockEntity, BeState<YutBoardBlockEntity>> {
    private static final Identifier T_FLAT = mc("birch_planks");
    private static final Identifier T_ROUND = mc("dark_oak_planks");
    private static final Identifier T_RED = mc("red_concrete");
    private static final Identifier T_BLUE = mc("blue_concrete");
    private static final Identifier T_WHITE = mc("white_concrete");
    private static final int WHITE = 0xFFFFFFFF;
    private static final int FULL = 0xF000F0;
    private static final float TOP = 2f / 16f;

    public YutBoardRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    private static Identifier mc(String n) {
        return Identifier.withDefaultNamespace("textures/block/" + n + ".png");
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public BeState<YutBoardBlockEntity> createRenderState() {
        return new BeState<>();
    }

    @Override
    public void extractRenderState(YutBoardBlockEntity be, BeState<YutBoardBlockEntity> state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.be = be;
        state.partialTicks = partialTicks;
    }

    @Override
    public void submit(BeState<YutBoardBlockEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        GeoBuffers buffers = new GeoBuffers();
        render(state.be, state.partialTicks, m, buffers, camera, state.lightCoords);
        buffers.flush(collector);
    }

    private static float ease(float x) {
        x = Mth.clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    private void render(YutBoardBlockEntity be, float tickDelta, PoseStack m, GeoBuffers providers, CameraRenderState camera, int light) {
        Level world = be.getLevel();
        if (world == null) return;
        float time = world.getGameTime() + tickDelta;

        m.pushPose();
        m.translate(0.5, 0, 0.5);
        m.rotate(Axis.YP.rotationDegrees(-be.getBlockState().getValue(YutBoardBlock.FACING).toYRot()));
        BellModel.Painter p = new BellModel.Painter(providers, m, light, 0);

        // 판은 3x3 블록: 판 기준 좌표(0~1)를 블록 단위로 바꾼다
        final float B = 3f;

        // ---- 말 ----
        int[] stack = new int[YutBoardBlockEntity.POINTS.length];
        for (int i = 0; i < be.pieces.length; i++) {
            int idx = be.pieces[i];
            if (idx < 0 || idx >= stack.length) continue;
            float px = (YutBoardBlockEntity.POINTS[idx][0] - 0.5f) * B;
            float pz = (YutBoardBlockEntity.POINTS[idx][1] - 0.5f) * B;
            int k = stack[idx]++;
            boolean picked = i == be.hilite;
            float y = TOP + k * 0.15f + (picked ? 0.14f + 0.03f * Mth.sin(time * 0.35f) : 0f);
            Identifier tex = i < 4 ? T_RED : T_BLUE;
            if (picked) p.lightOverride = FULL;
            p.use(tex, WHITE);
            p.box(px - 0.10f, y, pz - 0.10f, px + 0.10f, y + 0.09f, pz + 0.10f);
            p.box(px - 0.072f, y + 0.09f, pz - 0.072f, px + 0.072f, y + 0.16f, pz + 0.072f);
            p.use(picked ? T_WHITE : tex, picked ? 0xFFFFF0A0 : 0xFFDDDDDD);
            p.box(px - 0.04f, y + 0.16f, pz - 0.04f, px + 0.04f, y + 0.19f, pz + 0.04f);
            p.lightOverride = -1;
        }

        // ---- 윷가락 ----
        if (be.lastResult >= 1) {
            float age = (world.getGameTime() - be.animStart) + tickDelta;
            float t = Mth.clamp(age / YutBoardBlockEntity.THROW_TICKS, 0f, 1f);
            boolean flying = age >= 0 && age < YutBoardBlockEntity.THROW_TICKS + 8;
            for (int i = 0; i < 4; i++) {
                boolean flatUp = (be.sticks >> i & 1) != 0;
                // 착지 위치/방향: 가락마다 조금씩 다르게(결정적)
                float landX = (i * 7 % 5 - 2) * 0.05f;
                float landZ = (THROW_Z0 + i * 0.05f - 0.5f) * B;
                float yaw = (i * 11 % 5 - 2) * 4f;
                float x = landX, y = TOP + 0.03f, z = landZ, spin = 0f, ys = yaw;
                if (flying && t < 1f) {
                    float u = ease(t);
                    float turns = 1 + ((be.animStart + i * 3) % 3);
                    float arc = 4f * t * (1f - t);
                    x = Mth.lerp(u, (i - 1.5f) * 0.1f, landX);
                    z = Mth.lerp(u, 0.1f, landZ);        // 판 가운데 위쪽에서 던져 앞쪽 자리로
                    y = TOP + 0.03f + 1.1f * arc;
                    spin = (1f - u) * 360f * turns;
                    ys = yaw + (1f - u) * (i % 2 == 0 ? 90f : -70f);
                } else if (flying) {
                    float b = (age - YutBoardBlockEntity.THROW_TICKS) / 8f;   // 착지 후 짧은 통통 튐
                    y += 0.08f * Math.abs(Mth.sin(b * (float) Math.PI * 2f)) * (1f - b);
                }
                m.pushPose();
                m.translate(x, y, z);
                m.rotate(Axis.YP.rotationDegrees(ys));
                m.rotate(Axis.XP.rotationDegrees(spin + (flatUp ? 0f : 180f)));
                // 위쪽 절반 = 평평한 밝은 면, 아래쪽 절반 = 둥근 어두운 면 (뒤집히면 어두운 면이 위)
                p.use(T_ROUND, WHITE);
                p.box(-0.40f, -0.03f, -0.05f, 0.40f, 0f, 0.05f);
                p.use(T_FLAT, WHITE);
                p.box(-0.40f, 0f, -0.05f, 0.40f, 0.03f, 0.05f);
                m.popPose();
            }
        }
        m.popPose();
    }

    /** 던지는 자리 첫 가락의 z (판 기준 0~1). */
    private static final float THROW_Z0 = 0.79f;
}
