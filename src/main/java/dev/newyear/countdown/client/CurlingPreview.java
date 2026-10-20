package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.newyear.countdown.curling.CurlingStoneEntity;
import dev.newyear.countdown.curling.CurlingStoneItem;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 컬링 스톤을 꾹 누르고 있는 동안, 지금 힘·회전으로 놓으면 스톤이 미끄러질 길을 얼음 위에 점선으로 보여 준다.
 * (다른 스톤과의 충돌은 계산하지 않는다)
 */
public final class CurlingPreview {
    private static final Identifier T_WHITE = Identifier.withDefaultNamespace("textures/block/white_concrete.png");

    private CurlingPreview() {}

    public static void render(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isUsingItem()) return;
        ItemStack use = p.getUseItem();
        if (!(use.getItem() instanceof CurlingStoneItem item)) return;
        int used = p.getTicksUsingItem();
        float power = Math.min(1f, used / (float) CurlingStoneItem.FULL);
        Vec3 look = p.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z);
        if (dir.lengthSqr() < 1.0e-4) return;
        dir = dir.normalize();
        List<Vec3> path = CurlingStoneEntity.predict(mc.level, CurlingStoneItem.startPos(p, dir), CurlingStoneItem.velocity(dir, power),
                p.isShiftKeyDown() ? -1f : 1f);

        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        PoseStack m = ctx.poseStack();
        m.pushPose();
        m.translate(-cam.x, -cam.y, -cam.z);
        GeoBuffers buf = new GeoBuffers();
        VertexConsumer vc = buf.getBuffer(RenderTypes.entityTranslucent(T_WHITE));
        PoseStack.Pose e = m.last();
        int rgb = item.team() == 0 ? 0xFF5050 : 0xFFE050;
        for (int i = 0; i < path.size() - 1; i++) {
            if (i % 2 == 1) continue;                    // 점선
            Vec3 a = path.get(i);
            int alpha = (int) (200 * (0.35 + 0.65 * (i / (float) path.size())));
            dot(vc, e, a, 0.06f, (alpha << 24) | rgb);
        }
        // 멈출 곳: 스톤 크기의 고리
        Vec3 end = path.get(path.size() - 1);
        int seg = 20;
        float r0 = CurlingStoneEntity.RADIUS - 0.05f, r1 = CurlingStoneEntity.RADIUS + 0.03f;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float y = (float) end.y + 0.03f;
            v(vc, e, end.x + Math.cos(a0) * r0, y, end.z + Math.sin(a0) * r0, 0xE0000000 | rgb);
            v(vc, e, end.x + Math.cos(a1) * r0, y, end.z + Math.sin(a1) * r0, 0xE0000000 | rgb);
            v(vc, e, end.x + Math.cos(a1) * r1, y, end.z + Math.sin(a1) * r1, 0xE0000000 | rgb);
            v(vc, e, end.x + Math.cos(a0) * r1, y, end.z + Math.sin(a0) * r1, 0xE0000000 | rgb);
        }
        m.popPose();
        buf.flush(ctx.submitNodeCollector());
    }

    private static void dot(VertexConsumer vc, PoseStack.Pose e, Vec3 c, float r, int color) {
        float y = (float) c.y + 0.03f;
        v(vc, e, c.x - r, y, c.z - r, color);
        v(vc, e, c.x - r, y, c.z + r, color);
        v(vc, e, c.x + r, y, c.z + r, color);
        v(vc, e, c.x + r, y, c.z - r, color);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose e, double x, double y, double z, int color) {
        vc.addVertex(e, (float) x, (float) y, (float) z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0).setNormal(e, 0f, 1f, 0f);
    }
}
