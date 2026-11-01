package dev.newyear.countdown.client.ai;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 블록 변경 시뮬레이션 미리보기 화면. 스크립트가 놓을(또는 놓은) 블록을
 * ① 층별 평면도 (한 Y 층씩, 블록 지도 색) ② 입체도 (블록 아이템 아이콘을 등각으로 쌓기, 90° 회전) 로 보여 준다.
 * 붉은 테두리 = 기존 블록을 바꾸는 칸, 붉은 X = 블록을 없애는 칸. "월드에 표시"는 같은 데이터를 유령 블록으로 띄운다.
 */
public class BlockPreviewScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private BlockPreview.Preview preview;
    private boolean iso = true;
    private int layer;
    private int rotation;
    private int x0, y0, x1, y1;
    private final Map<String, ItemStack> icons = new HashMap<>();

    public BlockPreviewScreen(Screen parent, BlockPreview.Preview preview) {
        super(Component.literal("블록 미리보기"));
        this.parent = parent;
        setPreview(preview);
    }

    public void setPreview(BlockPreview.Preview p) {
        this.preview = p;
        this.layer = p.cells.isEmpty() ? 0 : p.minY;
    }

    @Override
    protected void init() {
        x0 = 8;
        y0 = 8;
        x1 = width - 8;
        y1 = height - 8;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_UP, InputConstants.KEY_PAGEUP -> { layer = Math.min(preview.maxY, layer + 1); return true; }
            case InputConstants.KEY_DOWN, InputConstants.KEY_PAGEDOWN -> { layer = Math.max(preview.minY, layer - 1); return true; }
            case InputConstants.KEY_R -> { rotation = (rotation + 1) & 3; return true; }
            case InputConstants.KEY_TAB -> { iso = !iso; return true; }
            default -> { }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!preview.cells.isEmpty()) layer = Math.max(preview.minY, Math.min(preview.maxY, layer + (sy > 0 ? 1 : -1)));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Hit h : new ArrayList<>(hits)) {
                if (WishUi.inside(event.x(), event.y(), h.x, h.y, h.w, h.h)) {
                    h.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        hits.clear();
        WishUi.panel(g, x0, y0, x1, y1, 1f);
        BlockPreview.Preview p = preview;
        g.text(font, "✦ 블록 변경 미리보기 — " + (p.title == null || p.title.isEmpty() ? p.id : p.title), x0 + 10, y0 + 8, WishUi.GOLD, false);
        int sideW = Math.min(170, (x1 - x0) / 3);
        int vx0 = x0 + 10, vy0 = y0 + 22, vx1 = x1 - sideW - 14, vy1 = y1 - 24;
        g.fill(vx0, vy0, vx1, vy1, 0xC00A0C18);
        g.outline(vx0, vy0, vx1 - vx0, vy1 - vy0, WishUi.EDGE_GOLD_DIM);
        if (p.cells.isEmpty()) {
            g.centeredText(font, "블록 변경이 없습니다.", (vx0 + vx1) / 2, (vy0 + vy1) / 2, WishUi.TEXT_DIM);
        } else if (iso) {
            drawIso(g, p, vx0, vy0, vx1, vy1);
        } else {
            drawLayer(g, p, vx0, vy0, vx1, vy1, mx, my);
        }
        drawSide(g, p, vx1 + 8, vy0, x1 - 10, vy1);
        // 버튼
        int by = y1 - 20, bx = x0 + 10;
        WishUi.button(g, font, iso ? "평면도 (Tab)" : "입체도 (Tab)", bx, by, 80, 14, mx, my, true, WishUi.MYSTIC, 1f);
        hits.add(new Hit(bx, by, 80, 14, () -> iso = !iso));
        bx += 84;
        if (iso) {
            WishUi.button(g, font, "회전 (R)", bx, by, 56, 14, mx, my, true, WishUi.INFO, 1f);
            hits.add(new Hit(bx, by, 56, 14, () -> rotation = (rotation + 1) & 3));
            bx += 60;
        } else {
            WishUi.button(g, font, "▲ 위층", bx, by, 50, 14, mx, my, true, WishUi.INFO, 1f);
            hits.add(new Hit(bx, by, 50, 14, () -> layer = Math.min(p.maxY, layer + 1)));
            bx += 54;
            WishUi.button(g, font, "▼ 아래층", bx, by, 56, 14, mx, my, true, WishUi.INFO, 1f);
            hits.add(new Hit(bx, by, 56, 14, () -> layer = Math.max(p.minY, layer - 1)));
            bx += 60;
        }
        WishUi.button(g, font, "월드에 표시 (60초)", bx, by, 104, 14, mx, my, !p.cells.isEmpty(), WishUi.GOLD, 1f);
        if (!p.cells.isEmpty()) hits.add(new Hit(bx, by, 104, 14, () -> {
            p.expiresAt = System.currentTimeMillis() + 60_000;
            BlockPreview.ACTIVE.put(p.id, p);
            AdminClient.message = "월드에 유령 블록으로 60초 동안 보여 줍니다 (나에게만 보임).";
            AdminClient.messageAt = System.currentTimeMillis();
        }));
        WishUi.button(g, font, "닫기", x1 - 10 - 56, by, 56, 14, mx, my, true, WishUi.GOLD, 1f);
        hits.add(new Hit(x1 - 10 - 56, by, 56, 14, this::onClose));
        if (!AdminClient.message.isEmpty() && System.currentTimeMillis() - AdminClient.messageAt < 6000) {
            g.text(font, font.plainSubstrByWidth("» " + AdminClient.message, x1 - x0 - 420), bx + 110, by + 3, WishUi.GOLD, false);
        }
        super.extractRenderState(g, mx, my, delta);
    }

    // ---------------------------------------------------------------- 평면도

    private void drawLayer(GuiGraphicsExtractor g, BlockPreview.Preview p, int vx0, int vy0, int vx1, int vy1, int mx, int my) {
        int w = p.maxX - p.minX + 1, d = p.maxZ - p.minZ + 1;
        int cell = Math.max(2, Math.min(18, Math.min((vx1 - vx0 - 16) / w, (vy1 - vy0 - 26) / d)));
        int gx = (vx0 + vx1) / 2 - cell * w / 2, gy = vy0 + 18 + ((vy1 - vy0 - 22) - cell * d) / 2;
        int count = 0;
        // 아래층 그림자 (한 층 아래를 흐리게)
        for (BlockPreview.Cell c : p.cells) {
            if (c.y != layer - 1) continue;
            int cx = gx + (c.x - p.minX) * cell, cz = gy + (c.z - p.minZ) * cell;
            g.fill(cx, cz, cx + cell, cz + cell, 0x30000000 | c.color);
        }
        BlockPreview.Cell hover = null;
        for (BlockPreview.Cell c : p.cells) {
            if (c.y != layer) continue;
            count++;
            int cx = gx + (c.x - p.minX) * cell, cz = gy + (c.z - p.minZ) * cell;
            if (c.removal) {
                g.fill(cx, cz, cx + cell, cz + cell, 0x60E05050);
                for (int k = 0; k < cell; k++) {
                    g.fill(cx + k, cz + k, cx + k + 1, cz + k + 1, 0xFFFF6060);
                    g.fill(cx + cell - 1 - k, cz + k, cx + cell - k, cz + k + 1, 0xFFFF6060);
                }
            } else {
                g.fill(cx, cz, cx + cell, cz + cell, 0xFF000000 | c.color);
                if (cell >= 6) g.fill(cx, cz, cx + cell, cz + 1, 0x40FFFFFF);
            }
            if (c.replaces) g.outline(cx, cz, cell, cell, 0xFFFF4040);
            if (WishUi.inside(mx, my, cx, cz, cell, cell)) hover = c;
        }
        // 격자 테두리
        g.outline(gx - 1, gy - 1, cell * w + 2, cell * d + 2, WishUi.EDGE_GOLD_DIM);
        g.text(font, "Y = " + layer + "  (" + count + "칸)   " + (p.minY == p.maxY ? "" : "층 " + (layer - p.minY + 1) + "/" + (p.maxY - p.minY + 1) + " · 휠/↑↓"),
                vx0 + 6, vy0 + 5, WishUi.TEXT, false);
        g.text(font, "↑ 북 (−Z)", vx1 - 6 - font.width("↑ 북 (−Z)"), vy0 + 5, WishUi.TEXT_FAINT, false);
        if (hover != null) {
            String tip = hover.x + ", " + hover.y + ", " + hover.z + "  " + hover.id().replace("minecraft:", "") + (hover.replaces ? " (기존 블록 교체)" : "");
            int tw = font.width(tip) + 8;
            int tx = Math.min(mx + 10, vx1 - tw), ty = my - 14;
            g.fill(tx, ty, tx + tw, ty + 12, 0xF0101426);
            g.outline(tx, ty, tw, 12, WishUi.EDGE_GOLD);
            g.text(font, tip, tx + 4, ty + 2, WishUi.TEXT, false);
        }
    }

    // ---------------------------------------------------------------- 입체도

    private int[] rotate(BlockPreview.Preview p, BlockPreview.Cell c) {
        int x = c.x - p.minX, z = c.z - p.minZ;
        int w = p.maxX - p.minX, d = p.maxZ - p.minZ;
        return switch (rotation) {
            case 1 -> new int[]{d - z, x};
            case 2 -> new int[]{w - x, d - z};
            case 3 -> new int[]{z, w - x};
            default -> new int[]{x, z};
        };
    }

    private void drawIso(GuiGraphicsExtractor g, BlockPreview.Preview p, int vx0, int vy0, int vx1, int vy1) {
        int w = p.maxX - p.minX + 1, d = p.maxZ - p.minZ + 1, h = p.maxY - p.minY + 1;
        // 화면에 맞는 블록 크기 (16px 아이콘을 scale 로 줄이거나 키운다)
        double spanX = (w + d) * 0.5, spanY = (w + d) * 0.25 + h * 0.5 + 0.6;
        float s = (float) Math.max(3, Math.min(24, Math.min((vx1 - vx0 - 20) / spanX, (vy1 - vy0 - 30) / spanY)));
        float cx = (vx0 + vx1) / 2f, cy = vy0 + 16 + h * s * 0.5f;
        float offX = (w - d) * 0.25f * s * (rotation % 2 == 0 ? 1 : -1);
        List<BlockPreview.Cell> sorted = new ArrayList<>(p.cells);
        sorted.sort(Comparator.comparingInt((BlockPreview.Cell c) -> { int[] r = rotate(p, c); return r[0] + r[1]; }).thenComparingInt(c -> c.y));
        g.enableScissor(vx0 + 1, vy0 + 1, vx1 - 1, vy1 - 1);
        int drawn = 0;
        for (BlockPreview.Cell c : sorted) {
            if (drawn++ > 3000) break;
            int[] r = rotate(p, c);
            float sx = cx - offX + (r[0] - r[1]) * s * 0.5f - s / 2f;
            float sy = cy + (r[0] + r[1]) * s * 0.25f - (c.y - p.minY) * s * 0.5f;
            if (c.removal) {
                g.fill((int) sx + (int) (s * 0.25f), (int) (sy + s * 0.25f), (int) (sx + s * 0.75f), (int) (sy + s * 0.75f), 0x90E05050);
                continue;
            }
            ItemStack icon = icon(c);
            if (icon.isEmpty()) {
                g.fill((int) sx, (int) sy, (int) (sx + s), (int) (sy + s), 0xFF000000 | c.color);
            } else {
                g.pose().pushMatrix();
                g.pose().translate(sx, sy);
                g.pose().scale(s / 16f, s / 16f);
                g.fakeItem(icon, 0, 0);
                g.pose().popMatrix();
            }
            if (c.replaces) g.outline((int) sx, (int) sy, Math.max(2, (int) s), Math.max(2, (int) s), 0xC0FF4040);
        }
        g.disableScissor();
        g.text(font, "입체도 · " + "동서남북".charAt(rotation) + "쪽에서 · R: 회전", vx0 + 6, vy0 + 5, WishUi.TEXT_DIM, false);
    }

    private ItemStack icon(BlockPreview.Cell c) {
        return icons.computeIfAbsent(c.id(), id -> {
            var item = BlockPreview.block(c.state).asItem();
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    // ---------------------------------------------------------------- 요약

    private void drawSide(GuiGraphicsExtractor g, BlockPreview.Preview p, int sx0, int sy0, int sx1, int sy1) {
        int y = sy0;
        g.text(font, "요약", sx0, y, WishUi.GOLD, false);
        y += 12;
        int replaces = 0, removals = 0;
        Map<String, Integer> by = new LinkedHashMap<>();
        for (BlockPreview.Cell c : p.cells) {
            if (c.replaces) replaces++;
            if (c.removal) removals++;
            by.merge(c.id().replace("minecraft:", ""), 1, Integer::sum);
        }
        String[] lines = {
                "블록 " + p.total + "칸" + (p.truncated ? " (앞 " + p.cells.size() + "칸만 표시)" : ""),
                p.cells.isEmpty() ? "" : "크기 " + (p.maxX - p.minX + 1) + " × " + (p.maxY - p.minY + 1) + " × " + (p.maxZ - p.minZ + 1),
                p.cells.isEmpty() ? "" : "범위 " + p.minX + "," + p.minY + "," + p.minZ + " ~ " + p.maxX + "," + p.maxY + "," + p.maxZ,
                p.dimension == null ? "" : p.dimension.replace("minecraft:", ""),
        };
        for (String l : lines) {
            if (l.isEmpty()) continue;
            for (var seq : WishUi.wrap(font, l, sx1 - sx0)) {
                g.text(font, seq, sx0, y, WishUi.TEXT, false);
                y += 9;
            }
        }
        if (replaces > 0) {
            g.text(font, "기존 블록 교체 " + replaces + "칸", sx0, y + 2, WishUi.DANGER, false);
            y += 11;
        }
        if (removals > 0) {
            g.text(font, "블록 제거 " + removals + "칸", sx0, y + 2, WishUi.WARNING, false);
            y += 11;
        }
        y += 6;
        g.text(font, "블록 종류", sx0, y, WishUi.TEXT_DIM, false);
        y += 11;
        List<Map.Entry<String, Integer>> list = new ArrayList<>(by.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : list) {
            if (y > sy1 - 10) break;
            BlockPreview.Cell sample = null;
            for (BlockPreview.Cell c : p.cells) if (c.id().replace("minecraft:", "").equals(e.getKey())) { sample = c; break; }
            if (sample != null) g.fill(sx0, y + 1, sx0 + 7, y + 8, 0xFF000000 | sample.color);
            g.text(font, font.plainSubstrByWidth(e.getKey(), sx1 - sx0 - 40), sx0 + 10, y, WishUi.TEXT, false);
            String n = String.valueOf(e.getValue());
            g.text(font, n, sx1 - font.width(n), y, WishUi.TEXT_DIM, false);
            y += 10;
        }
    }
}
