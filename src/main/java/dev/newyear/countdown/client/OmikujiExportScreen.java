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
    private final String name;
    private final List<OmikujiBook.Entry> entries;
    private int index = -1;                    // -1 = 표지, 0.. = 종이
    private int frames;
    private boolean capturing;
    private NativeImage atlas;
    private int tileW, tileH, cols;
    private boolean done;

    public OmikujiExportScreen(OmikujiAdminScreen parent, String name, List<OmikujiBook.Entry> entries) {
        super(Component.translatable("omikuji.newyearcountdown.admin.exporting", name));
        this.parent = parent;
        this.name = name;
        this.entries = entries;
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

    /** 표지: 종이와 같은 크기의 카드에 이름, 장수, 등급별 개수. */
    private void drawCover(GuiGraphicsExtractor ctx) {
        int w = OmikujiPaper.W, h = OmikujiPaper.H, x0 = -w / 2, y0 = -h / 2;
        ctx.fillGradient(x0, y0, x0 + w, y0 + h, 0xFFF9F1DE, 0xFFEADBBA);
        ctx.outline(x0, y0, w, h, 0xFF9B2C2C);
        ctx.outline(x0 + 1, y0 + 1, w - 2, h - 2, 0xFF9B2C2C);
        ctx.outline(x0 + 4, y0 + 4, w - 8, h - 8, 0xFFC9A24A);
        ctx.fill(x0 + 8, y0 + 10, x0 + w - 8, y0 + 26, 0xFF9B2C2C);
        ctx.centeredText(font, Component.translatable("omikuji.newyearcountdown.ui.title"), 0, y0 + 14, 0xFFF3D98A);
        ctx.pose().pushMatrix();
        float ns = Math.min(1.6f, (w - 20f) / Math.max(1, font.width(name)));
        ctx.pose().translate(0, y0 + 40);
        ctx.pose().scale(ns, ns);
        ctx.centeredText(font, name, 0, 0, 0xFF3A2A1A);
        ctx.pose().popMatrix();
        ctx.centeredText(font, Component.translatable("omikuji.newyearcountdown.admin.count", entries.size()), 0, y0 + 62, 0xFF6B5638);
        int y = y0 + 82;
        for (int r = 0; r < Fortunes.ALL.length; r++) {
            int n = 0;
            for (OmikujiBook.Entry e : entries) if (e.result == r) n++;
            if (n == 0) continue;
            Component label = Component.translatable("omikuji.newyearcountdown.fortune." + Fortunes.get(r).key());
            ctx.text(font, label, x0 + 22, y, OmikujiPaper.fortuneColor(r));
            String c = n + "";
            ctx.text(font, c, x0 + w - 22 - font.width(c), y, 0xFF3A2A1A);
            y += 12;
        }
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"));
        ctx.centeredText(font, date, 0, y0 + h - 18, 0xFF8A7656);
    }

    @Override
    public void tick() {
        // 그 칸이 최소 두 프레임 그려진 뒤(화면에 완전히 올라간 뒤) 캡처한다
        if (done || capturing || frames < 2) return;
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
