package dev.newyear.countdown.client;

import dev.newyear.countdown.wish.WishLanternEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** 소원 연등 렌더러: 부풀어 오르는 연출, 바람에 흔들림, 천천히 도는 몸통, 종이 위에 적힌 소원 글씨, 높이 올라가면 작아지며 사라짐. */
public class WishLanternRenderer extends EntityRenderer<WishLanternEntity> {
    private static final int MAX_LINES = 12;

    public WishLanternRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
    }

    @Override
    public Identifier getTexture(WishLanternEntity entity) {
        return WishLanternModel.PAPER;
    }

    @Override
    public boolean shouldRender(WishLanternEntity entity, net.minecraft.client.render.Frustum frustum, double x, double y, double z) {
        return true; // 아주 높이 떠 있어도 (넓은 상자로) 컬링되지 않게
    }

    @Override
    public void render(WishLanternEntity e, float yaw, float tickDelta, MatrixStack m, VertexConsumerProvider providers, int light) {
        float age = e.life + tickDelta;
        float seed = (e.getUuid().hashCode() & 0xFFFF) / 65535f;

        // 부풀기 → 비행 → 사라지기 전 작아짐
        float inflate = MathHelper.clamp(age / WishLanternEntity.INFLATE_TICKS, 0f, 1f);
        inflate = inflate * inflate * (3f - 2f * inflate);
        float scale = 0.45f + 0.55f * inflate;
        double alt = e.getY() - e.getStartY();
        float fadeStart = WishLanternEntity.VANISH_HEIGHT - WishLanternEntity.FADE_LENGTH;
        float fade = MathHelper.clamp(((float) alt - fadeStart) / WishLanternEntity.FADE_LENGTH, 0f, 1f);
        scale *= 1f - fade * fade * 0.9f;

        float spin = age * 0.9f + seed * 360f;
        float tiltZ = 0.06f * MathHelper.sin(age * 0.07f + seed * 6.28f) * (0.4f + 0.6f * inflate);
        float tiltX = 0.045f * MathHelper.sin(age * 0.053f + seed * 12.5f) * (0.4f + 0.6f * inflate);

        drawGlow(e, m, providers, scale, age, seed, (float) alt);

        m.push();
        m.multiply(RotationAxis.POSITIVE_X.rotation(tiltX));
        m.multiply(RotationAxis.POSITIVE_Z.rotation(tiltZ));
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(spin));
        m.scale(scale, scale, scale);

        WishLanternModel.draw(providers, m, e.getColorRgb(), age, seed);
        drawWish(e, m, providers, spin);

        m.pop();
    }

    /**
     * 연등 둘레의 부드러운 빛 번짐(후광). 카메라를 향한 둥근 그라데이션을 겹쳐 그린다.
     * 멀어질수록 빛이 상대적으로 커져서, 아주 멀리서는 반짝이는 별처럼 보이다가 천천히 꺼진다.
     */
    private void drawGlow(WishLanternEntity e, MatrixStack m, VertexConsumerProvider providers, float scale,
                          float age, float seed, float alt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        double dx = cam.x - e.getX(), dy = cam.y - e.getY(), dz = cam.z - e.getZ();
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float flick = 0.9f + 0.1f * MathHelper.sin(age * 1.7f + seed * 9f) + 0.05f * MathHelper.sin(age * 3.3f + seed * 4f);

        // 사라지기 직전에는 별빛처럼 천천히 어두워진다
        float endFade = MathHelper.clamp((WishLanternEntity.VANISH_HEIGHT - alt) / 25f, 0f, 1f);
        float appear = MathHelper.clamp(age / 24f, 0f, 1f);
        float far = Math.max(1f, dist / 28f);          // 멀수록 빛이 상대적으로 커진다

        net.minecraft.client.render.VertexConsumer vc = providers.getBuffer(
                GlowLayer.get(WishLanternModel.GLOW));
        m.push();
        m.translate(0, 0.62f * scale, 0);
        m.multiply(mc.gameRenderer.getCamera().getRotation());
        net.minecraft.client.util.math.MatrixStack.Entry en = m.peek();
        float[][] layers = {{3.4f, 0.07f}, {1.9f, 0.13f}, {0.9f, 0.24f}}; // {반지름(칸), 투명도}
        int rgb = e.getColorRgb();
        int r = Math.min(255, (rgb >> 16 & 255) + 20), g = Math.min(255, (rgb >> 8 & 255) + 10), b = rgb & 255;
        for (float[] l : layers) {
            float rad = l[0] * Math.max(scale, 0.25f) * far * (0.5f + 0.5f * appear);
            int al = (int) (255 * l[1] * flick * endFade * appear);
            if (al <= 0) continue;
            int col = (al << 24) | (r << 16) | (g << 8) | b;
            glowVert(vc, en, -rad, -rad, 0f, 0f, col);
            glowVert(vc, en, -rad, rad, 0f, 1f, col);
            glowVert(vc, en, rad, rad, 1f, 1f, col);
            glowVert(vc, en, rad, -rad, 1f, 0f, col);
        }
        m.pop();
    }

    private static void glowVert(net.minecraft.client.render.VertexConsumer vc, MatrixStack.Entry en, float x, float y, float u, float v, int color) {
        vc.vertex(en, x, y, 0f).color(color).texture(u, 1f - v).overlay(net.minecraft.client.render.OverlayTexture.DEFAULT_UV)
                .light(0xF000F0).normal(en, 0f, 0f, 1f);
    }

    /** 카메라를 향한 두 면의 종이 위에 소원을 쓴다. */
    private void drawWish(WishLanternEntity e, MatrixStack m, VertexConsumerProvider providers, float spinDeg) {
        String wish = e.getWish();
        if (wish == null || wish.isEmpty()) return;
        TextRenderer tr = getTextRenderer();
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        double dx = cam.x - e.getX(), dz = cam.z - e.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-3) return;
        // 너무 멀리서는 글씨를 그리지 않는다 (어차피 읽을 수 없다)
        if (len > 60) return;

        List<OrderedText> lines = tr.wrapLines(Text.literal(wish), 44);
        if (lines.size() > MAX_LINES) lines = lines.subList(0, MAX_LINES);
        float spinRad = (float) Math.toRadians(spinDeg);
        float midY = 0.64f;
        float ap = WishLanternModel.apothemAt(midY);

        for (int k = 0; k < 4; k++) {
            float theta = (float) (k * Math.PI / 2);
            float a = theta + spinRad; // 면이 월드에서 향하는 방향 (+Z 에서 +X 쪽으로 잰 각)
            double dot = (Math.sin(a) * dx + Math.cos(a) * dz) / len;
            if (dot < 0.15) continue;

            m.push();
            m.multiply(RotationAxis.POSITIVE_Y.rotation(theta));
            m.translate(0, midY, ap + 0.006f);
            m.multiply(RotationAxis.POSITIVE_X.rotation(-0.02f)); // 위로 갈수록 좁아지는 종이 면에 맞춰 살짝 기울인다
            float s = 0.0082f;
            m.scale(s, -s, s);
            int total = lines.size();
            float y = -total * 9f / 2f;
            for (OrderedText line : lines) {
                float w = tr.getWidth(line);
                tr.draw(line, -w / 2f, y, 0xFF5A1C10, false, m.peek().getPositionMatrix(), providers,
                        TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
                y += 9f;
            }
            m.pop();
        }
    }

}
