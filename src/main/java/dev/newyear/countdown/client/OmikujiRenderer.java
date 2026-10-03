package dev.newyear.countdown.client;

import dev.newyear.countdown.omikuji.Fortunes;
import dev.newyear.countdown.omikuji.OmikujiBlock;
import dev.newyear.countdown.omikuji.OmikujiBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

public class OmikujiRenderer implements BlockEntityRenderer<OmikujiBlockEntity> {
    private final TextRenderer textRenderer;

    public OmikujiRenderer(BlockEntityRendererFactory.Context ctx) {
        this.textRenderer = ctx.getTextRenderer();
    }

    @Override
    public boolean rendersOutsideBoundingBox(OmikujiBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 96;
    }

    @Override
    public void render(OmikujiBlockEntity be, float tickDelta, MatrixStack m, VertexConsumerProvider providers, int light, int overlay) {
        World world = be.getWorld();
        if (world == null) return;
        int lt = WorldRenderer.getLightmapCoordinates(world, be.getPos().up(4));
        float time = world.getTime() + tickDelta;
        float age = (world.getTime() - be.animStart) + tickDelta;
        float yaw = be.getCachedState().get(OmikujiBlock.FACING).asRotation();

        m.push();
        m.translate(0.5, 0, 0.5);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        OmikujiModel.draw(providers, m, lt, age, be.animResult, time);

        // 결과 공개 후 지붕 위에 운세 글자를 띄운다
        if (be.animResult >= 0 && age >= OmikujiBlockEntity.REVEAL_AT && age < OmikujiBlockEntity.SLIP_UNTIL) {
            float pop = MathHelper.clamp((age - OmikujiBlockEntity.REVEAL_AT) / 8f, 0f, 1f);
            float fade = age > OmikujiBlockEntity.SLIP_UNTIL - 10 ? (OmikujiBlockEntity.SLIP_UNTIL - age) / 10f : 1f;
            float s = pop * pop * Math.max(0f, fade);
            if (s > 0.02f) drawLabel(be, m, providers, s, time, yaw);
        }
        m.pop();
    }

    private void drawLabel(OmikujiBlockEntity be, MatrixStack m, VertexConsumerProvider providers, float s, float time, float yaw) {
        Fortunes.Fortune f = Fortunes.get(be.animResult);
        Text name = Text.translatable("omikuji.newyearcountdown.fortune." + f.key()).formatted(f.color(), Formatting.BOLD);
        Text msg = Text.translatable("omikuji.newyearcountdown.msg." + f.key());
        MinecraftClient mc = MinecraftClient.getInstance();

        m.push();
        m.translate(0, 4.7 + 0.06 * MathHelper.sin(time * 0.1f), 0);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));      // 구조물 회전을 되돌리고
        m.multiply(mc.gameRenderer.getCamera().getRotation());        // 카메라를 향하게 한다
        m.scale(-1f, -1f, 1f);

        m.push();
        float big = 0.075f * s;
        m.scale(big, big, big);
        textRenderer.draw(name, -textRenderer.getWidth(name) / 2f, -4f, 0xFFFFFFFF, true, m.peek().getPositionMatrix(),
                providers, TextRenderer.TextLayerType.NORMAL, 0x40000000, 0xF000F0);
        m.pop();

        m.push();
        float small = 0.026f * s;
        m.translate(0, 0.5f * s, 0);
        m.scale(small, small, small);
        textRenderer.draw(msg, -textRenderer.getWidth(msg) / 2f, 0f, 0xFFFFFFFF, true, m.peek().getPositionMatrix(),
                providers, TextRenderer.TextLayerType.NORMAL, 0x40000000, 0xF000F0);
        m.pop();
        m.pop();
    }
}
