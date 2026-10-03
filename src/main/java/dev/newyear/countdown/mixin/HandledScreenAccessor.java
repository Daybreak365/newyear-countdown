package dev.newyear.countdown.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 인벤토리 패널 위치(레시피 북을 열면 옮겨짐)를 읽기 위한 접근자. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int newyearcountdown$getX();

    @Accessor("y")
    int newyearcountdown$getY();

    @Accessor("backgroundWidth")
    int newyearcountdown$getBackgroundWidth();
}
