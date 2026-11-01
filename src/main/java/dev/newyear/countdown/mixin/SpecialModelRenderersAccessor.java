package dev.newyear.countdown.mixin;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 특수 아이템 렌더러 종류 표에 모드 렌더러(AI 동적 텍스처)를 등록하기 위한 접근자. */
@Mixin(SpecialModelRenderers.class)
public interface SpecialModelRenderersAccessor {
    @Accessor("ID_MAPPER")
    static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>> newyearcountdown$idMapper() {
        throw new AssertionError();
    }
}
