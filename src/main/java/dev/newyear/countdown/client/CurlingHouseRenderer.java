package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.newyear.countdown.ModReg;
import dev.newyear.countdown.curling.CurlingHouseBlockEntity;
import dev.newyear.countdown.curling.CurlingStoneEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 컬링 하우스: 바닥에 5x5 칸 픽셀아트 과녁(계단진 동심원), 위에는 엔드·점수·차례·남은 스톤 전광판,
 * 지금 점수가 나는 스톤 밑에는 금빛 고리.
 */
public class CurlingHouseRenderer implements BlockEntityRenderer<CurlingHouseBlockEntity, BeState<CurlingHouseBlockEntity>> {
    private static final Identifier T_HOUSE = ModReg.id("textures/block/curling_house.png");
    private static final Identifier T_WHITE = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
    private static final int FULL = 0xF000F0;

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
        state.partialTicks = partialTicks;
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public void submit(BeState<CurlingHouseBlockEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        CurlingHouseBlockEntity be = state.be;
        Level level = be.getLevel();
        if (level == null) return;
        GeoBuffers buf = new GeoBuffers();
        PoseStack.Pose e = m.last();
        float R = CurlingHouseBlockEntity.RADIUS;
        VertexConsumer vc = buf.getBuffer(RenderTypes.entityCutout(T_HOUSE));
        quad(vc, e, state.lightCoords, 0.5f - R, 0.5f - R, 0.5f + R, 0.5f + R, 0.012f, 0xFFFFFFFF, 0f, 0f, 1f, 1f);

        // 지금 점수가 나는 스톤 밑의 금빛 고리
        float time = level.getGameTime() + state.partialTicks;
        int pulse = (int) (150 + 80 * Math.sin(time * 0.25));
        VertexConsumer glow = buf.getBuffer(RenderTypes.entityTranslucent(T_WHITE));
        double bx = be.getBlockPos().getX(), by = be.getBlockPos().getY(), bz = be.getBlockPos().getZ();
        for (CurlingStoneEntity s : counting(be, level)) {
            float sx = (float) (s.getX() - bx), sz = (float) (s.getZ() - bz), sy = (float) (s.getY() - by) + 0.02f;
            ring(glow, e, sx, sy, sz, 0.36f, 0.46f, (pulse << 24) | 0xFFD84A);
        }

        // 전광판
        label(buf, m, camera, 0.5f, 2.6f, 0.5f, line1(be), 0.022f);
        label(buf, m, camera, 0.5f, 2.32f, 0.5f, line2(be), 0.018f);
        buf.flush(collector);
    }

    private static Component team(int t) {
        return Component.translatable(t == 0 ? "curling.newyearcountdown.team.red" : "curling.newyearcountdown.team.yellow")
                .withStyle(t == 0 ? ChatFormatting.RED : ChatFormatting.YELLOW);
    }

    private static Component line1(CurlingHouseBlockEntity be) {
        MutableComponent end = Component.translatable("curling.newyearcountdown.board.end", Math.min(be.end, CurlingHouseBlockEntity.ENDS), CurlingHouseBlockEntity.ENDS)
                .withStyle(ChatFormatting.AQUA);
        return end.append(Component.literal("   ")).append(team(0)).append(Component.literal(" " + be.score[0] + " : " + be.score[1] + " "))
                .append(team(1));
    }

    private static Component line2(CurlingHouseBlockEntity be) {
        if (be.winner >= 0) {
            return be.winner == 2 ? Component.translatable("curling.newyearcountdown.draw").withStyle(ChatFormatting.GOLD)
                    : Component.translatable("curling.newyearcountdown.win", team(be.winner)).withStyle(ChatFormatting.GOLD);
        }
        MutableComponent c = Component.translatable("curling.newyearcountdown.board.turn", team(be.turn));
        c.append(Component.literal("   "));
        for (int t = 0; t < 2; t++) {
            int left = CurlingHouseBlockEntity.STONES - be.thrown[t];
            c.append(Component.literal("●".repeat(Math.max(0, left))).withStyle(t == 0 ? ChatFormatting.RED : ChatFormatting.YELLOW));
            c.append(Component.literal("●".repeat(CurlingHouseBlockEntity.STONES - Math.max(0, left))).withStyle(ChatFormatting.DARK_GRAY));
            if (t == 0) c.append(Component.literal("  "));
        }
        return c;
    }

    /** 지금 점수가 나는 스톤들 (서버의 점수 계산과 같은 규칙). */
    private static List<CurlingStoneEntity> counting(CurlingHouseBlockEntity be, Level level) {
        double cx = be.getBlockPos().getX() + 0.5, cz = be.getBlockPos().getZ() + 0.5;
        float lim = CurlingHouseBlockEntity.RADIUS + CurlingStoneEntity.RADIUS;
        List<CurlingStoneEntity> in = new ArrayList<>();
        for (CurlingStoneEntity s : level.getEntitiesOfClass(CurlingStoneEntity.class, new AABB(be.getBlockPos()).inflate(lim + 1, 2, lim + 1), x -> true)) {
            if (Math.hypot(s.getX() - cx, s.getZ() - cz) <= lim) in.add(s);
        }
        in.sort(Comparator.comparingDouble(s -> Math.hypot(s.getX() - cx, s.getZ() - cz)));
        List<CurlingStoneEntity> out = new ArrayList<>();
        for (CurlingStoneEntity s : in) {
            if (!out.isEmpty() && s.team() != out.get(0).team()) break;
            out.add(s);
        }
        return out;
    }

    private static void label(GeoBuffers out, PoseStack m, CameraRenderState camera, float x, float y, float z, Component text, float scale) {
        m.pushPose();
        m.translate(x, y, z);
        m.rotate(camera.orientation);
        m.scale(scale, -scale, scale);
        var font = Minecraft.getInstance().font;
        out.text(m, text, -font.width(text) / 2f, 0f, 0xFFFFFFFF, false, 0x90000000, FULL);
        m.popPose();
    }

    private static void ring(VertexConsumer vc, PoseStack.Pose e, float cx, float y, float cz, float r0, float r1, int color) {
        int seg = 24;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            vert(vc, e, cx + c0 * r0, y, cz + s0 * r0, color, FULL, 0.5f, 0.5f);
            vert(vc, e, cx + c1 * r0, y, cz + s1 * r0, color, FULL, 0.5f, 0.5f);
            vert(vc, e, cx + c1 * r1, y, cz + s1 * r1, color, FULL, 0.5f, 0.5f);
            vert(vc, e, cx + c0 * r1, y, cz + s0 * r1, color, FULL, 0.5f, 0.5f);
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose e, int light, float x0, float z0, float x1, float z1, float y, int color,
                             float u0, float v0, float u1, float v1) {
        vert(vc, e, x0, y, z0, color, light, u0, v0);
        vert(vc, e, x0, y, z1, color, light, u0, v1);
        vert(vc, e, x1, y, z1, color, light, u1, v1);
        vert(vc, e, x1, y, z0, color, light, u1, v0);
    }

    private static void vert(VertexConsumer vc, PoseStack.Pose e, float x, float y, float z, int color, int light, float u, float v) {
        vc.addVertex(e, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(e, 0f, 1f, 0f);
    }
}
