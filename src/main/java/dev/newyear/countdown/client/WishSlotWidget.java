package dev.newyear.countdown.client;

import dev.newyear.countdown.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/** 인벤토리 오른쪽의 소원 기록 칸 (한 번이라도 소원을 빌었다면 나타난다). 오미쿠지 칸이 있으면 그 아래에 놓인다. */
public class WishSlotWidget extends ClickableWidget {
    private final HandledScreen<?> screen;

    public WishSlotWidget(HandledScreen<?> screen) {
        super(0, 0, 22, 22, Text.translatable("wish.newyearcountdown.ui.slot", WishBook.size()));
        this.screen = screen;
        setTooltip(Tooltip.of(Text.translatable("wish.newyearcountdown.ui.slot", WishBook.size())));
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        HandledScreenAccessor acc = (HandledScreenAccessor) screen;
        setX(acc.newyearcountdown$getX() + acc.newyearcountdown$getBackgroundWidth() + 4);
        setY(acc.newyearcountdown$getY() + 6 + (OmikujiBook.size() > 0 ? 26 : 0));
        int x = getX(), y = getY(), s = 22;
        int border = isHovered() ? 0xFFFFE08A : 0xFFB8893A;
        ctx.fill(x, y, x + s, y + s, 0xFF8B8B8B);
        ctx.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0xFF1C1626);
        ctx.fill(x + 2, y + 2, x + s - 2, y + s - 2, 0xFF2B2036);
        ctx.drawBorder(x, y, s, s, border);
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
            ctx.drawText(MinecraftClient.getInstance().textRenderer, t, x + s - 1 - MinecraftClient.getInstance().textRenderer.getWidth(t), y + s - 9, 0xFFFFFFFF, true);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        MinecraftClient.getInstance().setScreen(new WishBookScreen());
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
