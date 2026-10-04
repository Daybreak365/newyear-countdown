package dev.newyear.countdown.curling;

import dev.newyear.countdown.ModReg;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** 컬링: 스톤(빨강·노랑), 브룸, 하우스(과녁). 얼음 위에서 즐긴다. */
public final class Curling {
    public static final EntityType<CurlingStoneEntity> STONE = ModReg.entity("curling_stone",
            EntityType.Builder.<CurlingStoneEntity>of(CurlingStoneEntity::new, MobCategory.MISC)
                    .sized(0.6f, 0.25f).clientTrackingRange(10).updateInterval(1));

    public static final Item STONE_RED = ModReg.item("curling_stone_red", p -> new CurlingStoneItem(p, 0), new Item.Properties().stacksTo(8));
    public static final Item STONE_YELLOW = ModReg.item("curling_stone_yellow", p -> new CurlingStoneItem(p, 1), new Item.Properties().stacksTo(8));
    public static final Item BROOM = ModReg.item("curling_broom", CurlingBroomItem::new, new Item.Properties().stacksTo(1));

    public static final Block HOUSE = ModReg.block("curling_house", CurlingHouseBlock::new, BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLUE).strength(0.2f).sound(SoundType.WOOL).noCollision().noOcclusion().pushReaction(PushReaction.POPPED));
    public static final BlockEntityType<CurlingHouseBlockEntity> HOUSE_BE = ModReg.blockEntity("curling_house", CurlingHouseBlockEntity::new, HOUSE);
    public static final Item HOUSE_ITEM = ModReg.item("curling_house", p -> new BlockItem(HOUSE, p), new Item.Properties());

    private Curling() {}

    public static void init() {}
}
