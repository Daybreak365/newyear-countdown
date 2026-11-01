package dev.newyear.countdown.client.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.mixin.SpecialModelRenderersAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 가 그린 동적 텍스처. 서버의 "texture" 연산을 받아 실제 GPU 텍스처(DynamicTexture)로 만들고,
 * 아이템 모델 newyearcountdown:wish_texture 가 그 그림을 바닐라 생성 아이템처럼 두께 1픽셀로 돌출시켜 그린다
 * (손·인벤토리·땅에 떨어진 아이템·아이템 디스플레이 모두). HUD 아이콘도 같은 텍스처를 쓴다.
 */
public final class DynamicTextures {
    private DynamicTextures() {}

    public static final class Tex {
        public final String id;
        public final Identifier location;
        public final int w, h;
        final boolean[] opaque;
        final DynamicTexture texture;

        Tex(String id, Identifier location, int w, int h, boolean[] opaque, DynamicTexture texture) {
            this.id = id;
            this.location = location;
            this.w = w;
            this.h = h;
            this.opaque = opaque;
            this.texture = texture;
        }

        boolean solid(int x, int y) {
            return x >= 0 && y >= 0 && x < w && y < h && opaque[y * w + x];
        }
    }

    private static final Map<String, Tex> TEXTURES = new HashMap<>();
    private static final Identifier MISSING = Identifier.withDefaultNamespace("textures/block/white_concrete.png");

    public static void register() {
        SpecialModelRenderersAccessor.newyearcountdown$idMapper().put(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "wish_texture"), Unbaked.MAP_CODEC);
    }

    public static Tex get(String id) {
        return id == null ? null : TEXTURES.get(id);
    }

    /** 서버 연산 {"op":"texture", id, w, h, pixels:[...], palette:"c=aarrggbb;..."} */
    public static void apply(JsonObject o) {
        String id = o.get("id").getAsString();
        int w = Math.max(1, Math.min(64, o.get("w").getAsInt())), h = Math.max(1, Math.min(64, o.get("h").getAsInt()));
        Map<Character, Integer> palette = new HashMap<>();
        for (String pair : o.get("palette").getAsString().split(";")) {
            if (pair.length() < 3 || pair.charAt(1) != '=') continue;
            try {
                palette.put(pair.charAt(0), (int) Long.parseLong(pair.substring(2), 16));
            } catch (NumberFormatException ignored) { }
        }
        List<String> rows = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("pixels")) rows.add(e.getAsString());
        NativeImage img = new NativeImage(w, h, true);
        boolean[] opaque = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            String row = y < rows.size() ? rows.get(y) : "";
            for (int x = 0; x < w; x++) {
                char ch = x < row.length() ? row.charAt(x) : '.';
                Integer argb = ch == '.' || ch == ' ' ? null : palette.get(ch);
                int c = argb == null ? 0 : argb;
                img.setPixel(x, y, c);
                opaque[y * w + x] = (c >>> 24) > 16;
            }
        }
        Identifier loc = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "wishai/" + id);
        Minecraft mc = Minecraft.getInstance();
        remove(id);
        DynamicTexture tex = new DynamicTexture(() -> "wishai texture " + id, img);
        mc.getTextureManager().register(loc, tex);
        TEXTURES.put(id, new Tex(id, loc, w, h, opaque, tex));
    }

    public static void remove(String id) {
        Tex old = TEXTURES.remove(id);
        if (old != null) Minecraft.getInstance().getTextureManager().release(old.location);
    }

    public static void clear() {
        for (String id : new ArrayList<>(TEXTURES.keySet())) remove(id);
    }

    /** 아이템에 입힌 텍스처 이름 (custom_data.wishai.texture). */
    public static String textureOf(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();
        return tag.getCompound("wishai").flatMap(w -> w.getString("texture")).orElse(null);
    }

    // ---------------------------------------------------------------- 아이템 모델

    public record Unbaked() implements SpecialModelRenderer.Unbaked<String> {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public SpecialModelRenderer<String> bake(SpecialModelRenderer.BakingContext ctx) {
            return new Renderer();
        }

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }
    }

    /** 바닐라 생성 아이템처럼: 앞·뒤 면 + 불투명 픽셀의 가장자리마다 옆면. 좌표는 0..1 (두께는 7.5/16..8.5/16). */
    static final class Renderer implements SpecialModelRenderer<String> {
        private static final float Z0 = 7.5f / 16f, Z1 = 8.5f / 16f;

        @Override
        public String extractArgument(ItemStack stack) {
            return textureOf(stack);
        }

        @Override
        public void getExtents(Consumer<Vector3fc> out) {
            for (int i = 0; i < 8; i++) out.accept(new Vector3f((i & 1), (i >> 1) & 1, (i & 4) == 0 ? Z0 : Z1));
        }

        @Override
        public void submit(String id, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
            Tex t = get(id);
            if (t == null) {
                // 아직 텍스처를 받지 못함: 흐린 종이 한 장
                collector.submitCustomGeometry(pose, RenderTypes.entityCutout(MISSING), (p, vc) -> {
                    quad(vc, p, 0.2f, 0.1f, 0.8f, 0.9f, Z1, 0, 0, 1, 1, 0, 0, 1, 0xFFB8B8C8, light, overlay);
                    quad(vc, p, 0.8f, 0.1f, 0.2f, 0.9f, Z0, 0, 0, 1, 1, 0, 0, -1, 0xFFB8B8C8, light, overlay);
                });
                return;
            }
            collector.submitCustomGeometry(pose, RenderTypes.entityCutout(t.location), (p, vc) -> mesh(t, vc, p, light, overlay));
        }

        private static void mesh(Tex t, VertexConsumer vc, PoseStack.Pose p, int light, int overlay) {
            int n = Math.max(t.w, t.h);
            float px = 1f / n;
            float ox = (n - t.w) * px / 2f, oy = (n - t.h) * px / 2f;
            float x0 = ox, x1 = ox + t.w * px, y0 = oy, y1 = oy + t.h * px;
            // 앞(+z)·뒤(-z): 텍스처 한 장 (v 는 위가 0)
            quad(vc, p, x0, y0, x1, y1, Z1, 0, 1, 1, 0, 0, 0, 1, 0xFFFFFFFF, light, overlay);
            quad(vc, p, x1, y0, x0, y1, Z0, 1, 1, 0, 0, 0, 0, -1, 0xFFFFFFFF, light, overlay);
            // 옆면: 불투명 픽셀이 투명 픽셀(또는 바깥)과 맞닿은 가장자리
            for (int y = 0; y < t.h; y++) {
                for (int x = 0; x < t.w; x++) {
                    if (!t.solid(x, y)) continue;
                    float lx = x0 + x * px, rx = lx + px;
                    float ty = y1 - y * px, by = ty - px;       // 텍스처 y 는 아래로, 모델 y 는 위로
                    float u = (x + 0.5f) / t.w, v = (y + 0.5f) / t.h;
                    if (!t.solid(x, y - 1)) side(vc, p, lx, ty, rx, ty, u, v, 0, 1, 0, light, overlay);
                    if (!t.solid(x, y + 1)) side(vc, p, rx, by, lx, by, u, v, 0, -1, 0, light, overlay);
                    if (!t.solid(x - 1, y)) side(vc, p, lx, by, lx, ty, u, v, -1, 0, 0, light, overlay);
                    if (!t.solid(x + 1, y)) side(vc, p, rx, ty, rx, by, u, v, 1, 0, 0, light, overlay);
                }
            }
        }

        /** 앞면 사각형: (ax,ay)-(bx,by) 를 z 평면에, uv 는 (u0,v0)-(u1,v1). */
        private static void quad(VertexConsumer vc, PoseStack.Pose p, float ax, float ay, float bx, float by, float z,
                                 float u0, float v0, float u1, float v1, float nx, float ny, float nz, int color, int light, int overlay) {
            v(vc, p, ax, ay, z, u0, v0, nx, ny, nz, color, light, overlay);
            v(vc, p, bx, ay, z, u1, v0, nx, ny, nz, color, light, overlay);
            v(vc, p, bx, by, z, u1, v1, nx, ny, nz, color, light, overlay);
            v(vc, p, ax, by, z, u0, v1, nx, ny, nz, color, light, overlay);
        }

        /** 두께 방향 옆면: 모서리 (ax,ay)→(bx,by) 를 Z0..Z1 로 늘린다. 한 픽셀의 색을 그대로 쓴다. */
        private static void side(VertexConsumer vc, PoseStack.Pose p, float ax, float ay, float bx, float by, float u, float v,
                                 float nx, float ny, float nz, int light, int overlay) {
            v(vc, p, ax, ay, Z0, u, v, nx, ny, nz, 0xFFFFFFFF, light, overlay);
            v(vc, p, bx, by, Z0, u, v, nx, ny, nz, 0xFFFFFFFF, light, overlay);
            v(vc, p, bx, by, Z1, u, v, nx, ny, nz, 0xFFFFFFFF, light, overlay);
            v(vc, p, ax, ay, Z1, u, v, nx, ny, nz, 0xFFFFFFFF, light, overlay);
        }

        private static void v(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v,
                              float nx, float ny, float nz, int color, int light, int overlay) {
            vc.addVertex(p, x, y, z).setColor(color).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
        }
    }
}
