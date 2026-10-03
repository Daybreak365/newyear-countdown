package dev.newyear.countdown.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.function.Function;

/** 뒷배경을 가리지 않는 가산(additive) 발광 레이어: 깊이를 기록하지 않아 빛이 배경 위에 '더해지기만' 한다. */
public final class GlowLayer extends RenderLayer {
    private GlowLayer(String n, int sz) {
        super(n, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, net.minecraft.client.render.VertexFormat.DrawMode.QUADS, sz, false, true, () -> {}, () -> {});
        throw new IllegalStateException();
    }

    private static final Function<Identifier, RenderLayer> CACHE = Util.memoize(id -> RenderLayer.of(
            "newyearcountdown_glow",
            VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
            net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
            256, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(ENTITY_TRANSLUCENT_PROGRAM)
                    .texture(new net.minecraft.client.render.RenderPhase.Texture(id, false, false))
                    .transparency(LIGHTNING_TRANSPARENCY)   // SRC_ALPHA + ONE (가산)
                    .writeMaskState(COLOR_MASK)             // 깊이 기록 없음
                    .cull(DISABLE_CULLING)
                    .lightmap(DISABLE_LIGHTMAP)
                    .overlay(DISABLE_OVERLAY_COLOR)
                    .build(false)));

    public static RenderLayer get(Identifier tex) { return CACHE.apply(tex); }
}
