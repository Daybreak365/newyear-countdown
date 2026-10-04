package dev.newyear.countdown.client;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/** 인벤토리 오른쪽의 소원 기록 칸 (한 번이라도 소원을 빌었다면 나타난다). 오미쿠지 칸이 있으면 그 아래에 놓인다. */
public class WishSlotWidget extends AbstractWidget {
    private final AbstractContainerScreen<?> screen;

    public WishSlotWidget(AbstractContainerScreen<?> screen) {
        super(0, 0, 22, 22, Component.translatable("wish.newyearcountdown.ui.slot", WishBook.size()));
        this.screen = screen;
        setTooltip(Tooltip.create(Component.translatable("wish.newyearcountdown.ui.slot", WishBook.size())));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int[] p = InventorySideSlots.pos(screen, OmikujiBook.size() > 0 ? 1 : 0);
        setX(p[0]);
        setY(p[1]);
        int x = getX(), y = getY(), s = 22;
        int border = isHovered() ? 0xFFFFE08A : 0xFFB8893A;
        ctx.fill(x, y, x + s, y + s, 0xFF8B8B8B);
        ctx.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0xFF1C1626);
        ctx.fill(x + 2, y + 2, x + s - 2, y + s - 2, 0xFF2B2036);
        ctx.outline(x, y, s, s, border);
        // 작은 연등 아이콘
        int cx = x + s / 2, top = y + 4;
        for (int i = 0; i < 12; i++) {
            int half = 3 + i / 3;
            ctx.fill(cx - half, top + i, cx + half, top + i + 1, 0xFFFFC878);
        }
        ctx.fill(cx - 3, top - 2, cx + 3, top, 0xFFD9A441);
        ctx.fill(cx - 5, top + 12, cx + 5, top + 14, 0xFF7A5230);
        ctx.fill(cx - 1, top + 9, cx + 1, top + 12, 0xFFFFF0B0);
        int n = WishBook.size();
        if (n > 1) {
            String t = n > 99 ? "99+" : Integer.toString(n);
            ctx.text(Minecraft.getInstance().font, t, x + s - 1 - Minecraft.getInstance().font.width(t), y + s - 9, 0xFFFFFFFF, true);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        Minecraft.getInstance().gui.setScreen(new WishBookScreen());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        defaultButtonNarrationText(builder);
    }
}
