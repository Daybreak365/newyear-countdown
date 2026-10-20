package dev.newyear.countdown.client;

import net.minecraft.client.input.MouseButtonEvent;
import dev.newyear.countdown.NewYearCountdown;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

/** 인벤토리 화면 옆(InventorySideSlots)에 나타나는 오미쿠지 칸. 클릭하면 받아 둔 운세 보관함이 열린다 (한 번도 안 받았으면 나타나지 않는다). */
public class OmikujiSlotWidget extends AbstractWidget {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "textures/gui/omikuji_slot.png");
    private final AbstractContainerScreen<?> screen;

    public OmikujiSlotWidget(AbstractContainerScreen<?> screen) {
        super(0, 0, 22, 22, Component.translatable("omikuji.newyearcountdown.ui.slot", OmikujiBook.size()));
        this.screen = screen;
        setTooltip(Tooltip.create(Component.translatable("omikuji.newyearcountdown.ui.slot", OmikujiBook.size())));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int[] p = InventorySideSlots.pos(screen, 0);
        setX(p[0]);
        setY(p[1]);
        drawSlotFace(ctx, Minecraft.getInstance(), getX(), getY(), 22, isHovered(), 0f);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        Minecraft.getInstance().gui.setScreen(new OmikujiBookScreen());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        defaultButtonNarrationText(builder);
    }

    /** 칸 모양 (인벤토리 슬롯 느낌의 어두운 칸 + 금 테두리 + 오미쿠지 통 아이콘 + 개수). */
    public static void drawSlotFace(GuiGraphicsExtractor ctx, Minecraft mc, int x, int y, int s, boolean hover, float glow) {
        int border = hover ? 0xFFFFE08A : 0xFFB8893A;
        ctx.fill(x, y, x + s, y + s, 0xFF8B8B8B);
        ctx.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0xFF2A2018);
        ctx.fill(x + 2, y + 2, x + s - 2, y + s - 2, 0xFF3A2D20);
        ctx.outline(x, y, s, s, border);
        if (glow > 0f) {
            int g = ((int) (glow * 140) << 24) | 0xFFE08A;
            ctx.fill(x + 2, y + 2, x + s - 2, y + s - 2, g);
        }
        // 오미쿠지 통 아이콘
        int ix = x + (s - 16) / 2, iy = y + (s - 16) / 2;
        ctx.blit(RenderPipelines.GUI_TEXTURED, ICON, ix, iy, 0f, 0f, 16, 16, 16, 16);
        int n = OmikujiBook.extraCount(); // 메인 한 장은 칸 자체, 추가 뽑기 수는 +N
        if (n > 0) {
            String t = "+" + (n > 99 ? "99" : Integer.toString(n));
            ctx.text(mc.font, t, x + s - 1 - mc.font.width(t), y + s - 9, 0xFFFFFFFF, true);
        }
    }
}
