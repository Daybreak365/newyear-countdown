package dev.newyear.countdown.ai;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiSettingsTest {
    @TempDir Path dir;

    private JsonObject expected(AiConfig config) {
        JsonObject values = new JsonObject();
        for (var f : AiSettings.snapshot(config).getAsJsonArray("fields")) {
            JsonObject field = f.getAsJsonObject();
            values.add(field.get("key").getAsString(), field.get("value"));
        }
        return values;
    }
    private JsonObject patch(String key, String value) {
        JsonObject changes = new JsonObject();
        changes.addProperty(key, value);
        return changes;
    }

    @Test void changesRoundTripWithoutTouchingPrivateSettings() throws Exception {
        AiConfig config = new AiConfig();
        config.model = "gpt-6.1-terra";
        config.extensionTestCommand = List.of("private-runner", "secret-argument");
        config.apiBase = "https://private-proxy.invalid/v1";
        config.scriptStepsPerRun = 123456;
        config.modelPrices.put("custom", new double[]{1, 2, 3});
        JsonObject changes = patch("model", "gpt-6.1-sol");
        changes.addProperty("dailyBudgetUsd", "7.5");
        changes.addProperty("autonomyEnabled", "false");
        changes.addProperty("extraInstructions", "다정하게 대답해 주세요.");
        AiConfig next = AiSettings.prepare(config, changes, expected(config));
        assertEquals("gpt-6.1-terra", config.model);
        assertEquals(5.0, config.dailyBudgetUsd);
        Path path = dir.resolve("config.json");
        AiConfig.writeConfig(next, path);
        AiConfig restored = new Gson().fromJson(Files.readString(path), AiConfig.class);
        assertEquals("gpt-6.1-sol", restored.model);
        assertEquals(7.5, restored.dailyBudgetUsd);
        assertFalse(restored.autonomyEnabled);
        assertEquals(config.extensionTestCommand, restored.extensionTestCommand);
        assertEquals(config.apiBase, restored.apiBase);
        assertEquals(123456, restored.scriptStepsPerRun);
        assertArrayEquals(new double[]{1, 2, 3}, restored.modelPrices.get("custom"));
        assertEquals("다정하게 대답해 주세요.", restored.extraInstructions);
        // Replacing an existing file is supported and leaves no partial file behind.
        restored.wishCooldownSeconds = 12;
        AiConfig.writeConfig(restored, path);
        assertEquals(12, new Gson().fromJson(Files.readString(path), AiConfig.class).wishCooldownSeconds);
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
    }

    @Test void rejectsUnlistedFieldsAndDoesNotExposeSensitiveConfiguration() {
        AiConfig config = new AiConfig();
        config.apiBase = "private-proxy";
        config.extensionTestCommand = List.of("secret-runner");
        String wire = AiSettings.snapshot(config).toString();
        assertFalse(wire.contains("private-proxy"));
        assertFalse(wire.contains("secret-runner"));
        assertFalse(wire.contains("apiKey"));
        assertTrue(wire.getBytes(StandardCharsets.UTF_8).length < 60000);
        for (String key : List.of("apiBase", "extensionTestCommand", "testApiKey", "configVersion", "enabled"))
            assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch(key, "x"), expected(config)));
    }

    @Test void invalidPatchIsAtomicAndNumbersMustBeFiniteAndWithinBounds() {
        AiConfig config = new AiConfig();
        for (String value : List.of("NaN", "Infinity", "-1", "100001", "oops"))
            assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch("dailyBudgetUsd", value), expected(config)));
        for (String value : List.of("0", "1.5", "17"))
            assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch("maxConcurrentRequests", value), expected(config)));
        JsonObject changes = patch("model", "custom-model");
        changes.addProperty("wishCooldownSeconds", "-1");
        assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, changes, expected(config)));
        assertEquals("gpt-6.1-sol", config.model);
        assertEquals(60, config.wishCooldownSeconds);
        assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch("autonomyEnabled", "yes"), expected(config)));
        assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch("model", "Sol please"), expected(config)));
    }

    @Test void staleEditsFailButUnrelatedChangesSurvive() {
        AiConfig config = new AiConfig();
        JsonObject old = expected(config);
        config.dailyBudgetUsd = 9;
        assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch("dailyBudgetUsd", "7"), old));
        AiConfig next = AiSettings.prepare(config, patch("wishCooldownSeconds", "20"), old);
        assertEquals(9, next.dailyBudgetUsd);
        assertEquals(20, next.wishCooldownSeconds);
        assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch("model", "custom"), new JsonObject()));
    }

    @Test void solEffortsAndPricesRespectOverrides() {
        AiConfig config = new AiConfig();
        assertThrows(IllegalArgumentException.class, () -> AiSettings.prepare(config, patch("reasoningEffort", "none"), expected(config)));
        assertEquals("max", AiSettings.prepare(config, patch("reasoningEffort", "max"), expected(config)).reasoningEffort);
        assertArrayEquals(new double[]{2, .1, 10}, config.priceFor("gpt-6.1-sol"));
        assertArrayEquals(new double[]{2, .1, 10}, config.priceFor("gpt-6.1-sol-2026-10-01"));
        config.modelPrices.put("gpt-6.1-sol", new double[]{4, .2, 20});
        assertArrayEquals(new double[]{4, .2, 20}, config.priceFor("gpt-6.1-sol"));
        assertArrayEquals(new double[]{2.5, .25, 15}, config.priceFor("custom"));
    }

    @Test void legacyWorldPolicyAndRestartRequirementsAreExplicit() {
        AiConfig config = new AiConfig();
        config.allowReplacingSolidBlocks = true;
        assertEquals("any", expected(config).get("worldEditMode").getAsString());
        AiConfig next = AiSettings.prepare(config, patch("worldEditMode", "empty_only"), expected(config));
        assertEquals("empty_only", next.editMode());
        assertFalse(next.allowReplacingSolidBlocks);
        assertTrue(AiSettings.requiresRestart(patch("maxConcurrentRequests", "4")));
        assertTrue(AiSettings.requiresRestart(patch("maxQueuedRequests", "40")));
        assertTrue(AiSettings.requiresRestart(patch("memoryMaxPerPlayer", "500")));
        assertFalse(AiSettings.requiresRestart(patch("model", "custom")));
    }
}
