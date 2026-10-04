package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.newyear.countdown.curling.CurlingHouseBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** 컬링 하우스: 블록 바닥에 반지름 2칸의 동심원(파랑-하양-빨강-하양)과 가운데 십자선. */
public class CurlingHouseRenderer implements BlockEntityRenderer<CurlingHouseBlockEntity, BeState<CurlingHouseBlockEntity>> {
    private static final Identifier T_WHITE = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
    private static final int SEG = 64;

    public CurlingHouseRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public BeState<CurlingHouseBlockEntity> createRenderState() {
        return new BeState<>();
    }

    @Override
    public void extractRenderState(CurlingHouseBlockEntity be, BeState<CurlingHouseBlockEntity> state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.be = be;
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public void submit(BeState<CurlingHouseBlockEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        GeoBuffers buf = new GeoBuffers();
        VertexConsumer vc = buf.getBuffer(RenderTypes.entityCutout(T_WHITE));
        PoseStack.Pose e = m.last();
        int light = state.lightCoords;
        float R = CurlingHouseBlockEntity.RADIUS;
        // 바깥부터: 파랑 링, 하양 링, 빨강 링, 하양 버튼 (높이를 아주 조금씩 올려 겹침 깜빡임 방지)
        ring(vc, e, light, R * 0.66f, R, 0.010f, 0xFF2F62C8);
        ring(vc, e, light, R * 0.34f, R * 0.66f, 0.011f, 0xFFF2F2F2);
        ring(vc, e, light, R * 0.09f, R * 0.34f, 0.012f, 0xFFC8201F);
        ring(vc, e, light, 0f, R * 0.09f, 0.013f, 0xFFF2F2F2);
        // 티 라인 / 센터 라인
        strip(vc, e, light, -R, -0.02f, R, 0.02f, 0.014f, 0xFF1A1A1A);
        strip(vc, e, light, -0.02f, -R, 0.02f, R, 0.016f, 0xFF1A1A1A);
        buf.flush(collector);
    }

    private static void ring(VertexConsumer vc, PoseStack.Pose e, int light, float r0, float r1, float y, int color) {
        for (int i = 0; i < SEG; i++) {
            double a0 = i * Math.PI * 2 / SEG, a1 = (i + 1) * Math.PI * 2 / SEG;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            vert(vc, e, 0.5f + c0 * r0, y, 0.5f + s0 * r0, color, light);
            vert(vc, e, 0.5f + c1 * r0, y, 0.5f + s1 * r0, color, light);
            vert(vc, e, 0.5f + c1 * r1, y, 0.5f + s1 * r1, color, light);
            vert(vc, e, 0.5f + c0 * r1, y, 0.5f + s0 * r1, color, light);
        }
    }

    private static void strip(VertexConsumer vc, PoseStack.Pose e, int light, float x0, float z0, float x1, float z1, float y, int color) {
        vert(vc, e, 0.5f + x0, y, 0.5f + z0, color, light);
        vert(vc, e, 0.5f + x0, y, 0.5f + z1, color, light);
        vert(vc, e, 0.5f + x1, y, 0.5f + z1, color, light);
        vert(vc, e, 0.5f + x1, y, 0.5f + z0, color, light);
    }

    private static void vert(VertexConsumer vc, PoseStack.Pose e, float x, float y, float z, int color, int light) {
        vc.addVertex(e, x, y, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(e, 0f, 1f, 0f);
    }
}
