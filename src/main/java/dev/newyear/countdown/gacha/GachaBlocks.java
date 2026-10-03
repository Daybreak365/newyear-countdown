package dev.newyear.countdown.gacha;

import dev.newyear.countdown.NewYearCountdown;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

public final class GachaBlocks {
    public static final Block GACHA_MACHINE = Registry.register(Registries.BLOCK,
            Identifier.of(NewYearCountdown.MOD_ID, "gacha_machine"),
            new GachaMachineBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.RED)
                    .strength(2.0f, 6.0f)
                    .sounds(BlockSoundGroup.METAL)
                    .nonOpaque()
                    .luminance(s -> 7)
                    .pistonBehavior(PistonBehavior.BLOCK)));

    public static final BlockEntityType<GachaMachineBlockEntity> GACHA_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(NewYearCountdown.MOD_ID, "gacha_machine"),
            BlockEntityType.Builder.create(GachaMachineBlockEntity::new, GACHA_MACHINE).build(null));

    public static final Item GACHA_ITEM = Registry.register(Registries.ITEM,
            Identifier.of(NewYearCountdown.MOD_ID, "gacha_machine"), new BlockItem(GACHA_MACHINE, new Item.Settings()));

    public static final Item CAPSULE = Registry.register(Registries.ITEM,
            Identifier.of(NewYearCountdown.MOD_ID, "capsule"), new CapsuleItem(new Item.Settings().maxCount(64)));

    private GachaBlocks() {}

    public static void init() {
        Souvenirs.init();
    }
}
