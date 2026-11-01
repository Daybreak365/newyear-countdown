package dev.newyear.countdown.ai.script;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Objects;

/** Shared, deterministic slot allocation used by validation and live activation. */
public final class AbilityBindings {
    private AbilityBindings() {}
    private static JsonObject read(ScriptRecord rec) {
        return rec.runtime != null && rec.runtime.has("controls") ? rec.runtime.getAsJsonObject("controls") : new JsonObject();
    }
    private static ScriptRecord conflict(ScriptRecord self, Collection<ScriptRecord> records, String player, int slot) {
        for (ScriptRecord other : records) {
            if (Objects.equals(other.id, self.id)) continue;
            if ((other.state.live() || other.state == ScriptRecord.State.SUSPENDED) && read(other).has(player + ":" + slot)) return other;
        }
        return null;
    }
    public static void bind(ScriptRecord self, Collection<ScriptRecord> records, String player, int slot, String action, String label) {
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("Control slot must be 0..3");
        if (!action.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid action id");
        ScriptRecord other = conflict(self, records, player, slot);
        if (other != null) throw new IllegalArgumentException("Control slot " + slot + " is reserved by " + other.name + "; use controls.assign for a free slot");
        JsonObject bindings = RuntimeState.section(self, "controls");
        String key = player + ":" + slot;
        if (bindings.size() >= 32 && !bindings.has(key)) throw new IllegalArgumentException("At most 32 control bindings per script");
        JsonObject binding = new JsonObject();
        binding.addProperty("player", player); binding.addProperty("slot", slot);
        binding.addProperty("action", action); binding.addProperty("label", label);
        bindings.add(key, binding);
    }
    public static int assign(ScriptRecord self, Collection<ScriptRecord> records, String player, String action, String label) {
        return assign(self, records, player, action, label, new JsonObject());
    }
    public static int assign(ScriptRecord self, Collection<ScriptRecord> records, String player, String action, String label, JsonObject previous) {
        JsonObject bindings = read(self);
        for (int slot = 0; slot < 4; slot++) {
            JsonObject existing = bindings.getAsJsonObject(player + ":" + slot);
            if (existing != null && action.equals(existing.get("action").getAsString())) {
                bind(self, records, player, slot, action, label);
                return slot;
            }
        }
        for (int slot = 0; slot < 4; slot++) {
            JsonObject old = previous.getAsJsonObject(player + ":" + slot);
            if (old != null && action.equals(old.get("action").getAsString())
                    && !bindings.has(player + ":" + slot) && conflict(self, records, player, slot) == null) {
                bind(self, records, player, slot, action, label);
                return slot;
            }
        }
        for (int slot = 0; slot < 4; slot++) {
            if (!bindings.has(player + ":" + slot) && conflict(self, records, player, slot) == null) {
                bind(self, records, player, slot, action, label);
                return slot;
            }
        }
        throw new IllegalArgumentException("All four ability slots are reserved. Clear/stop an existing ability or use another agreed trigger; do not overwrite it.");
    }
}
