package dev.newyear.countdown.client;

import dev.newyear.countdown.SetTargetPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 설정 화면.
 * 숨김 디버그: 화면 상단 제목을 7번 연속 클릭하면 비밀번호 입력창이 뜨고, 통과하면 디버그 섹션이 열린다.
 * 목표 시각을 임의의 시점으로 바꿔 카운트다운과 종료 연출을 즉시 테스트할 수 있다.
 */
public class SettingsScreen extends Screen {
    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int UNLOCK_CLICKS = 7;
    private static final long CLICK_WINDOW_MS = 3000;

    private final Screen parent;
    private TextFieldWidget dateField;
    private Text message = Text.empty();
    private int titleClicks = 0;
    private long lastTitleClick = 0;

    public SettingsScreen(Screen parent) {
        super(Text.translatable("screen.newyearcountdown.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientConfig cfg = ClientConfig.get();
        int cx = width / 2;
        int w = 200;
        int x = cx - w / 2;
        int y = 36;

        addDrawableChild(ButtonWidget.builder(hudLabel(cfg), b -> {
            cfg.enabled = !cfg.enabled;
            b.setMessage(hudLabel(cfg));
        }).dimensions(x, y, w, 20).build());
        y += 24;

        int hw = (w - 6) / 2;
        addDrawableChild(ButtonWidget.builder(positionLabel(cfg), b -> {
            cfg.position = cfg.position.next();
            b.setMessage(positionLabel(cfg));
        }).dimensions(x, y, hw, 20).build());
        addDrawableChild(ButtonWidget.builder(scaleLabel(cfg), b -> {
            cfg.scaleIndex = (cfg.scaleIndex + 1) % ClientConfig.SCALES.length;
            b.setMessage(scaleLabel(cfg));
        }).dimensions(x + hw + 6, y, hw, 20).build());
        y += 24;

        addDrawableChild(ButtonWidget.builder(themeLabel(cfg), b -> {
            cfg.theme = cfg.theme.next();
            b.setMessage(themeLabel(cfg));
        }).dimensions(x, y, hw, 20).build());
        addDrawableChild(ButtonWidget.builder(tickLabel(cfg), b -> {
            cfg.tickSound = !cfg.tickSound;
            b.setMessage(tickLabel(cfg));
        }).dimensions(x + hw + 6, y, hw, 20).build());
        y += 28;

        if (DebugAccess.unlocked()) {
            // ---- 숨겨진 디버그 섹션 ----
            y += 12; // 헤더 자리
            dateField = new TextFieldWidget(textRenderer, x, y, w, 20, Text.empty());
            dateField.setMaxLength(19);
            ZonedDateTime t = Instant.ofEpochMilli(ClientCountdown.targetMs()).atZone(ClientCountdown.zone());
            dateField.setText(INPUT_FORMAT.format(t));
            addDrawableChild(dateField);
            y += 24;

            int bw = (w - 6) / 2;
            addDrawableChild(ButtonWidget.builder(Text.translatable("debug.newyearcountdown.apply"), b -> applyDateField())
                    .dimensions(x, y, bw, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.translatable("debug.newyearcountdown.reset"), b -> {
                send(SetTargetPayload.RESET, 0, -1);
                message = Text.empty();
            }).dimensions(x + bw + 6, y, bw, 20).build());
            y += 24;

            addDrawableChild(quick("debug.newyearcountdown.in10s", 10, x, y, bw));
            addDrawableChild(quick("debug.newyearcountdown.in1m", 60, x + bw + 6, y, bw));
            y += 24;
            addDrawableChild(quick("debug.newyearcountdown.in10m", 600, x, y, bw));
            addDrawableChild(quick("debug.newyearcountdown.in1h", 3600, x + bw + 6, y, bw));
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.newyearcountdown.done"), b -> close())
                .dimensions(x, height - 28, w, 20).build());
    }

    private ButtonWidget quick(String key, int seconds, int x, int y, int w) {
        return ButtonWidget.builder(Text.translatable(key), b -> {
            send(SetTargetPayload.RELATIVE_SECONDS, seconds, System.currentTimeMillis() + seconds * 1000L);
            message = Text.empty();
        }).dimensions(x, y, w, 20).build();
    }

    private void applyDateField() {
        try {
            long ms = LocalDateTime.parse(dateField.getText().trim(), INPUT_FORMAT)
                    .atZone(ClientCountdown.zone()).toInstant().toEpochMilli();
            send(SetTargetPayload.ABSOLUTE, ms, ms);
            message = Text.empty();
        } catch (Exception e) {
            message = Text.translatable("debug.newyearcountdown.bad_format").styled(s -> s.withColor(0xFF6B6B));
        }
    }

    /** 서버로 보낼 수 있으면 서버에 요청(전원에게 반영), 아니면 내 화면에만 로컬 적용. */
    private void send(int mode, long value, long localTargetMs) {
        if (ClientPlayNetworking.canSend(SetTargetPayload.ID)) {
            ClientPlayNetworking.send(new SetTargetPayload(mode, value));
        } else if (mode == SetTargetPayload.RESET) {
            ClientCountdown.reset();
        } else {
            ClientCountdown.setLocalDebug(localTargetMs);
            message = Text.translatable("debug.newyearcountdown.local_only").styled(s -> s.withColor(0xFFC857));
        }
    }

    private static Text hudLabel(ClientConfig cfg) {
        return Text.translatable("screen.newyearcountdown.hud",
                Text.translatable(cfg.enabled ? "screen.newyearcountdown.on" : "screen.newyearcountdown.off"));
    }

    private static Text positionLabel(ClientConfig cfg) {
        return Text.translatable("screen.newyearcountdown.position", Text.translatable(cfg.position.key()));
    }

    private static Text themeLabel(ClientConfig cfg) {
        return Text.translatable("screen.newyearcountdown.theme", Text.translatable(cfg.theme.key()));
    }

    private static Text tickLabel(ClientConfig cfg) {
        return Text.translatable("screen.newyearcountdown.tick",
                Text.translatable(cfg.tickSound ? "screen.newyearcountdown.on" : "screen.newyearcountdown.off"));
    }

    private static Text scaleLabel(ClientConfig cfg) {
        return Text.translatable("screen.newyearcountdown.scale", Math.round(cfg.scale() * 100) + "%");
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFFFF);

        if (DebugAccess.unlocked()) {
            int x = width / 2 - 100;
            int hy = 36 + 24 * 2 + 28 + 2;
            Text header = Text.translatable("debug.newyearcountdown.header");
            ctx.drawTextWithShadow(textRenderer, header, x, hy, 0xFFFF6B6B);
            ctx.drawTextWithShadow(textRenderer, Text.translatable("debug.newyearcountdown.field_hint", ClientCountdown.zone().getId()),
                    x + textRenderer.getWidth(header) + 6, hy, 0xFF777B88);

            long rem = ClientCountdown.targetMs() - ClientCountdown.now();
            Text status = Text.translatable("debug.newyearcountdown.status", CountdownHud.formatTarget(), formatRemaining(rem));
            ctx.drawCenteredTextWithShadow(textRenderer, status, width / 2, height - 52, 0xFFA0A4B0);
            if (!message.getString().isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer, message, width / 2, height - 42, 0xFFFFFFFF);
            }
        }
    }

    private static String formatRemaining(long ms) {
        if (ms <= 0) return "0";
        Duration d = Duration.ofMillis(ms);
        return String.format("%dd %02d:%02d:%02d", d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 제목 영역을 연속 클릭하면 디버그 해제
        int tw = textRenderer.getWidth(title);
        boolean onTitle = mouseY >= 10 && mouseY <= 27 && Math.abs(mouseX - width / 2.0) <= tw / 2.0 + 6;
        if (onTitle && button == 0 && !DebugAccess.unlocked()) {
            long now = System.currentTimeMillis();
            titleClicks = (now - lastTitleClick > CLICK_WINDOW_MS) ? 1 : titleClicks + 1;
            lastTitleClick = now;
            if (titleClicks >= UNLOCK_CLICKS) {
                titleClicks = 0;
                MinecraftClient.getInstance().setScreen(new PasswordScreen(this));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        ClientConfig.save();
        MinecraftClient.getInstance().setScreen(parent);
    }
}
