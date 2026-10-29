package dev.newyear.countdown.ai.script.api;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 레지스트리 조회·아이템 생성 등 스크립트 API 공통 도우미. */
public final class McUtil {
    private McUtil() {}

    public static final String DATA_KEY = "wishai";

    public static Identifier parseId(Interpreter in, String raw, String what) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (id == null) throw in.error("Invalid " + what + " id '" + raw + "'");
        return id;
    }

    private static <T> T lookup(Interpreter in, Registry<T> reg, String raw, String what) {
        Identifier id = parseId(in, raw, what);
        if (!reg.containsKey(id)) {
            throw in.error("Unknown " + what + " '" + raw + "'" + suggest(reg, id.getPath()));
        }
        return reg.getValue(id);
    }

    private static <T> String suggest(Registry<T> reg, String path) {
        List<String> close = new ArrayList<>();
        for (Identifier k : reg.keySet()) {
            String p = k.getPath();
            if (p.contains(path) || path.contains(p) && p.length() > 3) close.add(k.getPath());
            if (close.size() >= 6) break;
        }
        return close.isEmpty() ? "" : " (similar: " + String.join(", ", close) + ")";
    }

    public static Item item(Interpreter in, String raw) {
        Item it = lookup(in, BuiltInRegistries.ITEM, raw, "item");
        if (it == Items.AIR) throw in.error("Item 'air' cannot be used");
        return it;
    }

    public static EntityType<?> entityType(Interpreter in, String raw) {
        return lookup(in, BuiltInRegistries.ENTITY_TYPE, raw, "entity type");
    }

    public static Holder<MobEffect> effect(Interpreter in, String raw) {
        Identifier id = parseId(in, raw, "effect");
        return BuiltInRegistries.MOB_EFFECT.get(id).map(h -> (Holder<MobEffect>) h)
                .orElseThrow(() -> in.error("Unknown effect '" + raw + "'" + suggest(BuiltInRegistries.MOB_EFFECT, id.getPath())));
    }

    public static SoundEvent sound(Interpreter in, String raw) {
        return lookup(in, BuiltInRegistries.SOUND_EVENT, raw, "sound");
    }

    public static ParticleOptions particle(Interpreter in, String raw, JsObject opts) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("dust") || s.equals("minecraft:dust")) {
            int color = color(Args.optStr(opts, "color", "#ffd27a"), 0xFFD27A);
            float scale = (float) Math.max(0.2, Math.min(4.0, Args.optNum(opts, "scale", 1.0)));
            return new DustParticleOptions(color, scale);
        }
        ParticleType<?> type = lookup(in, BuiltInRegistries.PARTICLE_TYPE, s, "particle");
        if (type instanceof SimpleParticleType simple) return simple;
        throw in.error("Particle '" + raw + "' needs extra data and is not supported. Use simple particles like end_rod, firework, happy_villager, heart, note, glow, enchant, flame, soul_fire_flame, cherry_leaves, snowflake, totem_of_undying, or 'dust' with {color, scale}");
    }

    public static BlockState block(Interpreter in, String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        try {
            BlockStateParser.BlockResult r = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false);
            return r.blockState();
        } catch (CommandSyntaxException e) {
            throw in.error("Unknown block '" + raw + "': " + e.getRawMessage().getString());
        }
    }

    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    public static String blockString(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    public static BlockState parseBlockString(String s) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
    }

    public static String itemId(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    public static String entityId(EntityType<?> t) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
    }

    public static String dimId(Level level) {
        return level.dimension().identifier().toString();
    }

    public static ServerLevel level(MinecraftServer server, String dim) {
        if (dim == null) return server.overworld();
        for (ServerLevel l : server.getAllLevels()) if (dimId(l).equals(dim)) return l;
        return null;
    }

    public static BlockPos blockPos(double[] p) {
        return BlockPos.containing(p[0], p[1], p[2]);
    }

    public static int color(String s, int def) {
        if (s == null) return def;
        String t = s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "red": return 0xE05555;
            case "orange": return 0xF0A040;
            case "yellow": return 0xF6E05A;
            case "gold": return 0xE8B84A;
            case "green": return 0x6CCB6C;
            case "lime": return 0x9EE85A;
            case "aqua", "cyan": return 0x5AD8E8;
            case "blue": return 0x5A7AE8;
            case "purple": return 0xA05AE8;
            case "pink": return 0xF08AC8;
            case "white": return 0xF4F4F4;
            case "gray", "grey": return 0x9A9A9A;
            case "black": return 0x202020;
            default: break;
        }
        if (t.startsWith("#")) t = t.substring(1);
        if (t.startsWith("0x")) t = t.substring(2);
        try {
            if (t.length() == 6) return Integer.parseInt(t, 16);
        } catch (NumberFormatException ignored) { }
        return def;
    }

    /** 스크립트가 넘긴 텍스트를 안전한 컴포넌트로 만든다 (길이 제한, 서식 코드 제거). */
    public static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '§' || (c < 32 && c != '\n') || c == 127) continue;
            sb.append(c);
        }
        String r = sb.toString();
        return r.length() > max ? r.substring(0, max) : r;
    }

    public static MutableComponent text(String s, String color) {
        MutableComponent c = Component.literal(clean(s, 400));
        if (color != null && !color.isEmpty()) c.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color(color, 0xFFFFFF))));
        return c;
    }

    /**
     * 아이템 생성. opts: {name, lore: [..] | "..", glow, color, data: {...}}.
     * data 는 아이템 CUSTOM_DATA 의 wishai 항목에 저장되어 이벤트에서 다시 읽을 수 있다 (특별한 아이템 표식).
     */
    public static ItemStack makeStack(Interpreter in, String id, int count, JsObject opts, String scriptId) {
        Item item = item(in, id);
        int max = item.getDefaultMaxStackSize();
        ItemStack st = new ItemStack(item, Math.max(1, Math.min(count, max)));
        String name = Args.optStr(opts, "name", null);
        if (name != null) st.set(DataComponents.CUSTOM_NAME, text(name, Args.optStr(opts, "color", "#f3d98a")).withStyle(s -> s.withItalic(false)));
        Object lore = opts.get("lore");
        List<Component> lines = new ArrayList<>();
        if (lore instanceof String ls) for (String l : ls.split("\n")) lines.add(text(l, "#b8a9c9").withStyle(s -> s.withItalic(false)));
        else if (lore instanceof Values.JsArray la) for (Object o : la.list) if (lines.size() < 8) lines.add(text(Interpreter.toStr(o), "#b8a9c9").withStyle(s -> s.withItalic(false)));
        if (!lines.isEmpty()) st.set(DataComponents.LORE, new ItemLore(lines.subList(0, Math.min(8, lines.size()))));
        if (Args.optBool(opts, "glow", false)) st.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        Object data = opts.get("data");
        if ((data instanceof JsObject) || scriptId != null && (name != null || data instanceof JsObject)) {
            CompoundTag tag = new CompoundTag();
            CompoundTag w = new CompoundTag();
            if (scriptId != null) w.putString("script", scriptId);
            if (data instanceof JsObject d) {
                String json = dev.newyear.countdown.ai.script.lang.Stdlib.toJson(d).toString();
                if (json.length() > 2000) throw in.error("item data too large (max 2000 chars of JSON)");
                w.putString("data", json);
            }
            tag.put(DATA_KEY, w);
            st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return st;
    }

    /** 아이템 정보 객체 {id, count, name, special, data}. */
    public static JsObject itemInfo(ItemStack st) {
        JsObject o = new JsObject();
        if (st == null || st.isEmpty()) {
            o.put("id", "minecraft:air").put("count", 0.0).put("name", "");
            return o;
        }
        o.put("id", itemId(st));
        o.put("count", (double) st.getCount());
        o.put("name", st.getHoverName().getString());
        CustomData cd = st.get(DataComponents.CUSTOM_DATA);
        boolean special = false;
        if (cd != null) {
            CompoundTag tag = cd.copyTag();
            if (tag.contains(DATA_KEY)) {
                special = true;
                CompoundTag w = tag.getCompoundOrEmpty(DATA_KEY);
                String json = w.getStringOr("data", "");
                if (!json.isEmpty()) {
                    try {
                        o.put("data", dev.newyear.countdown.ai.script.lang.Stdlib.fromJson(com.google.gson.JsonParser.parseString(json), null));
                    } catch (RuntimeException ignored) { }
                }
                String script = w.getStringOr("script", "");
                if (!script.isEmpty()) o.put("script", script);
            }
        }
        o.put("special", special);
        return o;
    }

    /** 다른 플레이어에게 쓰면 해로운 효과 (동의 필요). */
    public static final Set<String> HARMFUL_EFFECTS = Set.of("poison", "wither", "slowness", "blindness", "nausea", "weakness",
            "mining_fatigue", "hunger", "levitation", "darkness", "bad_omen", "instant_damage", "unluck", "infested",
            "oozing", "weaving", "wind_charged", "raid_omen", "trial_omen");
}
