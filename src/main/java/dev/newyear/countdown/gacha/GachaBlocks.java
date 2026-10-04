package dev.newyear.countdown.gacha;

import dev.newyear.countdown.ModReg;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class GachaBlocks {
    public static final Block GACHA_MACHINE = ModReg.block("gacha_machine", GachaMachineBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.0f, 6.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(s -> 7)
                    .pushReaction(PushReaction.IMMOVEABLE));

    public static final BlockEntityType<GachaMachineBlockEntity> GACHA_BE = ModReg.blockEntity("gacha_machine", GachaMachineBlockEntity::new, GACHA_MACHINE);

    public static final Item GACHA_ITEM = ModReg.item("gacha_machine", p -> new GachaMachineItem(GACHA_MACHINE, p), new Item.Properties());

    public static final Item CAPSULE = ModReg.item("capsule", CapsuleItem::new, new Item.Properties().stacksTo(64));

    private GachaBlocks() {}

    public static void init() {
        Souvenirs.init();
    }
}
