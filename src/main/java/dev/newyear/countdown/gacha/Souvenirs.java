package dev.newyear.countdown.gacha;

import dev.newyear.countdown.ModReg;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.List;

/** 캡슐에서 나오는 새해 기념품 목록과 확률. */
public final class Souvenirs {
    public record Entry(Item item, int weight) {}

    public static final List<Entry> ALL = new ArrayList<>();
    private static int totalWeight;

    /** 2027 안경의 착용 텍스처 (assets/newyearcountdown/equipment/party_glasses.json). */
    public static final ResourceKey<EquipmentAsset> GLASSES_ASSET = ResourceKey.create(EquipmentAssets.ROOT_ID, ModReg.id("party_glasses"));

    /** 몸에 쓰는 2027 안경 (머리 칸에 장착, 방어력 없음). */
    public static final Item GLASSES = add("party_glasses", Item::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE)
            .component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.HEAD)
                    .setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC).setAsset(GLASSES_ASSET).build()), 12);
    public static final Item LUCKY_POUCH = add("lucky_pouch", p -> new SouvenirItem(p, SouvenirItem.Kind.POUCH), new Item.Properties(), 16);

    public static final Item RED_ENVELOPE = add("red_envelope", p -> new SouvenirItem(p, SouvenirItem.Kind.ENVELOPE), new Item.Properties(), 15);
    public static final Item MINI_CALENDAR = add("mini_calendar", p -> new SouvenirItem(p, SouvenirItem.Kind.CALENDAR), new Item.Properties().stacksTo(1), 15);
    public static final Item FIRECRACKER = add("firecracker_keychain", p -> new SouvenirItem(p, SouvenirItem.Kind.FIRECRACKER), new Item.Properties().rarity(Rarity.UNCOMMON), 12);
    public static final Item CHAMPAGNE = add("champagne", p -> new SouvenirItem(p, SouvenirItem.Kind.CHAMPAGNE), new Item.Properties().rarity(Rarity.UNCOMMON), 11);
    public static final Item GOLD_BADGE = add("golden_badge", p -> new SouvenirItem(p, SouvenirItem.Kind.BADGE), new Item.Properties().rarity(Rarity.EPIC).stacksTo(1)
            .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true), 4);

    public static final Item TTEOKGUK = add("tteokguk", TteokgukItem::new, new Item.Properties().stacksTo(16).usingConvertsTo(Items.BOWL).food(
            new FoodProperties.Builder().nutrition(10).saturationModifier(0.9f).alwaysEdible().build(),
            Consumables.defaultFood().onConsume(new ApplyStatusEffectsConsumeEffect(List.of(
                    new MobEffectInstance(MobEffects.REGENERATION, 20 * 8, 1),
                    new MobEffectInstance(MobEffects.ABSORPTION, 20 * 120, 1)))).build()), 14);
    public static final Item KITE = add("kite", p -> new SouvenirItem(p, SouvenirItem.Kind.KITE), new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON), 10);

    /** 윷 세트: 바닥에 까는 윷판(블록 + 블록 엔티티). */
    public static final Block YUT_BOARD_BLOCK = ModReg.block("yut_board", YutBoardBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.6f)
                    .sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.POPPED));
    public static final BlockEntityType<YutBoardBlockEntity> YUT_BE = ModReg.blockEntity("yut_board", YutBoardBlockEntity::new, YUT_BOARD_BLOCK);
    public static final Item YUT_SET = add("yut_set", p -> new YutBoardItem(YUT_BOARD_BLOCK, p), new Item.Properties(), 10);

    public static final Item GREETING_CARD = add("greeting_card", p -> new SouvenirItem(p, SouvenirItem.Kind.CARD), new Item.Properties().stacksTo(16), 13);

    private Souvenirs() {}

    private static Item add(String path, Function<Item.Properties, Item> factory, Item.Properties props, int weight) {
        Item item = ModReg.item(path, factory, props);
        ALL.add(new Entry(item, weight));
        totalWeight += weight;
        return item;
    }

    public static Item pick(RandomSource r) {
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
            if (server.getTickCount() % 8 != 0) return;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.isSpectator() || !p.getItemBySlot(EquipmentSlot.HEAD).is(GLASSES)) continue;
                if (server.getTickCount() % 40 == 0) {   // 쓰고 있는 동안 행운 I (표시 아이콘 없이 조용히 유지)
                    p.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 12, 0, true, false, true));
                }
                if (!p.isShiftKeyDown()) continue;
                ServerLevel sw = (ServerLevel) p.level();
                sw.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getEyeY() + 0.35, p.getZ(), 3, 0.35, 0.15, 0.35, 0.05);
                sw.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getEyeY() + 0.1, p.getZ(), 1, 0.4, 0.2, 0.4, 0.02);
                if (server.getTickCount() % 40 == 0) {
                    sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.5f, 1.4f);
                }
            }
        });
    }
}
