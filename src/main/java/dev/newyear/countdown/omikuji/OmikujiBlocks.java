package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class OmikujiBlocks {
    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.copy(Blocks.DARK_OAK_PLANKS)
                .nonOpaque()
                .strength(2.0f, 6.0f)
                .dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK);
    }

    public static final Block OMIKUJI_BOX = Registry.register(Registries.BLOCK,
            Identifier.of(NewYearCountdown.MOD_ID, "omikuji_box"), new OmikujiBlock(settings()));
    public static final Block OMIKUJI_PART = Registry.register(Registries.BLOCK,
            Identifier.of(NewYearCountdown.MOD_ID, "omikuji_part"), new OmikujiPartBlock(settings()));
    public static final BlockEntityType<OmikujiBlockEntity> OMIKUJI_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(NewYearCountdown.MOD_ID, "omikuji_box"),
            BlockEntityType.Builder.create(OmikujiBlockEntity::new, OMIKUJI_BOX).build(null));
    public static final Item OMIKUJI_ITEM = Registry.register(Registries.ITEM,
            Identifier.of(NewYearCountdown.MOD_ID, "omikuji_box"), new OmikujiItem(OMIKUJI_BOX, new Item.Settings()));

    private OmikujiBlocks() {}

    public static void init() {
        OmikujiPackets.register();
    }
}
