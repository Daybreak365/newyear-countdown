package dev.newyear.countdown.ai.script;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.*;
import dev.newyear.countdown.ai.script.lang.Values.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Codec-backed item editing. No raw NBT, registry mutation or container injection. */
public final class RuntimeItems {
    private RuntimeItems() {}
    private static final Set<String> EDITABLE = Set.of("damage", "max_damage", "max_stack_size", "unbreakable",
            "enchantments", "stored_enchantments", "attribute_modifiers", "food", "consumable", "use_cooldown",
            "enchantment_glint_override", "rarity", "repair_cost", "dyed_color", "trim", "equippable",
            "tool", "weapon", "use_duration", "glider", "item_model", "custom_model_data");

    public static ApiObject api(ScriptInstance inst) {
        return new ApiObject("items")
            .fn("components", "() → editable component ids discovered from the current game registry", (in,s,a) -> {
                JsArray out = new JsArray();
                for (var id : BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) if (EDITABLE.contains(id.getPath())) out.list.add(id.toString());
                return out;
            })
            .fn("get", "(player, slot) → item including editable components; slot is inventory index or mainhand/offhand/head/chest/legs/feet", (in,s,a) -> {
                ServerPlayer p = player(in, inst, Args.get(a,0), false);
                if (inst.dry) return new JsObject().put("id", "minecraft:diamond_sword").put("count", 1.0).put("components", new JsObject());
                ItemStack st = stack(in,p,Args.get(a,1));
                JsObject result = McUtil.itemInfo(st), components = new JsObject();
                for (String id : EDITABLE) {
                    DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.withDefaultNamespace(id));
                    if (type != null && st.has(type) && type.codec() != null) components.put(id, encode(in,st,type));
                }
                return result.put("components",components);
            })
            .fn("patch", "(player, slot, components) → true; atomic codec-validated patch, null removes a component. Enchantments use current vanilla JSON format.", (in,s,a) -> {
                ServerPlayer p = player(in,inst,Args.get(a,0),true);
                JsObject patch = Args.opts(a,2);
                if (patch.map.isEmpty()) throw in.error("components must be a nonempty object; use items.components()");
                ItemStack original = inst.dry ? new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD) : stack(in,p,Args.get(a,1));
                if (original.isEmpty()) throw in.error("That slot is empty");
                ItemStack copy = original.copy();
                apply(in,copy,patch);
                if (copy.getCount() > copy.getMaxStackSize()) throw in.error("New max stack size is smaller than the stack count");
                if (inst.dry) inst.effect("item component patch " + patch.map.keySet());
                else { set(in,p,Args.get(a,1),copy); p.inventoryMenu.broadcastChanges(); }
                return true;
            });
    }

    public static void apply(Interpreter in, ItemStack st, JsObject patch) {
        if (patch.map.size() > 24 || Stdlib.toJson(patch).toString().length() > 16000) throw in.error("Component patch too large");
        ItemStack copy = st.copy();
        for (var entry : patch.map.entrySet()) {
            Identifier id = McUtil.parseId(in, entry.getKey(), "component");
            if (!id.getNamespace().equals("minecraft") || !EDITABLE.contains(id.getPath())) throw in.error("Unsupported component: " + id + "; use items.components()");
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
            if (type == null || type.codec() == null) throw in.error("Component has no persistent codec: " + id);
            patchOne(in,copy,type,entry.getValue());
        }
        var valid=ItemStack.validateStrict(copy);
        if(valid.error().isPresent())throw in.error("Invalid item component combination: "+valid.error().get().message());
        st.applyComponents(copy.getComponentsPatch());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void patchOne(Interpreter in, ItemStack st, DataComponentType type, Object value) {
        if (value == null) { st.remove(type); return; }
        var ops = RegistryOps.create(JsonOps.INSTANCE, WishEngine.get().server().registryAccess());
        var parsed = type.codec().parse(ops, Stdlib.toJson(value));
        if (parsed.error().isPresent()) throw in.error("Invalid component: " + parsed.error().get());
        st.set(type, parsed.result().orElseThrow());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object encode(Interpreter in, ItemStack st, DataComponentType type) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, WishEngine.get().server().registryAccess());
        JsonElement json = (JsonElement) type.codec().encodeStart(ops, st.get(type)).result().orElse(com.google.gson.JsonNull.INSTANCE);
        return Stdlib.fromJson(json,in,null,0);
    }

    static ServerPlayer player(Interpreter in, ScriptInstance inst, Object v, boolean write) {
        if (!(v instanceof PlayerRef ref)) throw in.error("Expected a Player handle");
        if (write) Policy.checkAffect(in,inst,ref,"edit inventory/state of",true);
        else if (!Objects.equals(inst.rec.owner,ref.id.toString())) Policy.checkAffect(in,inst,ref,"inspect inventory of",true);
        if (inst.dry) return null;
        ServerPlayer p = ref.live();
        if (p == null) throw in.error("Player is offline");
        return p;
    }

    static ItemStack stack(Interpreter in, ServerPlayer p, Object slot) {
        if (slot instanceof Number n) {
            int i = n.intValue();
            if (i < 0 || i >= p.getInventory().getContainerSize() || n.doubleValue()!=i) throw in.error("Invalid inventory slot");
            return p.getInventory().getItem(i);
        }
        return p.getItemBySlot(equipment(in,slot));
    }
    static void set(Interpreter in, ServerPlayer p, Object slot, ItemStack st) {
        stack(in,p,slot); // validate before writing
        if (slot instanceof Number n) p.getInventory().setItem(n.intValue(),st);
        else p.setItemSlot(equipment(in,slot),st);
    }
    private static net.minecraft.world.entity.EquipmentSlot equipment(Interpreter in,Object slot) {
        try { return net.minecraft.world.entity.EquipmentSlot.valueOf(Interpreter.toStr(slot).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw in.error("Slot must be an inventory index or mainhand/offhand/head/chest/legs/feet"); }
    }
}
