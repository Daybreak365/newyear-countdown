package dev.newyear.countdown.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** 숨김 기능 잠금 해제용 비밀번호 입력 화면. */
public class PasswordScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget field;
    private Text error = Text.empty();

    public PasswordScreen(Screen parent) {
        super(Text.translatable("screen.newyearcountdown.password.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        field = new TextFieldWidget(textRenderer, cx - 100, height / 2 - 10, 200, 20, Text.empty());
        field.setMaxLength(32);
        // 입력한 글자를 별표로 가린다
        field.setRenderTextProvider((text, firstIndex) -> OrderedText.styledForwardsVisitedString("*".repeat(text.length()), Style.EMPTY));
        addDrawableChild(field);
        setInitialFocus(field);

        int bw = 97;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.newyearcountdown.password.ok"), b -> submit())
                .dimensions(cx - 100, height / 2 + 18, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.newyearcountdown.password.cancel"), b -> close())
                .dimensions(cx + 3, height / 2 + 18, bw, 20).build());
    }

    private void submit() {
        if (DebugAccess.tryUnlock(field.getText())) {
            MinecraftClient.getInstance().setScreen(parent); // 설정 화면이 다시 열리며 숨김 섹션이 나타난다
        } else {
            error = Text.translatable("screen.newyearcountdown.password.wrong").styled(s -> s.withColor(0xFF6B6B));
            field.setText("");
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 34, 0xFFFFFFFF);
        if (!error.getString().isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, error, width / 2, height / 2 + 46, 0xFFFFFFFF);
        }
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
