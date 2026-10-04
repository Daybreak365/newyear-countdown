package dev.newyear.countdown.client;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.newyear.countdown.ModReg;
import java.util.function.Function;
import net.minecraft.util.Util;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** 뒷배경을 가리지 않는 가산(additive) 발광 레이어: 깊이를 기록하지 않아 빛이 배경 위에 '더해지기만' 한다. */
public final class GlowLayer {
    private GlowLayer() {}

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.ENTITY_EMISSIVE_SNIPPET, RenderPipelines.EYES_SNIPPET)
                    .withLocation(ModReg.id("pipeline/glow"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))   // SRC_ALPHA + ONE (가산)
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))   // 깊이 기록 없음
                    .withCull(false)
                    .build());

    private static final Function<Identifier, RenderType> CACHE = Util.memoize(id -> RenderType.create(
            "newyearcountdown_glow",
            RenderSetup.builder(PIPELINE).withTexture("Sampler0", id).sortOnUpload().createRenderSetup()));

    public static RenderType get(Identifier tex) { return CACHE.apply(tex); }
}
