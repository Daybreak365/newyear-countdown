package dev.newyear.countdown.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 개인 HUD 설정 (config/newyearcountdown-client.json). */
public final class ClientConfig {
    public enum Position {
        TOP_CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_CENTER;

        public Position next() {
            return values()[(ordinal() + 1) % values().length];
        }
        public String key() {
            return "position.newyearcountdown." + name().toLowerCase();
        }
    }

    public static final float[] SCALES = {0.6f, 0.75f, 1.0f, 1.25f, 1.5f};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientConfig instance = new ClientConfig();

    public boolean enabled = true;
    public Position position = Position.TOP_LEFT;
    public int scaleIndex = 1;
    public HudTheme theme = HudTheme.CHRISTMAS;
    /** 마지막 30초 째깍 소리. */
    public boolean tickSound = true;

    public static ClientConfig get() {
        return instance;
    }

    public float scale() {
        return SCALES[Math.floorMod(scaleIndex, SCALES.length)];
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("newyearcountdown-client.json");
    }

    public static void load() {
        if (!Files.exists(path())) return;
        try (Reader r = Files.newBufferedReader(path(), StandardCharsets.UTF_8)) {
            ClientConfig loaded = GSON.fromJson(r, ClientConfig.class);
            if (loaded != null) {
                if (loaded.position == null) loaded.position = Position.TOP_LEFT;
                if (loaded.theme == null) loaded.theme = HudTheme.CHRISTMAS;
                instance = loaded;
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            GSON.toJson(instance, w);
        } catch (Exception ignored) {
        }
    }
}
