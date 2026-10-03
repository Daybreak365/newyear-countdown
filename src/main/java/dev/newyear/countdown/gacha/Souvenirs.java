package dev.newyear.countdown.gacha;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.sound.BlockSoundGroup;
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
            new ArmorItem(GLASSES_MATERIAL, ArmorItem.Type.HELMET, new Item.Settings().maxCount(1).rarity(Rarity.RARE)), 12);
    public static final Item LUCKY_POUCH = add("lucky_pouch", new SouvenirItem(new Item.Settings(), SouvenirItem.Kind.POUCH), 16);

    /** 설치할 수 있는 양 인형 (블록 + 블록 아이템). */
    public static final Block SHEEP_PLUSH_BLOCK = Registry.register(Registries.BLOCK, id("sheep_plush"),
            new SheepPlushBlock(AbstractBlock.Settings.create().mapColor(MapColor.WHITE).strength(0.5f)
                    .sounds(BlockSoundGroup.WOOL).nonOpaque().pistonBehavior(PistonBehavior.DESTROY)));
    public static final Item SHEEP_PLUSH = add("sheep_plush", new SheepPlushItem(SHEEP_PLUSH_BLOCK, new Item.Settings()), 13);

    public static final Item MINI_BELL = add("mini_bell", new SouvenirItem(new Item.Settings().rarity(Rarity.UNCOMMON), SouvenirItem.Kind.BELL), 11);
    public static final Item RED_ENVELOPE = add("red_envelope", new SouvenirItem(new Item.Settings(), SouvenirItem.Kind.ENVELOPE), 15);
    public static final Item MINI_CALENDAR = add("mini_calendar", new SouvenirItem(new Item.Settings().maxCount(1), SouvenirItem.Kind.CALENDAR), 15);
    public static final Item FIRECRACKER = add("firecracker_keychain", new SouvenirItem(new Item.Settings().rarity(Rarity.UNCOMMON), SouvenirItem.Kind.FIRECRACKER), 12);
    public static final Item CHAMPAGNE = add("champagne", new SouvenirItem(new Item.Settings().rarity(Rarity.UNCOMMON), SouvenirItem.Kind.CHAMPAGNE), 11);
    public static final Item GOLD_BADGE = add("golden_badge", new SouvenirItem(new Item.Settings().rarity(Rarity.EPIC).maxCount(1)
            .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true), SouvenirItem.Kind.BADGE), 4);

    public static final Item TTEOKGUK = add("tteokguk", new TteokgukItem(new Item.Settings().maxCount(16).food(
            new FoodComponent.Builder().nutrition(10).saturationModifier(0.9f).alwaysEdible()
                    .statusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 8, 1), 1.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 20 * 120, 1), 1.0f)
                    .build())), 14);
    public static final Item KITE = add("kite", new SouvenirItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), SouvenirItem.Kind.KITE), 10);

    /** 윷 세트: 바닥에 까는 윷판(블록 + 블록 엔티티). */
    public static final Block YUT_BOARD_BLOCK = Registry.register(Registries.BLOCK, id("yut_board"),
            new YutBoardBlock(AbstractBlock.Settings.create().mapColor(MapColor.OAK_TAN).strength(0.6f)
                    .sounds(BlockSoundGroup.WOOD).nonOpaque().pistonBehavior(PistonBehavior.DESTROY)));
    public static final BlockEntityType<YutBoardBlockEntity> YUT_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, id("yut_board"),
            BlockEntityType.Builder.create(YutBoardBlockEntity::new, YUT_BOARD_BLOCK).build(null));
    public static final Item YUT_SET = add("yut_set", new YutBoardItem(YUT_BOARD_BLOCK, new Item.Settings()), 10);

    public static final Item GREETING_CARD = add("greeting_card", new SouvenirItem(new Item.Settings().maxCount(16), SouvenirItem.Kind.CARD), 13);

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
        ChampagneSpray.init();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 8 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p.isSpectator() || !p.getEquippedStack(EquipmentSlot.HEAD).isOf(GLASSES)) continue;
                if (server.getTicks() % 40 == 0) {   // 쓰고 있는 동안 행운 I (표시 아이콘 없이 조용히 유지)
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 20 * 12, 0, true, false, true));
                }
                if (!p.isSneaking()) continue;
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
