package dev.newyear.countdown.omikuji;

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

public final class OmikujiBlocks {
    private static BlockBehaviour.Properties settings() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.DARK_OAK_PLANKS)
                .noOcclusion()
                .strength(2.0f, 6.0f)
                .noLootTable()
                .pushReaction(PushReaction.IMMOVEABLE);
    }

    public static final Block OMIKUJI_BOX = ModReg.block("omikuji_box", OmikujiBlock::new, settings());
    public static final Block OMIKUJI_PART = ModReg.block("omikuji_part", OmikujiPartBlock::new, settings());
    public static final BlockEntityType<OmikujiBlockEntity> OMIKUJI_BE = ModReg.blockEntity("omikuji_box", OmikujiBlockEntity::new, OMIKUJI_BOX);
    public static final Item OMIKUJI_ITEM = ModReg.item("omikuji_box", p -> new OmikujiItem(OMIKUJI_BOX, p), new Item.Properties());

    private OmikujiBlocks() {}

    public static void init() {
        OmikujiPackets.register();
        OmikujiAdmin.register();
    }
}
