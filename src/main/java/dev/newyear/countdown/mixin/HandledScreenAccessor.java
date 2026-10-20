package dev.newyear.countdown.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 인벤토리 패널 위치(레시피 북을 열면 옮겨짐)를 읽기 위한 접근자. */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
    @Accessor("leftPos")
    int newyearcountdown$getX();

    @Accessor("topPos")
    int newyearcountdown$getY();

    @Accessor("imageWidth")
    int newyearcountdown$getBackgroundWidth();
}
