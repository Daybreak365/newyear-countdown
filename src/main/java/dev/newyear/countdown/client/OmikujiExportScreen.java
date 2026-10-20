package dev.newyear.countdown.client;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.NativeImage;
import dev.newyear.countdown.omikuji.Fortunes;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.util.UUID;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * 한 유저의 오미쿠지 전부를 한 장의 PNG 로 만든다.
 * 실제 게임 화면에 종이를 한 장씩 그리고(보관함과 똑같은 모습), 그 프레임을 캡처해 격자로 이어 붙인다.
 * 첫 칸은 이름·장수·등급별 개수를 적은 표지. 파일은 게임 폴더의 omikuji_exports/ 에 저장된다.
 */
public class OmikujiExportScreen extends Screen {
    private static final int BG = 0xFF2B2622;
    private static final int PAD = 6;          // 종이 그림자까지 담는 여백 (GUI 단위)
    private static final int GAP = 16;         // 칸 사이 (픽셀)

    private final OmikujiAdminScreen parent;
    private final UUID id;
    private final String name;
    private PlayerSkin skin;
    private boolean skinReady;
    private int waitTicks;
    private Model.Simple wideModel, slimModel;
    private final List<OmikujiBook.Entry> entries;
    private int index = -1;                    // -1 = 표지, 0.. = 종이
    private int frames;
    private boolean capturing;
    private NativeImage atlas;
    private int tileW, tileH, cols;
    private boolean done;

    public OmikujiExportScreen(OmikujiAdminScreen parent, UUID id, String name, List<OmikujiBook.Entry> entries) {
        super(Component.translatable("omikuji.newyearcountdown.admin.exporting", name));
        this.parent = parent;
        this.id = id;
        this.name = name;
        this.entries = entries;
        this.skin = DefaultPlayerSkin.get(id);
        loadSkin();
    }

    /** 접속 중이면 탭 목록의 스킨, 아니면 스킨 서버에서 받아 온다 (최대 4초 기다림). */
    private void loadSkin() {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        if (info != null) {
            skin = info.getSkin();
            skinReady = true;
            return;
        }
        mc.playerSkinRenderCache().lookup(ResolvableProfile.createUnresolved(id)).whenComplete((r, err) -> mc.execute(() -> {
            if (r != null && r.isPresent()) skin = r.get().playerSkin();
            skinReady = true;
        }));
    }

    @Override
    protected void init() {
        var models = Minecraft.getInstance().getEntityModels();
        wideModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
        slimModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
    }

    public static Path folder() {
        return new File(Minecraft.getInstance().gameDirectory, "omikuji_exports").toPath();
    }

    public static void openFolder() {
        File f = folder().toFile();
        f.mkdirs();
        Blaze3D.openPath(f.toPath());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, BG);
    }

    /** 칸 하나를 화면 가운데 놓을 때의 배율 (GUI 단위). */
    private float scale() {
        return Math.min((height - 24f) / (OmikujiPaper.H + PAD * 2), (width - 24f) / (OmikujiPaper.W + PAD * 2));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        extractBackground(ctx, mouseX, mouseY, delta);
        if (done) return;
        float s = scale();
        ctx.pose().pushMatrix();
        ctx.pose().translate(width / 2f, height / 2f);
        ctx.pose().scale(s, s);
        if (index < 0) drawCover(ctx);
        else OmikujiPaper.draw(ctx, font, entries.get(index), 1f, index);
        ctx.pose().popMatrix();
        frames++;
    }

    /** 표지: 종이와 같은 크기의 카드에 이름, 플레이어 모습(스킨), 장수, 등급별 개수. */
    private void drawCover(GuiGraphicsExtractor ctx) {
        int w = OmikujiPaper.W, h = OmikujiPaper.H, x0 = -w / 2, y0 = -h / 2;
        ctx.fillGradient(x0, y0, x0 + w, y0 + h, 0xFFF9F1DE, 0xFFEADBBA);
        ctx.outline(x0, y0, w, h, 0xFF9B2C2C);
        ctx.outline(x0 + 1, y0 + 1, w - 2, h - 2, 0xFF9B2C2C);
        ctx.outline(x0 + 4, y0 + 4, w - 8, h - 8, 0xFFC9A24A);
        ctx.fill(x0 + 8, y0 + 8, x0 + w - 8, y0 + 22, 0xFF9B2C2C);
        ctx.centeredText(font, Component.translatable("omikuji.newyearcountdown.ui.title"), 0, y0 + 11, 0xFFF3D98A);
        ctx.pose().pushMatrix();
        float ns = Math.min(1.4f, (w - 20f) / Math.max(1, font.width(name)));
        ctx.pose().translate(0, y0 + 27);
        ctx.pose().scale(ns, ns);
        ctx.centeredText(font, name, 0, 0, 0xFF3A2A1A);
        ctx.pose().popMatrix();
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        ctx.centeredText(font, date, 0, y0 + 41, 0xFF8A7656);

        // 플레이어 모습: 스킨을 입힌 3D 모델 (살짝 비스듬히). 그림 속 그림이라 화면 좌표로 넣는다
        int my0 = y0 + 52, my1 = y0 + 150;
        ctx.fill(x0 + 18, my0, x0 + w - 18, my1, 0x22FFFFFF);
        ctx.outline(x0 + 18, my0, w - 36, my1 - my0, 0x55C9A24A);
        float s = scale();
        int sx0 = Math.round(width / 2f + (x0 + 18) * s), sx1 = Math.round(width / 2f + (x0 + w - 18) * s);
        int sy0 = Math.round(height / 2f + (my0 + 3) * s), sy1 = Math.round(height / 2f + (my1 - 3) * s);
        Model.Simple model = skin.model() == PlayerModelType.SLIM ? slimModel : wideModel;
        if (model != null) {
            ctx.skin(model, skin.body().texturePath(), 0.97f * (sy1 - sy0) / 2.125f, -6f, 28f, -1.0625f, sx0, sy0, sx1, sy1);
        }

        ctx.centeredText(font, Component.translatable("omikuji.newyearcountdown.admin.count", entries.size()), 0, y0 + 156, 0xFF6B5638);
        int col = 0, row = 0;
        for (int r = 0; r < Fortunes.ALL.length; r++) {
            int n = 0;
            for (OmikujiBook.Entry e : entries) if (e.result == r) n++;
            if (n == 0) continue;
            int cx = x0 + 14 + col * 62, cy = y0 + 170 + row * 11;
            Component label = Component.translatable("omikuji.newyearcountdown.fortune." + Fortunes.get(r).key());
            ctx.text(font, label, cx, cy, OmikujiPaper.fortuneColor(r));
            String c = "x" + n;
            ctx.text(font, c, cx + 56 - font.width(c), cy, 0xFF3A2A1A);
            if (++col == 2) { col = 0; row++; }
        }
    }

    @Override
    public void tick() {
        // 그 칸이 최소 두 프레임 그려진 뒤(화면에 완전히 올라간 뒤) 캡처한다
        if (!skinReady && ++waitTicks < 80) return;   // 스킨을 받을 때까지 (최대 4초)
        if (done || capturing || frames < 3) return;
        capturing = true;
        Minecraft mc = Minecraft.getInstance();
        int gs = mc.getWindow().getGuiScale();
        float s = scale();
        int pw = (int) Math.floor((OmikujiPaper.W + PAD * 2) * s * gs);
        int ph = (int) Math.floor((OmikujiPaper.H + PAD * 2) * s * gs);
        int left = (int) Math.floor((width / 2f - (OmikujiPaper.W / 2f + PAD) * s) * gs);
        int top = (int) Math.floor((height / 2f - (OmikujiPaper.H / 2f + PAD) * s) * gs);
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), shot -> mc.execute(() -> {
            try {
                place(shot, left, top, pw, ph);
            } finally {
                shot.close();
            }
            capturing = false;
            frames = 0;
            index++;
            if (index >= entries.size()) finish();
        }));
    }

    private void place(NativeImage shot, int left, int top, int pw, int ph) {
        if (atlas == null) {
            tileW = Math.min(pw, shot.getWidth() - left);
            tileH = Math.min(ph, shot.getHeight() - top);
            int tiles = entries.size() + 1;
            cols = Math.min(5, tiles);
            int rows = (tiles + cols - 1) / cols;
            atlas = new NativeImage(cols * tileW + (cols + 1) * GAP, rows * tileH + (rows + 1) * GAP, false);
            atlas.fillRect(0, 0, atlas.getWidth(), atlas.getHeight(), BG);
        }
        int t = index + 1;
        int tx = GAP + (t % cols) * (tileW + GAP), ty = GAP + (t / cols) * (tileH + GAP);
        int w = Math.min(tileW, shot.getWidth() - left), h = Math.min(tileH, shot.getHeight() - top);
        shot.copyRect(atlas, Math.max(0, left), Math.max(0, top), tx, ty, w, h, false, false);
    }

    private void finish() {
        done = true;
        NativeImage img = atlas;
        atlas = null;
        String safe = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File dir = folder().toFile();
        File out = new File(dir, safe + "_" + stamp + ".png");
        Minecraft mc = Minecraft.getInstance();
        Util.ioPool().execute(() -> {
            Component msg;
            try {
                dir.mkdirs();
                img.writeToFile(out);
                msg = Component.translatable("omikuji.newyearcountdown.admin.saved", out.getName()).withStyle(ChatFormatting.GREEN);
            } catch (Exception e) {
                msg = Component.translatable("omikuji.newyearcountdown.admin.failed", e.getMessage()).withStyle(ChatFormatting.RED);
            } finally {
                img.close();
            }
            Component m = msg;
            mc.execute(() -> {
                parent.setMessage(m);
                mc.gui.setScreen(parent);
            });
        });
    }
}
