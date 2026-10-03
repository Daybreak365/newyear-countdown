package dev.newyear.countdown.gacha;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.util.List;

/** 설날 떡국: 든든한 음식(재생·흡수 포함). 먹으면 "한 살 더" + 경험치, 그릇이 돌아온다. */
public class TteokgukItem extends Item {
    public TteokgukItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable(getTranslationKey() + ".desc").formatted(Formatting.GRAY));
        super.appendTooltip(stack, context, tooltip, type);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        ItemStack result = super.finishUsing(stack, world, user);   // 음식 효과 + 1개 소모
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            sp.addExperience(25);
            sp.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 20 * 60 * 3, 0));
            sp.getInventory().offerOrDrop(new ItemStack(Items.BOWL));
            sp.sendMessage(Text.translatable("item.newyearcountdown.tteokguk.eaten").formatted(Formatting.GOLD), true);
        }
        return result;
    }
}
