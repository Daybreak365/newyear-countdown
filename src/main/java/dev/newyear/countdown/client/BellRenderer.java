package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.bell.BosingakBellBlockEntity;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/** 보신각 블록 렌더러: 모델(BellModel)을 마스터 칸 기준으로 그린다. */
public class BellRenderer implements BlockEntityRenderer<BosingakBellBlockEntity, BeState<BosingakBellBlockEntity>> {
    public BellRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public BeState<BosingakBellBlockEntity> createRenderState() {
        return new BeState<>();
    }

    @Override
    public void extractRenderState(BosingakBellBlockEntity be, BeState<BosingakBellBlockEntity> state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.be = be;
        state.partialTicks = partialTicks;
    }

    @Override
    public void submit(BeState<BosingakBellBlockEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        GeoBuffers buffers = new GeoBuffers();
        render(state.be, state.partialTicks, m, buffers, camera, state.lightCoords);
        buffers.flush(collector);
    }

    @Override
    public int getViewDistance() {
        return 160;
    }

    private void render(BosingakBellBlockEntity be, float tickDelta, PoseStack m, GeoBuffers providers, CameraRenderState camera, int light) {
        Level world = be.getLevel();
        if (world == null) return;
        int lt = LightCoordsUtil.getLightCoords(world, be.getBlockPos().above(2));

        float angle = Mth.lerp(tickDelta, be.prevStrikerAngle, be.strikerAngle);
        float age = (world.getGameTime() - be.swayStart) + tickDelta;
        float sway = age < 0 ? 0f : be.swayAmp * (float) Math.exp(-age / 90f) * Mth.sin(age * 0.28f);

        m.pushPose();
        m.translate(0.5, 0, 0.5);
        m.rotate(Axis.YP.rotationDegrees(-be.getYaw()));
        BellModel.draw(providers, m, lt, angle, sway);
        m.popPose();
    }
}
