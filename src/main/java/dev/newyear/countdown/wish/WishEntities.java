package dev.newyear.countdown.wish;

import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class WishEntities {
    public static final EntityType<WishLanternEntity> LANTERN = Registry.register(Registries.ENTITY_TYPE,
            Identifier.of(NewYearCountdown.MOD_ID, "wish_lantern"),
            EntityType.Builder.<WishLanternEntity>create(WishLanternEntity::new, SpawnGroup.MISC)
                    .dimensions(0.9f, 1.5f)
                    .maxTrackingRange(16)
                    .trackingTickInterval(1)   // 매 틱 위치를 보내 부드럽게 보이게 한다 (2틱마다면 뚝뚝 끊겨 보임)
                    .makeFireImmune()
                    .build("wish_lantern"));

    public static final Item WISH_LANTERN_ITEM = Registry.register(Registries.ITEM,
            Identifier.of(NewYearCountdown.MOD_ID, "wish_lantern"),
            new WishLanternItem(new Item.Settings().maxCount(16)));

    private WishEntities() {}

    public static void init() {
        WishPackets.register();
        LanternDispenser.init();
    }
}
