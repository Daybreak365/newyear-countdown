package dev.newyear.countdown.client;


import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/** 내가 빈 소원 모음. 연등이 하늘로 사라져도 기록은 남아 여기서 다시 볼 수 있다 (최근 것부터). */
public class WishBookScreen extends Screen {
    private static final int PER_PAGE = 4;
    private static final int CARD_H = 44;
    private int page;
    private Button prev, next;
    private final long openNanos = System.nanoTime();

    public WishBookScreen() {
        super(Component.translatable("wish.newyearcountdown.ui.book", WishBook.size()));
    }

    private int pages() {
        return Math.max(1, (WishBook.size() + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    protected void init() {
        int cx = width / 2, by = height / 2 + PER_PAGE * (CARD_H + 4) / 2 + 12;
        prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> go(-1)).bounds(cx - 82, by, 30, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("wish.newyearcountdown.ui.close"), b -> onClose()).bounds(cx - 46, by, 92, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> go(1)).bounds(cx + 52, by, 30, 20).build());
        update();
    }

    private void go(int d) {
        page = Mth.clamp(page + d, 0, pages() - 1);
        update();
    }

    private void update() {
        prev.active = page > 0;
        next.active = page < pages() - 1;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_LEFT) { go(-1); return true; }
        if (keyCode == InputConstants.KEY_RIGHT) { go(1); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        float t = (System.nanoTime() - openNanos) / 1.0e9f;
        int cx = width / 2;
        int listH = PER_PAGE * (CARD_H + 4);
        int top = height / 2 - listH / 2;
        ctx.centeredText(font, Component.translatable("wish.newyearcountdown.ui.book", WishBook.size()), cx, top - 22, 0xFFF3D98A);
        WishScreen.drawLantern(ctx, cx - 120, top - 30, t, 0f);
        WishScreen.drawLantern(ctx, cx + 120, top - 30, t, 2.1f);

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy.MM.dd HH:mm");
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= WishBook.size()) break;
            WishBook.Wish w = WishBook.newest(idx);
            int y = top + i * (CARD_H + 4);
            int x0 = cx - 130, x1 = cx + 130;
            ctx.fillGradient(x0, y, x1, y + CARD_H, 0xE0201A2C, 0xE0382638);
            ctx.outline(x0, y, x1 - x0, CARD_H, 0xFF8A6A3A);
            ctx.fill(x0 + 1, y + 1, x0 + 4, y + CARD_H - 1, 0xFFE0A24A);
            ctx.text(font, fmt.format(new Date(w.time)), x0 + 10, y + 4, 0xFF9C8A70, false);
            int ty = y + 16;
            int lines = 0;
            for (FormattedCharSequence l : font.split(Component.literal(w.text), x1 - x0 - 20)) {
                if (++lines > 3) break;
                ctx.text(font, l, x0 + 10, ty, 0xFFFFE8C0, false);
                ty += 9;
            }
        }
        if (WishBook.size() == 0) {
            ctx.centeredText(font, Component.translatable("wish.newyearcountdown.ui.empty"), cx, height / 2, 0xFFB8A890);
        }
        ctx.centeredText(font, Component.literal((page + 1) + " / " + pages()), cx, top + listH + 2, 0xFFB8A890);
    }
}
