package dev.newyear.countdown;

import dev.newyear.countdown.bell.ModBlocks;
import dev.newyear.countdown.gacha.CapsuleItem;
import dev.newyear.countdown.gacha.GachaBlocks;
import dev.newyear.countdown.gacha.Souvenirs;
import dev.newyear.countdown.omikuji.OmikujiBlocks;
import dev.newyear.countdown.wish.WishEntities;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** 이 모드의 모든 블록·아이템을 모은 전용 크리에이티브 탭. */
public final class ModItemGroup {
    public static final RegistryKey<ItemGroup> KEY =
            RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(NewYearCountdown.MOD_ID, "main"));

    private ModItemGroup() {}

    public static void init() {
        Registry.register(Registries.ITEM_GROUP, KEY, FabricItemGroup.builder()
                .icon(() -> CapsuleItem.of(0))
                .displayName(Text.translatable("itemGroup.newyearcountdown"))
                .entries((ctx, e) -> {
                    e.add(ModBlocks.BOSINGAK_BELL_ITEM);
                    e.add(OmikujiBlocks.OMIKUJI_ITEM);
                    e.add(WishEntities.WISH_LANTERN_ITEM);
                    e.add(GachaBlocks.GACHA_ITEM);
                    for (int i = 0; i < CapsuleItem.RGB.length; i++) e.add(CapsuleItem.of(i));
                    for (Souvenirs.Entry s : Souvenirs.ALL) e.add(s.item());
                })
                .build());
    }
}
