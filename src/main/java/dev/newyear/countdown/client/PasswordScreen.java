package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** 숨김 기능 잠금 해제용 비밀번호 입력 화면. */
public class PasswordScreen extends Screen {
    private final Screen parent;
    private EditBox field;
    private Component error = Component.empty();

    public PasswordScreen(Screen parent) {
        super(Component.translatable("screen.newyearcountdown.password.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        field = new EditBox(font, cx - 100, height / 2 - 10, 200, 20, Component.empty());
        field.setMaxLength(32);
        // 입력한 글자를 별표로 가린다
        field.addFormatter((text, firstIndex) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(field);
        setInitialFocus(field);

        int bw = 97;
        addRenderableWidget(Button.builder(Component.translatable("screen.newyearcountdown.password.ok"), b -> submit())
                .bounds(cx - 100, height / 2 + 18, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.newyearcountdown.password.cancel"), b -> onClose())
                .bounds(cx + 3, height / 2 + 18, bw, 20).build());
    }

    private void submit() {
        if (DebugAccess.tryUnlock(field.getValue())) {
            Minecraft.getInstance().gui.setScreen(parent); // 설정 화면이 다시 열리며 숨김 섹션이 나타난다
        } else {
            error = Component.translatable("screen.newyearcountdown.password.wrong").withStyle(s -> s.withColor(0xFF6B6B));
            field.setValue("");
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font, title, width / 2, height / 2 - 34, 0xFFFFFFFF);
        if (!error.getString().isEmpty()) {
            ctx.centeredText(font, error, width / 2, height / 2 + 46, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
