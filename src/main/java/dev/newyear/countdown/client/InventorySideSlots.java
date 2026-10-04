package dev.newyear.countdown.client;

import dev.newyear.countdown.mixin.HandledScreenAccessor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/**
 * 인벤토리 화면에 붙는 모드 칸(오미쿠지 보관함, 소원 기록)의 위치.
 * 오른쪽은 바닐라 상태효과 목록이 쓰므로 패널 왼쪽에 세로로 놓고,
 * 레시피 북이 열려 왼쪽이 막히면 패널 위쪽에 가로로 놓는다.
 */
public final class InventorySideSlots {
    public static final int SIZE = 22;
    private static final int GAP = 4;

    private InventorySideSlots() {}

    /** index 번째 칸의 {x, y}. */
    public static int[] pos(AbstractContainerScreen<?> screen, int index) {
        HandledScreenAccessor acc = (HandledScreenAccessor) screen;
        int x0 = acc.newyearcountdown$getX(), y0 = acc.newyearcountdown$getY();
        // 레시피 북이 열리면 패널이 가운데에서 오른쪽으로 밀린다
        boolean bookOpen = screen instanceof InventoryScreen && x0 > (screen.width - acc.newyearcountdown$getBackgroundWidth()) / 2 + 20;
        if (bookOpen) return new int[]{x0 + 4 + index * (SIZE + GAP), y0 - SIZE - 2};
        return new int[]{x0 - SIZE - GAP, y0 + 6 + index * (SIZE + GAP)};
    }
}
