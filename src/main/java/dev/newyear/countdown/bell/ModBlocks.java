package dev.newyear.countdown.bell;

import dev.newyear.countdown.ModReg;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

public final class ModBlocks {
    private static BlockBehaviour.Properties settings() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BRICKS)
                .noOcclusion()
                .strength(3.0f, 12.0f)
                .noLootTable()                       // 아이템은 구조물이 무너질 때 한 번만 BellLayout 이 떨어뜨린다
                .pushReaction(PushReaction.IMMOVEABLE);
    }

    /** 구조물의 마스터 (대종 바로 아래 칸, 블록 엔티티 보유). */
    public static final Block BOSINGAK_BELL = ModReg.block("bosingak_bell", BosingakBellBlock::new, settings());

    /** 구조물의 부속 칸들 (보이지 않음). */
    public static final Block BOSINGAK_PART = ModReg.block("bosingak_part", BosingakPartBlock::new, settings());

    public static final BlockEntityType<BosingakBellBlockEntity> BELL_BE = ModReg.blockEntity("bosingak_bell", BosingakBellBlockEntity::new, BOSINGAK_BELL);

    public static final Item BOSINGAK_BELL_ITEM = ModReg.item("bosingak_bell", p -> new BosingakBellItem(BOSINGAK_BELL, p), new Item.Properties());

    private ModBlocks() {}

    public static void init() {
        BellRegistry.init();
    }
}
