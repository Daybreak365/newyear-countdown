package dev.newyear.countdown.gacha;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.Item;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.Util;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** 캡슐에서 나오는 새해 기념품 목록과 확률. */
public final class Souvenirs {
    public record Entry(Item item, int weight) {}

    public static final List<Entry> ALL = new ArrayList<>();
    private static int totalWeight;

    /** 몸에 쓰는 2027 안경 (머리 칸에 장착). 갑옷 수치는 모두 0. */
    public static final RegistryEntry<ArmorMaterial> GLASSES_MATERIAL = Registry.registerReference(
            Registries.ARMOR_MATERIAL, id("party_glasses"),
            new ArmorMaterial(Util.make(new EnumMap<>(ArmorItem.Type.class), m -> {
                for (ArmorItem.Type t : ArmorItem.Type.values()) m.put(t, 0);
            }), 1, SoundEvents.ITEM_ARMOR_EQUIP_GENERIC, () -> Ingredient.EMPTY,
                    List.of(new ArmorMaterial.Layer(id("party_glasses"))), 0f, 0f));

    public static final Item GLASSES = add("party_glasses",
            new ArmorItem(GLASSES_MATERIAL, ArmorItem.Type.HELMET, new Item.Settings().maxCount(1).rarity(Rarity.RARE)), 14);
    public static final Item LUCKY_POUCH = add("lucky_pouch", new SouvenirItem(new Item.Settings(), SouvenirItem.Kind.POUCH), 20);
    public static final Item SHEEP_PLUSH = add("sheep_plush", new SouvenirItem(new Item.Settings(), SouvenirItem.Kind.PLUSH), 15);
    public static final Item MINI_BELL = add("mini_bell", new SouvenirItem(new Item.Settings().rarity(Rarity.UNCOMMON), SouvenirItem.Kind.BELL), 11);
    public static final Item RED_ENVELOPE = add("red_envelope", new SouvenirItem(new Item.Settings(), SouvenirItem.Kind.ENVELOPE), 17);
    public static final Item MINI_CALENDAR = add("mini_calendar", new SouvenirItem(new Item.Settings(), SouvenirItem.Kind.CALENDAR), 19);
    public static final Item FIRECRACKER = add("firecracker_keychain", new SouvenirItem(new Item.Settings().rarity(Rarity.UNCOMMON), SouvenirItem.Kind.FIRECRACKER), 13);
    public static final Item CIDER = add("sparkling_cider", new SouvenirItem(new Item.Settings().rarity(Rarity.UNCOMMON), SouvenirItem.Kind.CIDER), 13);
    public static final Item GOLD_BADGE = add("golden_badge", new SouvenirItem(new Item.Settings().rarity(Rarity.EPIC)
            .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true), SouvenirItem.Kind.BADGE), 4);

    private Souvenirs() {}

    private static Identifier id(String path) {
        return Identifier.of(NewYearCountdown.MOD_ID, path);
    }

    private static Item add(String path, Item item, int weight) {
        Registry.register(Registries.ITEM, id(path), item);
        ALL.add(new Entry(item, weight));
        totalWeight += weight;
        return item;
    }

    public static Item pick(Random r) {
        int roll = r.nextInt(totalWeight);
        for (Entry e : ALL) {
            roll -= e.weight();
            if (roll < 0) return e.item();
        }
        return ALL.get(0).item();
    }

    /** 안경을 쓰고 웅크리면 머리 위로 파티 불꽃이 튄다. */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 8 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!p.isSneaking() || p.isSpectator() || !p.getEquippedStack(EquipmentSlot.HEAD).isOf(GLASSES)) continue;
                ServerWorld sw = (ServerWorld) p.getWorld();
                sw.spawnParticles(ParticleTypes.FIREWORK, p.getX(), p.getEyeY() + 0.35, p.getZ(), 3, 0.35, 0.15, 0.35, 0.05);
                sw.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getEyeY() + 0.1, p.getZ(), 1, 0.4, 0.2, 0.4, 0.02);
                if (server.getTicks() % 40 == 0) {
                    sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.5f, 1.4f);
                }
            }
        });
    }
}
