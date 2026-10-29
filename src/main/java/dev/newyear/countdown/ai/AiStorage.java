package dev.newyear.countdown.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.usage.UsageTracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 월드 폴더 안 newyearcountdown_ai/ 에 JSON 으로 저장한다.
 * 쓰기는 임시 파일 → 원자적 이동, 이전 파일은 .bak 로 보관해서 손상되면 백업에서 복구한다.
 */
public final class AiStorage {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final Path dir;

    public static final class UsageFile {
        public UsageTracker.Day today;
        public UsageTracker.Day lifetime;
    }

    public AiStorage(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public AiState loadState() {
        AiState s = read("state.json", AiState.class);
        if (s == null) s = new AiState();
        List<dev.newyear.countdown.ai.script.ScriptRecord> scripts = read("scripts.json", new TypeToken<List<dev.newyear.countdown.ai.script.ScriptRecord>>() {}.getType());
        if (scripts != null) {
            s.scripts = new java.util.LinkedHashMap<>();
            for (var r : scripts) if (r != null && r.id != null) s.scripts.put(r.id, r);
        }
        s.fixNulls();
        return s;
    }

    public List<MemoryRecord> loadMemory() {
        List<MemoryRecord> list = read("memory.json", new TypeToken<List<MemoryRecord>>() {}.getType());
        return list == null ? new ArrayList<>() : list;
    }

    public UsageFile loadUsage() {
        return read("usage.json", UsageFile.class);
    }

    /** 서버 스레드에서 문자열로 직렬화한 뒤 (상태가 바뀌지 않게), 쓰기는 호출자가 고른 스레드에서 한다. */
    public record Snapshot(String state, String scripts, String memory, String usage) {}

    public Snapshot snapshot(AiState state, List<MemoryRecord> memory, UsageFile usage) {
        var scripts = state.scripts;
        state.scripts = new java.util.LinkedHashMap<>();
        String st;
        try {
            st = GSON.toJson(state);
        } finally {
            state.scripts = scripts;
        }
        return new Snapshot(st, GSON.toJson(new ArrayList<>(scripts.values())), GSON.toJson(memory), GSON.toJson(usage));
    }

    public synchronized void write(Snapshot s) {
        try {
            Files.createDirectories(dir);
            writeAtomic("state.json", s.state());
            writeAtomic("scripts.json", s.scripts());
            writeAtomic("memory.json", s.memory());
            writeAtomic("usage.json", s.usage());
        } catch (IOException e) {
            NewYearCountdown.LOGGER.error("[WishAI] Failed to save AI data to {}", dir, e);
        }
    }

    private void writeAtomic(String name, String content) throws IOException {
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        if (Files.exists(target)) Files.copy(target, dir.resolve(name + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private <T> T read(String name, java.lang.reflect.Type type) {
        T v = tryRead(dir.resolve(name), type);
        if (v != null) return v;
        Path bak = dir.resolve(name + ".bak");
        if (Files.exists(bak)) {
            v = tryRead(bak, type);
            if (v != null) NewYearCountdown.LOGGER.warn("[WishAI] {} was unreadable; recovered from backup", name);
        }
        return v;
    }

    private <T> T tryRead(Path p, java.lang.reflect.Type type) {
        if (!Files.exists(p)) return null;
        try {
            return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            NewYearCountdown.LOGGER.error("[WishAI] Could not read {}: {}", p, e.toString());
            return null;
        }
    }
}
