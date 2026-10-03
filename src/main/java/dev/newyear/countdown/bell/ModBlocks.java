package dev.newyear.countdown.bell;

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

public final class ModBlocks {
    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.copy(Blocks.STONE_BRICKS)
                .nonOpaque()
                .strength(3.0f, 12.0f)
                .dropsNothing()                       // 아이템은 구조물이 무너질 때 한 번만 BellLayout 이 떨어뜨린다
                .pistonBehavior(PistonBehavior.BLOCK);
    }

    /** 구조물의 마스터 (대종 바로 아래 칸, 블록 엔티티 보유). */
    public static final Block BOSINGAK_BELL = Registry.register(
            Registries.BLOCK, Identifier.of(NewYearCountdown.MOD_ID, "bosingak_bell"),
            new BosingakBellBlock(settings()));

    /** 구조물의 부속 칸들 (보이지 않음). */
    public static final Block BOSINGAK_PART = Registry.register(
            Registries.BLOCK, Identifier.of(NewYearCountdown.MOD_ID, "bosingak_part"),
            new BosingakPartBlock(settings()));

    public static final BlockEntityType<BosingakBellBlockEntity> BELL_BE = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, Identifier.of(NewYearCountdown.MOD_ID, "bosingak_bell"),
            BlockEntityType.Builder.create(BosingakBellBlockEntity::new, BOSINGAK_BELL).build(null));

    public static final Item BOSINGAK_BELL_ITEM = Registry.register(
            Registries.ITEM, Identifier.of(NewYearCountdown.MOD_ID, "bosingak_bell"),
            new BosingakBellItem(BOSINGAK_BELL, new Item.Settings()));

    private ModBlocks() {}

    public static void init() {
        BellRegistry.init();
    }
}
