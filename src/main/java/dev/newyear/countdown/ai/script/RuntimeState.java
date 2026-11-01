package dev.newyear.countdown.ai.script;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.*;
import dev.newyear.countdown.ai.script.lang.Values.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

public final class RuntimeState {
    private RuntimeState() {}
    public static JsonObject section(ScriptRecord rec, String name) {
        if (rec.runtime == null) rec.runtime = new JsonObject();
        if (!rec.runtime.has(name)) rec.runtime.add(name,new JsonObject());
        return rec.runtime.getAsJsonObject(name);
    }
    static LivingEntity target(Interpreter in, ScriptInstance inst, Object value) {
        if (value instanceof PlayerRef p) return RuntimeItems.player(in,inst,p,true);
        if (value instanceof EntityRef ref) {
            if (!inst.rec.entities.contains(ref.id.toString())) throw in.error("Only script-owned creatures can be modified");
            if (inst.dry) return null;
            if (ref.live() instanceof LivingEntity entity) return entity;
        }
        throw in.error("Expected an online Player or owned living Entity");
    }
    private static String uuid(Object value) { return value instanceof PlayerRef p?p.id.toString():((EntityRef)value).id.toString(); }

    public static ApiObject attributes(ScriptInstance inst) {
        return new ApiObject("attributes")
            .fn("list","() → current registry attribute ids (including gravity, scale and movement_speed when supported)",(in,s,a)->{
                JsArray out=new JsArray(); for(var key:BuiltInRegistries.ATTRIBUTE.keySet()) out.list.add(key.toString()); return out;
            })
            .fn("get","(target,id) → {base,value}",(in,s,a)->{
                LivingEntity entity=target(in,inst,Args.get(a,0));
                var holder=BuiltInRegistries.ATTRIBUTE.get(McUtil.parseId(in,Args.str(in,a,1,"attribute"),"attribute")).orElseThrow(()->in.error("Unknown attribute"));
                if(inst.dry) return new JsObject().put("base",1.0).put("value",1.0);
                var attr=entity.getAttribute(holder); if(attr==null) throw in.error("Target does not have that attribute");
                return new JsObject().put("base",attr.getBaseValue()).put("value",attr.getValue());
            })
            .fn("modify","(target,id,key,amount,operation='add') → modifier id. add/multiply_base/multiply_total; removed when script ends. Same key replaces.",(in,s,a)->{
                Object value=Args.get(a,0); LivingEntity entity=target(in,inst,value);
                Identifier attribute=McUtil.parseId(in,Args.str(in,a,1,"attribute"),"attribute");
                var holder=BuiltInRegistries.ATTRIBUTE.get(attribute).orElseThrow(()->in.error("Unknown attribute"));
                String key=Args.str(in,a,2,"key"); if(!key.matches("[a-z0-9_/-]{1,40}")) throw in.error("Invalid modifier key");
                double amount=Args.num(in,a,3,"amount"); if(Math.abs(amount)>1024) throw in.error("Attribute amount exceeds 1024");
                AttributeModifier.Operation operation=switch(Args.str(a,4,"add")) {
                    case "add"->AttributeModifier.Operation.ADD_VALUE;
                    case "multiply_base"->AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    case "multiply_total"->AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                    default->throw in.error("Unknown modifier operation");
                };
                Identifier id=Identifier.fromNamespaceAndPath("newyearcountdown",inst.rec.id+"/"+key);
                if(inst.dry) {inst.effect("attribute "+attribute+" "+amount);return id.toString();}
                var attr=entity.getAttribute(holder);if(attr==null) throw in.error("Target does not have that attribute");
                JsonObject entries=section(inst.rec,"attributes"); String recordKey=uuid(value)+"|"+attribute+"|"+key;
                if(!entries.has(recordKey)&&entries.size()>=64)throw in.error("Too many attribute modifiers");
                JsonObject record=new JsonObject();record.addProperty("entity",uuid(value));record.addProperty("attribute",attribute.toString());record.addProperty("id",id.toString());record.addProperty("amount",amount);record.addProperty("operation",operation.name());entries.add(recordKey,record);
                attr.addOrUpdateTransientModifier(new AttributeModifier(id,amount,operation));return id.toString();
            })
            .fn("remove","(target,id,key) → true; remove this script's modifier",(in,s,a)->{
                Object value=Args.get(a,0);LivingEntity entity=target(in,inst,value);
                Identifier attrId=McUtil.parseId(in,Args.str(in,a,1,"attribute"),"attribute");String key=Args.str(in,a,2,"key");
                if(!inst.dry){var holder=BuiltInRegistries.ATTRIBUTE.get(attrId).orElseThrow(()->in.error("Unknown attribute"));var attr=entity.getAttribute(holder);if(attr!=null)attr.removeModifier(Identifier.fromNamespaceAndPath("newyearcountdown",inst.rec.id+"/"+key));section(inst.rec,"attributes").remove(uuid(value)+"|"+attrId+"|"+key);}return true;
            });
    }

    public static ApiObject snapshots(ScriptInstance inst) {
        return new ApiObject("snapshots")
            .fn("capture","(player,key,{equipment:false}) → key; saves location, health, food and optionally tracked equipped stacks. Max 8. Does not capture XP or untracked historical items.",(in,s,a)->{
                Object value=Args.get(a,0);ServerPlayer p=RuntimeItems.player(in,inst,value,true);String key=Args.str(in,a,1,"key");
                if(!key.matches("[a-zA-Z0-9_-]{1,40}"))throw in.error("Invalid snapshot key");
                if(inst.dry){inst.effect("snapshot capture "+key);return key;}
                JsonObject entries=section(inst.rec,"snapshots");if(!entries.has(key)&&entries.size()>=8)throw in.error("Snapshot limit is 8");
                JsonObject data=new JsonObject();data.addProperty("player",p.getUUID().toString());data.addProperty("dimension",McUtil.dimId(p.level()));data.addProperty("x",p.getX());data.addProperty("y",p.getY());data.addProperty("z",p.getZ());data.addProperty("health",p.getHealth());data.addProperty("food",p.getFoodData().getFoodLevel());
                JsonObject equipment=new JsonObject();
                if(Args.optBool(Args.opts(a,2),"equipment",false))for(EquipmentSlot slot:EquipmentSlot.values()) {
                    ItemStack stack=p.getItemBySlot(slot);if(stack.isEmpty())continue;
                    String token=UUID.randomUUID().toString();CompoundTag tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();tag.putString("wishai_checkpoint",token);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
                    JsonObject item=new JsonObject();item.addProperty("token",token);item.addProperty("count",stack.getCount());
                    item.add("stack",ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,p.registryAccess()),stack).getOrThrow());equipment.add(slot.name(),item);
                }
                data.add("equipment",equipment);entries.add(key,data);p.inventoryMenu.broadcastChanges();return key;
            })
            .fn("restore","(player,key) → true; SINGLE USE. Same dimension, collision/range checked. Equipment must still be the exact tracked stack; missing/moved items fail atomically, never duplicated.",(in,s,a)->{
                ServerPlayer p=RuntimeItems.player(in,inst,Args.get(a,0),true);String key=Args.str(in,a,1,"key");
                if(inst.dry){inst.effect("snapshot restore "+key);return true;}
                JsonObject entries=section(inst.rec,"snapshots");if(!entries.has(key))throw in.error("No unused snapshot: "+key);
                JsonObject data=entries.getAsJsonObject(key);if(!data.get("player").getAsString().equals(p.getUUID().toString()))throw in.error("Snapshot belongs to another player");
                if(!data.get("dimension").getAsString().equals(McUtil.dimId(p.level())))throw in.error("Snapshot is in another dimension");
                double[] at={data.get("x").getAsDouble(),data.get("y").getAsDouble(),data.get("z").getAsDouble()};Policy.checkRange(in,inst,at);
                Policy.checkTeleport(in,inst,p,at);
                if(!p.level().noCollision(p,p.getBoundingBox().move(at[0]-p.getX(),at[1]-p.getY(),at[2]-p.getZ())))throw in.error("Checkpoint is obstructed");
                Map<EquipmentSlot,ItemStack> restored=new LinkedHashMap<>();
                for(var entry:data.getAsJsonObject("equipment").entrySet()) {
                    EquipmentSlot slot=EquipmentSlot.valueOf(entry.getKey());JsonObject record=entry.getValue().getAsJsonObject();ItemStack current=p.getItemBySlot(slot);
                    String token=current.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("wishai_checkpoint").orElse("");
                    if(!token.equals(record.get("token").getAsString())||current.getCount()!=record.get("count").getAsInt())throw in.error("Tracked equipment missing or changed in "+slot+"; no items restored");
                    restored.put(slot,ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,p.registryAccess()),record.get("stack")).getOrThrow());
                }
                entries.remove(key);restored.forEach(p::setItemSlot);p.teleportTo(at[0],at[1],at[2]);p.setHealth(Math.max(1,Math.min(p.getMaxHealth(),data.get("health").getAsFloat())));p.getFoodData().setFoodLevel(data.get("food").getAsInt());p.inventoryMenu.broadcastChanges();return true;
            })
            .fn("discard","(key) delete a checkpoint",(in,s,a)->{if(!inst.dry)section(inst.rec,"snapshots").remove(Args.str(in,a,0,"key"));return true;});
    }

    public static void entityLoaded(Entity entity) {
        if(!(entity instanceof LivingEntity living)||WishEngine.get()==null)return;
        for(ScriptRecord rec:WishEngine.get().state().scripts.values())applyAttributes(rec,living,rec.state.live());
    }
    private static void applyAttributes(ScriptRecord rec,LivingEntity entity,boolean active) {
        for(var entry:section(rec,"attributes").entrySet())try {
            JsonObject r=entry.getValue().getAsJsonObject();if(!r.get("entity").getAsString().equals(entity.getUUID().toString()))continue;
            var holder=BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(r.get("attribute").getAsString()));if(holder.isEmpty())continue;
            var attr=entity.getAttribute(holder.get());if(attr==null)continue;Identifier id=Identifier.parse(r.get("id").getAsString());
            if(active)attr.addOrUpdateTransientModifier(new AttributeModifier(id,r.get("amount").getAsDouble(),AttributeModifier.Operation.valueOf(r.get("operation").getAsString())));else attr.removeModifier(id);
        }catch(RuntimeException ignored) { }
    }
    public static void cleanup(ScriptRecord rec) {
        suspend(rec);
        section(rec,"snapshots").asMap().clear();
        section(rec,"projectiles").asMap().clear();
    }
    public static void restore(ScriptRecord rec) { applyLoaded(rec,true); }
    public static void suspend(ScriptRecord rec) { applyLoaded(rec,false); }
    private static void applyLoaded(ScriptRecord rec, boolean active) {
        if(WishEngine.get()==null)return;
        Set<String> ids=new HashSet<>();for(var entry:section(rec,"attributes").entrySet())ids.add(entry.getValue().getAsJsonObject().get("entity").getAsString());
        for(String id:ids){Entity entity=WishEngine.get().player(UUID.fromString(id));if(entity==null)for(var level:WishEngine.get().server().getAllLevels()){entity=level.getEntity(UUID.fromString(id));if(entity!=null)break;}if(entity instanceof LivingEntity living)applyAttributes(rec,living,active);}
    }
}
