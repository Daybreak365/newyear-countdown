package dev.newyear.countdown.client;

import net.minecraft.client.input.MouseButtonEvent;
import dev.newyear.countdown.SetTargetPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
    private EditBox dateField;
    private Component message = Component.empty();
    private int titleClicks = 0;
    private long lastTitleClick = 0;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("screen.newyearcountdown.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientConfig cfg = ClientConfig.get();
        int cx = width / 2;
        int w = 200;
        int x = cx - w / 2;
        int y = 36;

        addRenderableWidget(Button.builder(hudLabel(cfg), b -> {
            cfg.enabled = !cfg.enabled;
            b.setMessage(hudLabel(cfg));
        }).bounds(x, y, w, 20).build());
        y += 24;

        int hw = (w - 6) / 2;
        addRenderableWidget(Button.builder(positionLabel(cfg), b -> {
            cfg.position = cfg.position.next();
            b.setMessage(positionLabel(cfg));
        }).bounds(x, y, hw, 20).build());
        addRenderableWidget(Button.builder(scaleLabel(cfg), b -> {
            cfg.scaleIndex = (cfg.scaleIndex + 1) % ClientConfig.SCALES.length;
            b.setMessage(scaleLabel(cfg));
        }).bounds(x + hw + 6, y, hw, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(themeLabel(cfg), b -> {
            cfg.theme = cfg.theme.next();
            b.setMessage(themeLabel(cfg));
        }).bounds(x, y, hw, 20).build());
        addRenderableWidget(Button.builder(tickLabel(cfg), b -> {
            cfg.tickSound = !cfg.tickSound;
            b.setMessage(tickLabel(cfg));
        }).bounds(x + hw + 6, y, hw, 20).build());
        y += 28;

        if (DebugAccess.unlocked()) {
            // ---- 숨겨진 디버그 섹션 ----
            y += 12; // 헤더 자리
            dateField = new EditBox(font, x, y, w, 20, Component.empty());
            dateField.setMaxLength(19);
            ZonedDateTime t = Instant.ofEpochMilli(ClientCountdown.targetMs()).atZone(ClientCountdown.zone());
            dateField.setValue(INPUT_FORMAT.format(t));
            addRenderableWidget(dateField);
            y += 24;

            int bw = (w - 6) / 2;
            addRenderableWidget(Button.builder(Component.translatable("debug.newyearcountdown.apply"), b -> applyDateField())
                    .bounds(x, y, bw, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("debug.newyearcountdown.reset"), b -> {
                send(SetTargetPayload.RESET, 0, -1);
                message = Component.empty();
            }).bounds(x + bw + 6, y, bw, 20).build());
            y += 24;

            addRenderableWidget(quick("debug.newyearcountdown.in10s", 10, x, y, bw));
            addRenderableWidget(quick("debug.newyearcountdown.in1m", 60, x + bw + 6, y, bw));
            y += 24;
            addRenderableWidget(quick("debug.newyearcountdown.in10m", 600, x, y, bw));
            addRenderableWidget(quick("debug.newyearcountdown.in1h", 3600, x + bw + 6, y, bw));
        }

        addRenderableWidget(Button.builder(Component.translatable("screen.newyearcountdown.done"), b -> onClose())
                .bounds(x, height - 28, w, 20).build());
    }

    private Button quick(String key, int seconds, int x, int y, int w) {
        return Button.builder(Component.translatable(key), b -> {
            send(SetTargetPayload.RELATIVE_SECONDS, seconds, System.currentTimeMillis() + seconds * 1000L);
            message = Component.empty();
        }).bounds(x, y, w, 20).build();
    }

    private void applyDateField() {
        try {
            long ms = LocalDateTime.parse(dateField.getValue().trim(), INPUT_FORMAT)
                    .atZone(ClientCountdown.zone()).toInstant().toEpochMilli();
            send(SetTargetPayload.ABSOLUTE, ms, ms);
            message = Component.empty();
        } catch (Exception e) {
            message = Component.translatable("debug.newyearcountdown.bad_format").withStyle(s -> s.withColor(0xFF6B6B));
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
            message = Component.translatable("debug.newyearcountdown.local_only").withStyle(s -> s.withColor(0xFFC857));
        }
    }

    private static Component hudLabel(ClientConfig cfg) {
        return Component.translatable("screen.newyearcountdown.hud",
                Component.translatable(cfg.enabled ? "screen.newyearcountdown.on" : "screen.newyearcountdown.off"));
    }

    private static Component positionLabel(ClientConfig cfg) {
        return Component.translatable("screen.newyearcountdown.position", Component.translatable(cfg.position.key()));
    }

    private static Component themeLabel(ClientConfig cfg) {
        return Component.translatable("screen.newyearcountdown.theme", Component.translatable(cfg.theme.key()));
    }

    private static Component tickLabel(ClientConfig cfg) {
        return Component.translatable("screen.newyearcountdown.tick",
                Component.translatable(cfg.tickSound ? "screen.newyearcountdown.on" : "screen.newyearcountdown.off"));
    }

    private static Component scaleLabel(ClientConfig cfg) {
        return Component.translatable("screen.newyearcountdown.scale", Math.round(cfg.scale() * 100) + "%");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);

        if (DebugAccess.unlocked()) {
            int x = width / 2 - 100;
            int hy = 36 + 24 * 2 + 28 + 2;
            Component header = Component.translatable("debug.newyearcountdown.header");
            ctx.text(font, header, x, hy, 0xFFFF6B6B);
            ctx.text(font, Component.translatable("debug.newyearcountdown.field_hint", ClientCountdown.zone().getId()),
                    x + font.width(header) + 6, hy, 0xFF777B88);

            long rem = ClientCountdown.targetMs() - ClientCountdown.now();
            Component status = Component.translatable("debug.newyearcountdown.status", CountdownHud.formatTarget(), formatRemaining(rem));
            ctx.centeredText(font, status, width / 2, height - 52, 0xFFA0A4B0);
            if (!message.getString().isEmpty()) {
                ctx.centeredText(font, message, width / 2, height - 42, 0xFFFFFFFF);
            }
        }
    }

    private static String formatRemaining(long ms) {
        if (ms <= 0) return "0";
        Duration d = Duration.ofMillis(ms);
        return String.format("%dd %02d:%02d:%02d", d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        // 제목 영역을 연속 클릭하면 디버그 해제
        int tw = font.width(title);
        boolean onTitle = mouseY >= 10 && mouseY <= 27 && Math.abs(mouseX - width / 2.0) <= tw / 2.0 + 6;
        if (onTitle && button == 0 && !DebugAccess.unlocked()) {
            long now = System.currentTimeMillis();
            titleClicks = (now - lastTitleClick > CLICK_WINDOW_MS) ? 1 : titleClicks + 1;
            lastTitleClick = now;
            if (titleClicks >= UNLOCK_CLICKS) {
                titleClicks = 0;
                Minecraft.getInstance().gui.setScreen(new PasswordScreen(this));
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        ClientConfig.save();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
