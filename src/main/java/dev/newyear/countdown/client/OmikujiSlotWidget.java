package dev.newyear.countdown.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;

/** 인벤토리 화면 옆(InventorySideSlots)에 나타나는 오미쿠지 칸. 클릭하면 받아 둔 운세 보관함이 열린다 (한 번도 안 받았으면 나타나지 않는다). */
public class OmikujiSlotWidget extends ClickableWidget {
    private final HandledScreen<?> screen;

    public OmikujiSlotWidget(HandledScreen<?> screen) {
        super(0, 0, 22, 22, Text.translatable("omikuji.newyearcountdown.ui.slot", OmikujiBook.size()));
        this.screen = screen;
        setTooltip(Tooltip.of(Text.translatable("omikuji.newyearcountdown.ui.slot", OmikujiBook.size())));
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int[] p = InventorySideSlots.pos(screen, 0);
        setX(p[0]);
        setY(p[1]);
        drawSlotFace(ctx, MinecraftClient.getInstance(), getX(), getY(), 22, isHovered(), 0f);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        MinecraftClient.getInstance().setScreen(new OmikujiBookScreen());
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    /** 칸 모양 (인벤토리 슬롯 느낌의 어두운 칸 + 금 테두리 + 작은 종이 아이콘 + 개수). */
    public static void drawSlotFace(DrawContext ctx, MinecraftClient mc, int x, int y, int s, boolean hover, float glow) {
        int border = hover ? 0xFFFFE08A : 0xFFB8893A;
        ctx.fill(x, y, x + s, y + s, 0xFF8B8B8B);
        ctx.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0xFF2A2018);
        ctx.fill(x + 2, y + 2, x + s - 2, y + s - 2, 0xFF3A2D20);
        ctx.drawBorder(x, y, s, s, border);
        if (glow > 0f) {
            int g = ((int) (glow * 140) << 24) | 0xFFE08A;
            ctx.fill(x + 2, y + 2, x + s - 2, y + s - 2, g);
        }
        // 종이 아이콘
        int px = x + s / 2 - 5, py = y + 3;
        ctx.fill(px, py, px + 10, py + 16, 0xFFF2E6C8);
        ctx.fill(px, py, px + 10, py + 4, 0xFF9B2C2C);
        ctx.fill(px + 1, py + 5, px + 9, py + 6, 0xFFC9A24A);
        int last = OmikujiBook.size() > 0 ? OmikujiPaper.fortuneColor(OmikujiBook.main().result) : 0xFF888888;
        ctx.fill(px + 2, py + 8, px + 8, py + 10, last);
        ctx.fill(px + 2, py + 12, px + 8, py + 13, 0xFF8A7656);
        int n = OmikujiBook.extraCount(); // 메인 한 장은 칸 자체, 추가 뽑기 수는 +N
        if (n > 0) {
            String t = "+" + (n > 99 ? "99" : Integer.toString(n));
            ctx.drawText(mc.textRenderer, t, x + s - 1 - mc.textRenderer.getWidth(t), y + s - 9, 0xFFFFFFFF, true);
        }
    }
}
