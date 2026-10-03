package dev.newyear.countdown.client;

import dev.newyear.countdown.bell.BosingakBellBlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/** 보신각 블록 렌더러: 모델(BellModel)을 마스터 칸 기준으로 그린다. */
public class BellRenderer implements BlockEntityRenderer<BosingakBellBlockEntity> {
    public BellRenderer(BlockEntityRendererFactory.Context context) {}

    @Override
    public boolean rendersOutsideBoundingBox(BosingakBellBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 160;
    }

    @Override
    public void render(BosingakBellBlockEntity be, float tickDelta, MatrixStack m,
                       VertexConsumerProvider providers, int light, int overlay) {
        World world = be.getWorld();
        if (world == null) return;
        int lt = WorldRenderer.getLightmapCoordinates(world, be.getPos().up(2));

        float angle = MathHelper.lerp(tickDelta, be.prevStrikerAngle, be.strikerAngle);
        float age = (world.getTime() - be.swayStart) + tickDelta;
        float sway = age < 0 ? 0f : be.swayAmp * (float) Math.exp(-age / 90f) * MathHelper.sin(age * 0.28f);

        m.push();
        m.translate(0.5, 0, 0.5);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-be.getYaw()));
        BellModel.draw(providers, m, lt, angle, sway);
        m.pop();
    }
}
