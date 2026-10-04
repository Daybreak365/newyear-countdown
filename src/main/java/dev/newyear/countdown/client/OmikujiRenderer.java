package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.omikuji.Fortunes;
import dev.newyear.countdown.omikuji.OmikujiBlock;
import dev.newyear.countdown.omikuji.OmikujiBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public class OmikujiRenderer implements BlockEntityRenderer<OmikujiBlockEntity, BeState<OmikujiBlockEntity>> {
    private final Font textRenderer;

    public OmikujiRenderer(BlockEntityRendererProvider.Context ctx) {
        this.textRenderer = ctx.font();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public BeState<OmikujiBlockEntity> createRenderState() {
        return new BeState<>();
    }

    @Override
    public void extractRenderState(OmikujiBlockEntity be, BeState<OmikujiBlockEntity> state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.be = be;
        state.partialTicks = partialTicks;
    }

    @Override
    public void submit(BeState<OmikujiBlockEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        GeoBuffers buffers = new GeoBuffers();
        render(state.be, state.partialTicks, m, buffers, camera, state.lightCoords);
        buffers.flush(collector);
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    private void render(OmikujiBlockEntity be, float tickDelta, PoseStack m, GeoBuffers providers, CameraRenderState camera, int light) {
        Level world = be.getLevel();
        if (world == null) return;
        int lt = LightCoordsUtil.getLightCoords(world, be.getBlockPos().above(4));
        float time = world.getGameTime() + tickDelta;
        float age = (world.getGameTime() - be.animStart) + tickDelta;
        float yaw = be.getBlockState().getValue(OmikujiBlock.FACING).toYRot();

        m.pushPose();
        m.translate(0.5, 0, 0.5);
        m.rotate(Axis.YP.rotationDegrees(-yaw));
        OmikujiModel.draw(providers, m, lt, age, be.animResult, time);

        // 결과 공개 후 지붕 위에 운세 글자를 띄운다
        if (be.animResult >= 0 && age >= OmikujiBlockEntity.REVEAL_AT && age < OmikujiBlockEntity.SLIP_UNTIL) {
            float pop = Mth.clamp((age - OmikujiBlockEntity.REVEAL_AT) / 8f, 0f, 1f);
            float fade = age > OmikujiBlockEntity.SLIP_UNTIL - 10 ? (OmikujiBlockEntity.SLIP_UNTIL - age) / 10f : 1f;
            float s = pop * pop * Math.max(0f, fade);
            if (s > 0.02f) drawLabel(be, m, providers, s, time, yaw, camera);
        }
        m.popPose();
    }

    private void drawLabel(OmikujiBlockEntity be, PoseStack m, GeoBuffers providers, float s, float time, float yaw, CameraRenderState camera) {
        Fortunes.Fortune f = Fortunes.get(be.animResult);
        Component name = Component.translatable("omikuji.newyearcountdown.fortune." + f.key()).withStyle(f.color(), ChatFormatting.BOLD);
        Component msg = Component.translatable("omikuji.newyearcountdown.msg." + f.key());

        m.pushPose();
        m.translate(0, 4.7 + 0.06 * Mth.sin(time * 0.1f), 0);
        m.rotate(Axis.YP.rotationDegrees(yaw));      // 구조물 회전을 되돌리고
        m.rotate(camera.orientation);        // 카메라를 향하게 한다
        m.scale(1f, -1f, 1f);

        m.pushPose();
        float big = 0.075f * s;
        m.scale(big, big, big);
        providers.text(m, name, -textRenderer.width(name) / 2f, -4f, 0xFFFFFFFF, true, 0x40000000, 0xF000F0);
        m.popPose();

        m.pushPose();
        float small = 0.026f * s;
        m.translate(0, 0.5f * s, 0);
        m.scale(small, small, small);
        providers.text(m, msg, -textRenderer.width(msg) / 2f, 0f, 0xFFFFFFFF, true, 0x40000000, 0xF000F0);
        m.popPose();
        m.popPose();
    }
}
