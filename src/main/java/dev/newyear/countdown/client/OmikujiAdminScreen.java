package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.omikuji.Fortunes;
import dev.newyear.countdown.omikuji.OmikujiAdmin;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * 오미쿠지 관리자 콘솔. 비밀번호를 넣으면 서버의 모든 유저 목록이 뜨고, 유저마다 뽑은 오미쿠지 전부를 한 장의 PNG 로 저장할 수 있다.
 * 비밀번호는 서버가 다시 확인한다(틀리면 목록을 보내지 않음).
 */
public class OmikujiAdminScreen extends Screen {
    private static final int ROW_H = 22, PANEL_W = 340;
    private static OmikujiAdminScreen current;

    private EditBox field;
    private Component message = Component.empty();
    private List<OmikujiAdmin.UserRecord> users;   // null = 아직 로그인 전
    private int page;
    private boolean waiting;

    public OmikujiAdminScreen() {
        super(Component.translatable("omikuji.newyearcountdown.admin.title"));
    }

    /** 서버 응답. */
    public static void onList(OmikujiAdmin.ListS2C list) {
        if (current == null) return;
        current.waiting = false;
        if (!list.ok()) {
            current.message = Component.translatable("screen.newyearcountdown.password.wrong").withStyle(s -> s.withColor(0xFF6B6B));
            current.users = null;
        } else {
            current.users = new ArrayList<>(list.users());
            current.message = Component.empty();
            current.page = 0;
        }
        current.rebuildWidgets();
    }

    @Override
    protected void init() {
        current = this;
        int cx = width / 2;
        if (users == null) {
            field = new EditBox(font, cx - 100, height / 2 - 10, 200, 20, Component.empty());
            field.setMaxLength(32);
            field.addFormatter((text, firstIndex) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
            addRenderableWidget(field);
            setInitialFocus(field);
            addRenderableWidget(Button.builder(Component.translatable("screen.newyearcountdown.password.ok"), b -> submit())
                    .bounds(cx - 100, height / 2 + 18, 97, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.newyearcountdown.password.cancel"), b -> onClose())
                    .bounds(cx + 3, height / 2 + 18, 97, 20).build());
            if (DebugAccess.password() != null && !waiting && message.getString().isEmpty()) {
                request(DebugAccess.password());   // 이미 잠금 해제했으면 바로 요청
            }
            return;
        }
        int top = listTop();
        int from = page * rows();
        for (int i = from; i < Math.min(users.size(), from + rows()); i++) {
            OmikujiAdmin.UserRecord u = users.get(i);
            int y = top + (i - from) * ROW_H;
            addRenderableWidget(Button.builder(Component.translatable("omikuji.newyearcountdown.admin.export"), b -> export(u))
                    .bounds(cx + PANEL_W / 2 - 86, y, 80, 20).build());
        }
        int pages = Math.max(1, (users.size() + rows() - 1) / rows());
        int by = height - 26;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
                .bounds(cx - PANEL_W / 2 + 6, by, 20, 20).build());
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
                .bounds(cx - PANEL_W / 2 + 30, by, 20, 20).build());
        prev.active = page > 0;
        next.active = page < pages - 1;
        addRenderableWidget(Button.builder(Component.translatable("omikuji.newyearcountdown.admin.open_folder"),
                b -> OmikujiExportScreen.openFolder()).bounds(cx + PANEL_W / 2 - 166, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("omikuji.newyearcountdown.ui.close"), b -> onClose())
                .bounds(cx + PANEL_W / 2 - 62, by, 56, 20).build());
    }

    /** 목록 시작 y (제목·메시지 아래). */
    private int listTop() {
        return 34;
    }

    /** 화면 높이에 맞는 한 쪽의 줄 수. */
    private int rows() {
        return Math.max(3, Math.min(10, (height - listTop() - 32) / ROW_H));
    }

    private void submit() {
        String pw = field.getValue();
        DebugAccess.tryUnlock(pw);       // 맞으면 이 게임 동안 기억 (설정 화면 숨김 섹션도 열림)
        request(pw);
    }

    private void request(String pw) {
        waiting = true;
        message = Component.translatable("omikuji.newyearcountdown.admin.loading").withStyle(s -> s.withColor(0xAAAAAA));
        ClientPlayNetworking.send(new OmikujiAdmin.RequestC2S(pw));
    }

    private void export(OmikujiAdmin.UserRecord u) {
        List<OmikujiBook.Entry> entries = new ArrayList<>();
        for (int i = 0; i < u.results().size(); i++) {
            entries.add(new OmikujiBook.Entry(u.results().get(i), u.numbers().get(i), u.times().get(i)));
        }
        Minecraft.getInstance().gui.setScreen(new OmikujiExportScreen(this, u.id(), u.name(), entries));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (users == null && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        if (users == null) {
            ctx.centeredText(font, title, cx, height / 2 - 34, 0xFFFFE08A);
            if (!message.getString().isEmpty()) ctx.centeredText(font, message, cx, height / 2 + 46, 0xFFFFFFFF);
            return;
        }
        int top = listTop();
        int x0 = cx - PANEL_W / 2, x1 = cx + PANEL_W / 2;
        ctx.centeredText(font, Component.translatable("omikuji.newyearcountdown.admin.list", users.size()), cx, 8, 0xFFFFE08A);
        if (users.isEmpty()) {
            ctx.centeredText(font, Component.translatable("omikuji.newyearcountdown.admin.empty"), cx, top + 20, 0xFFAAAAAA);
        }
        int from = page * rows();
        for (int i = from; i < Math.min(users.size(), from + rows()); i++) {
            OmikujiAdmin.UserRecord u = users.get(i);
            int y = top + (i - from) * ROW_H;
            ctx.fill(x0, y - 1, x1, y + 21, (i % 2 == 0) ? 0x50000000 : 0x30000000);

            Fortunes.Fortune first = Fortunes.get(u.results().get(0));
            Component info = Component.translatable("omikuji.newyearcountdown.admin.row", u.results().size(),
                    Component.translatable("omikuji.newyearcountdown.fortune." + first.key()));
            int maxW = PANEL_W - 110 - 92;
            FormattedCharSequence line = font.split(info, maxW).isEmpty() ? FormattedCharSequence.EMPTY : font.split(info, maxW).get(0);
            ctx.text(font, line, x0 + 104, y + 6, OmikujiPaper.fortuneColor(u.results().get(0)));
            PlayerFaceExtractor.extractRenderState(ctx, ResolvableProfile.createUnresolved(u.id()), x0 + 4, y + 2, 16);
            ctx.text(font, font.plainSubstrByWidth(u.name(), 76), x0 + 24, y + 6, 0xFFFFFFFF);
        }
        int pages = Math.max(1, (users.size() + rows() - 1) / rows());
        ctx.text(font, (page + 1) + " / " + pages, x0 + 56, height - 20, 0xFFAAAAAA);
        if (!message.getString().isEmpty()) ctx.centeredText(font, message, cx, 20, 0xFFFFFFFF);
    }

    /** 저장이 끝나면 내보내기 화면이 결과를 알려 준다. */
    void setMessage(Component m) {
        message = m;
    }

    @Override
    public void removed() {
        if (current == this && !(Minecraft.getInstance().gui.screen() instanceof OmikujiExportScreen)) current = null;
    }
}
