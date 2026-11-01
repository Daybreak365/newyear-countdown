package dev.newyear.countdown.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Paged editor backed exclusively by the OP-only server configuration schema. */
public final class AdminSettingsScreen extends Screen {
    private final Screen parent;
    private final Map<String, String> draft = new LinkedHashMap<>();
    private final Map<String, JsonElement> original = new LinkedHashMap<>();
    private final List<JsonObject> fields = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final List<JsonObject> visible = new ArrayList<>();
    private JsonObject seenConfig, seenResult;
    private int x0, y0, w, h, group, page, pageSize, totalPages;
    private boolean saving;
    private String status = "서버 설정을 불러오는 중…";
    private long discardUntil;
    private Button saveButton;

    public AdminSettingsScreen(Screen parent) {
        super(Component.literal("새벽별 설정"));
        this.parent = parent;
        AdminClient.config = null;
        AdminClient.configResult = null;
        AdminClient.request("config");
    }

    @Override
    protected void init() {
        w = Math.min(540, width - 12);
        h = Math.min(370, height - 12);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        pageSize = Math.max(1, (h - 127) / 41);
        rebuild();
    }

    @Override public boolean isPauseScreen() { return false; }

    private String key(JsonObject field) { return field.get("key").getAsString(); }
    private boolean changed(String key) {
        return !draft.get(key).trim().equals(original.get(key).getAsString());
    }
    private long changedCount() { return draft.keySet().stream().filter(this::changed).count(); }

    private void accept(JsonObject config) {
        fields.clear(); groups.clear(); draft.clear(); original.clear();
        for (JsonElement e : config.getAsJsonArray("fields")) {
            JsonObject f = e.getAsJsonObject();
            fields.add(f);
            String g = f.get("group").getAsString();
            if (!groups.contains(g)) groups.add(g);
            String k = key(f);
            original.put(k, f.get("value").deepCopy());
            draft.put(k, f.get("value").getAsString());
        }
        group = Math.min(group, groups.size() - 1);
        rebuild();
    }

    @Override public void tick() {
        if (AdminClient.configResult != null && AdminClient.configResult != seenResult) {
            seenResult = AdminClient.configResult;
            saving = false;
            status = seenResult.get("text").getAsString();
            if (seenResult.get("ok").getAsBoolean()) {
                seenConfig = AdminClient.config;
                accept(seenConfig);
            } else rebuild();
        } else if (AdminClient.config != null && AdminClient.config != seenConfig && !saving) {
            seenConfig = AdminClient.config;
            accept(seenConfig);
            status = "변경 후 ‘저장·적용’을 누르세요.";
        }
        if (saveButton != null) saveButton.active = !saving && changedCount() > 0;
    }

    private Button button(String label, int x, int y, int width, Runnable action) {
        Button b = Button.builder(Component.literal(label), ignored -> action.run()).bounds(x, y, width, 20).build();
        addRenderableWidget(b);
        return b;
    }

    private void rebuild() {
        clearWidgets(); visible.clear();
        button("세션 키", x0 + w - 76, y0 + 4, 62,
                () -> minecraft.gui.setScreen(new AdminSessionKeyScreen(this))).active = !saving;
        int by = y0 + h - 27;
        if (!groups.isEmpty()) {
            int gw = (w - 20) / groups.size();
            for (int i = 0; i < groups.size(); i++) {
                int target = i;
                Button b = button(groups.get(i), x0 + 10 + i * gw, y0 + 27, gw - 3, () -> { group = target; page = 0; rebuild(); });
                b.active = i != group;
            }
            List<JsonObject> selected = fields.stream().filter(f -> f.get("group").getAsString().equals(groups.get(group))).toList();
            totalPages = Math.max(1, (selected.size() + pageSize - 1) / pageSize);
            page = Math.min(page, totalPages - 1);
            visible.addAll(selected.subList(page * pageSize, Math.min(selected.size(), (page + 1) * pageSize)));
            int labelWidth = Math.min(190, (w - 28) / 2);
            for (int i = 0; i < visible.size(); i++) {
                JsonObject f = visible.get(i);
                String k = key(f);
                int x = x0 + 14 + labelWidth, y = y0 + 56 + i * 41;
                int inputWidth = w - 28 - labelWidth;
                JsonArray choices = f.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    Button b = button(display(draft.get(k)), x, y, inputWidth, () -> {
                        int selectedIndex = -1;
                        for (int j = 0; j < choices.size(); j++) if (choices.get(j).getAsString().equals(draft.get(k))) selectedIndex = j;
                        draft.put(k, choices.get((selectedIndex + 1) % choices.size()).getAsString());
                        rebuild();
                    });
                    b.active = !saving;
                } else {
                    EditBox edit = new EditBox(font, x, y, inputWidth, 20, Component.literal(f.get("label").getAsString()));
                    edit.setMaxLength(f.get("type").getAsString().equals("string") ? f.get("max").getAsInt() : 24);
                    edit.setValue(draft.get(k));
                    edit.setResponder(v -> draft.put(k, v));
                    edit.setEditable(!saving);
                    addRenderableWidget(edit);
                }
            }
            button("<", x0 + 12, by - 46, 24, () -> { page--; rebuild(); }).active = page > 0;
            button(">", x0 + 80, by - 46, 24, () -> { page++; rebuild(); }).active = page + 1 < totalPages;
            button("다시 불러오기", x0 + w - 114, by - 46, 100, () -> {
                if (saving || !mayDiscard()) return;
                AdminClient.request("config");
                status = "서버 설정을 다시 불러오는 중…";
            }).active = !saving;
        }
        saveButton = button("저장·적용", x0 + 12, by, 84, this::save);
        saveButton.active = !saving && changedCount() > 0;
        button("돌아가기", x0 + w - 94, by, 80, this::onClose).active = !saving;
    }

    private String display(String value) {
        return switch (value) {
            case "true" -> "켜짐";
            case "false" -> "꺼짐";
            default -> value;
        };
    }

    private void save() {
        if (saving || changedCount() == 0) return;
        JsonObject changes = new JsonObject(), expected = new JsonObject(), req = new JsonObject();
        for (String k : draft.keySet()) if (changed(k)) {
            changes.addProperty(k, draft.get(k));
            expected.add(k, original.get(k).deepCopy());
        }
        req.addProperty("op", "config_save");
        req.add("changes", changes);
        req.add("expected", expected);
        saving = true;
        status = "서버에서 검사하고 저장하는 중…";
        AdminClient.send(req);
        rebuild();
    }

    private boolean mayDiscard() {
        if (changedCount() == 0 || System.currentTimeMillis() < discardUntil) return true;
        discardUntil = System.currentTimeMillis() + 3000;
        status = "저장하지 않은 변경이 있습니다. 3초 안에 다시 누르면 버립니다.";
        return false;
    }

    @Override public void onClose() {
        if (!saving && mayDiscard()) minecraft.gui.setScreen(parent);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == InputConstants.KEY_S) { save(); return true; }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        WishUi.panel(g, x0, y0, x0 + w, y0 + h, 1f);
        g.text(font, "✦ 새벽별 설정 · 관리자 전용", x0 + 14, y0 + 10, WishUi.GOLD, false);
        int labelWidth = Math.min(190, (w - 28) / 2);
        for (int i = 0; i < visible.size(); i++) {
            JsonObject f = visible.get(i);
            String label = (changed(key(f)) ? "● " : "") + f.get("label").getAsString();
            if (f.get("restart").getAsBoolean()) label += " *";
            int y = y0 + 56 + i * 41;
            g.text(font, font.plainSubstrByWidth(label, labelWidth - 6), x0 + 14, y + 6, changed(key(f)) ? WishUi.GOLD : WishUi.TEXT, false);
            g.text(font, font.plainSubstrByWidth(f.get("hint").getAsString(), w - 28), x0 + 14, y + 24, WishUi.TEXT_FAINT, false);
        }
        int by = y0 + h - 27;
        if (!groups.isEmpty()) g.text(font, (page + 1) + "/" + totalPages, x0 + 44, by - 40, WishUi.TEXT_DIM, false);
        g.text(font, font.plainSubstrByWidth(status, w - 28), x0 + 14, by - 18, WishUi.GOLD, false);
        g.text(font, font.plainSubstrByWidth(changedCount() + "개 변경 · * 재시작 필요", w - 206), x0 + 104, by + 6, WishUi.TEXT_FAINT, false);
        super.extractRenderState(g, mx, my, delta);
    }
}
