package dev.newyear.countdown.wish;

import dev.newyear.countdown.ModReg;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

public final class WishEntities {
    public static final EntityType<WishLanternEntity> LANTERN = ModReg.entity("wish_lantern", EntityType.Builder.<WishLanternEntity>of(WishLanternEntity::new, MobCategory.MISC)
                    .sized(0.9f, 1.5f)
                    .clientTrackingRange(16)
                    .updateInterval(1)   // 매 틱 위치를 보내 부드럽게 보이게 한다 (2틱마다면 뚝뚝 끊겨 보임)
                    .fireImmune());

    public static final Item WISH_LANTERN_ITEM = ModReg.item("wish_lantern", WishLanternItem::new, new Item.Properties().stacksTo(16));

    private WishEntities() {}

    public static void init() {
        WishPackets.register();
        LanternDispenser.init();
    }
}
