package dev.newyear.countdown;

import dev.newyear.countdown.bell.ModBlocks;
import dev.newyear.countdown.gacha.CapsuleItem;
import dev.newyear.countdown.gacha.GachaBlocks;
import dev.newyear.countdown.gacha.Souvenirs;
import dev.newyear.countdown.omikuji.OmikujiBlocks;
import dev.newyear.countdown.wish.WishEntities;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;

/** 이 모드의 모든 블록·아이템을 모은 전용 크리에이티브 탭. */
public final class ModItemGroup {
    public static final ResourceKey<CreativeModeTab> KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "main"));

    private ModItemGroup() {}

    public static void init() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, FabricCreativeModeTab.builder()
                .icon(() -> CapsuleItem.of(0))
                .title(Component.translatable("itemGroup.newyearcountdown"))
                .displayItems((ctx, e) -> {
                    e.accept(ModBlocks.BOSINGAK_BELL_ITEM);
                    e.accept(OmikujiBlocks.OMIKUJI_ITEM);
                    e.accept(WishEntities.WISH_LANTERN_ITEM);
                    e.accept(GachaBlocks.GACHA_ITEM);
                    for (int i = 0; i < CapsuleItem.RGB.length; i++) e.accept(CapsuleItem.of(i));
                    for (Souvenirs.Entry s : Souvenirs.ALL) e.accept(s.item());
                })
                .build());
    }
}
