package dev.newyear.countdown.ai;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessionApiKeyTest {
    private static final String KEY = "sk-session-unit-test-only-123456789";
    @TempDir Path dir;

    @AfterEach void clear() {
        AiConfig.clearSessionApiKey();
        AiConfig.testApiKey = null;
    }

    @Test void onlyTheCurrentOwnerDisconnectClearsTheKey() {
        SessionApiKey keys = new SessionApiKey();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        keys.set(first, KEY);
        keys.onDisconnect(second);
        assertEquals(KEY, keys.get());
        keys.set(second, KEY + "-replaced");
        keys.onDisconnect(first);
        assertEquals(KEY + "-replaced", keys.get());
        keys.onDisconnect(second);
        assertFalse(keys.present());
        assertEquals("", keys.get());
    }

    @Test void rejectsInvalidKeysWithoutDestroyingTheWorkingKeyOrEchoingTheInput() {
        SessionApiKey keys = new SessionApiKey();
        UUID player = UUID.randomUUID();
        keys.set(player, KEY);
        for (String bad : new String[]{"", "short-secret", "sk-secret with spaces-123", "sk-secret\r\nheader-injection", "k".repeat(513)}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> keys.set(player, bad));
            if (!bad.isEmpty()) assertFalse(error.getMessage().contains(bad));
            assertEquals(KEY, keys.get());
        }
        assertThrows(IllegalArgumentException.class, () -> keys.set(null, KEY));
        assertEquals(KEY, keys.get());
    }

    @Test void manualClearRemovesTheCredential() {
        SessionApiKey keys = new SessionApiKey();
        keys.set(UUID.randomUUID(), "  " + KEY + "  ");
        assertEquals(KEY, keys.get());
        keys.clear();
        keys.clear();
        assertFalse(keys.present());
    }

    @Test void temporaryKeyOverridesEnvironmentAndClearRestoresIt() {
        AiConfig.testApiKey = null;
        AiConfig.clearSessionApiKey();
        String original = AiConfig.apiKey();
        String source = AiConfig.apiKeySource();
        UUID player = UUID.randomUUID();
        AiConfig.setSessionApiKey(player, KEY);
        assertEquals(KEY, AiConfig.apiKey());
        assertEquals("session", AiConfig.apiKeySource());
        assertTrue(AiConfig.hasApiKey());
        AiConfig.clearSessionApiKeyFor(player);
        assertEquals(original, AiConfig.apiKey());
        assertEquals(source, AiConfig.apiKeySource());
    }

    @Test void keyIsNeverSerializedInConfigOrAdminSettings() throws Exception {
        AiConfig.setSessionApiKey(UUID.randomUUID(), KEY);
        AiConfig config = new AiConfig();
        assertFalse(new Gson().toJson(config).contains(KEY));
        assertFalse(AiSettings.snapshot(config).toString().contains(KEY));
        Path path = dir.resolve("config.json");
        AiConfig.writeConfig(config, path);
        String saved = Files.readString(path);
        assertFalse(saved.contains(KEY));
        assertFalse(saved.contains("SESSION_KEY"));
        assertEquals(KEY, AiConfig.apiKey(), "Saving ordinary config must not clear a session key");
    }

    @Test void engineStopClearsKeyEvenWhenNoWorldIsRunning() {
        AiConfig.setSessionApiKey(UUID.randomUUID(), KEY);
        WishEngine.stop();
        assertFalse(AiConfig.hasSessionApiKey());
    }

    @Test void selfTestCredentialStaysSeparateFromTheSessionCredential() {
        AiConfig.setSessionApiKey(UUID.randomUUID(), KEY);
        AiConfig.testApiKey = "sk-fake-server-only";
        assertEquals("sk-fake-server-only", AiConfig.apiKey());
        AiConfig.testApiKey = null;
        assertEquals(KEY, AiConfig.apiKey());
    }
}
