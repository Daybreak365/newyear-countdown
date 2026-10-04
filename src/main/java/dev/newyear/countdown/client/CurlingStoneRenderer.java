package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.curling.CurlingStoneEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** 컬링 스톤: 팔각으로 둥글린 화강암 몸통, 팀 색 띠와 손잡이. 미끄러질 때 회전 방향으로 돈다. */
public class CurlingStoneRenderer extends EntityRenderer<CurlingStoneEntity, CurlingStoneRenderer.State> {
    private static final Identifier T_GRANITE = mc("polished_andesite");
    private static final Identifier T_DARK = mc("polished_blackstone");
    private static final Identifier T_RED = mc("red_concrete");
    private static final Identifier T_YELLOW = mc("yellow_concrete");
    private static final int WHITE = 0xFFFFFFFF;

    public static class State extends EntityRenderState {
        int team;
        float spinAngle;
    }

    public CurlingStoneRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.3f;
    }

    private static Identifier mc(String n) {
        return Identifier.withDefaultNamespace("textures/block/" + n + ".png");
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CurlingStoneEntity e, State s, float partialTicks) {
        super.extractRenderState(e, s, partialTicks);
        s.team = e.team();
        s.spinAngle = Mth.lerp(partialTicks, e.prevSpinAngle, e.spinAngle) + e.getYRot();
    }

    @Override
    public void submit(State s, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        GeoBuffers buf = new GeoBuffers();
        m.pushPose();
        m.rotate(Axis.YP.rotationDegrees(-s.spinAngle));
        BellModel.Painter p = new BellModel.Painter(buf, m, s.lightCoords, 0);
        Identifier team = s.team == 0 ? T_RED : T_YELLOW;
        // 몸통: 두 상자를 45도 겹쳐 팔각으로 (아래 좁게 - 가운데 넓게 - 위 좁게)
        octagon(p, T_DARK, 0xFFAAAAAA, 0.22f, 0.00f, 0.03f);
        octagon(p, T_GRANITE, 0xFFC8CCD4, 0.29f, 0.03f, 0.12f);
        octagon(p, T_GRANITE, 0xFFB8BCC4, 0.27f, 0.12f, 0.17f);
        octagon(p, team, WHITE, 0.24f, 0.17f, 0.185f);          // 팀 색 띠
        octagon(p, T_GRANITE, 0xFFD2D6DC, 0.2f, 0.185f, 0.2f);
        // 손잡이
        p.use(team, WHITE);
        p.box(-0.04f, 0.2f, -0.04f, 0.04f, 0.27f, 0.04f);
        p.box(-0.04f, 0.25f, -0.04f, 0.2f, 0.29f, 0.04f);
        p.use(T_DARK, WHITE);
        p.box(0.16f, 0.235f, -0.045f, 0.205f, 0.295f, 0.045f);
        m.popPose();
        buf.flush(collector);
        super.submit(s, m, collector, camera);
    }

    private static void octagon(BellModel.Painter p, Identifier tex, int color, float r, float y0, float y1) {
        p.use(tex, color);
        float a = r * 0.92f;
        p.box(-a, y0, -a, a, y1, a);
        p.m.pushPose();
        p.m.rotate(Axis.YP.rotationDegrees(45f));
        p.box(-a + 0.0005f, y0 + 0.0005f, -a + 0.0005f, a - 0.0005f, y1 - 0.0005f, a - 0.0005f);
        p.m.popPose();
    }
}
