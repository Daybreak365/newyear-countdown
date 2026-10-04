package dev.newyear.countdown.gacha;

import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** 설날 떡국: 든든한 음식(재생·흡수 포함). 먹으면 "한 살 더" + 경험치, 그릇이 돌아온다. */
public class TteokgukItem extends Item {
    public TteokgukItem(Properties settings) {
        super(settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, tooltip, type);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level world, LivingEntity user) {
        ItemStack result = super.finishUsingItem(stack, world, user);   // 음식 효과 + 1개 소모
        if (!world.isClientSide() && user instanceof ServerPlayer sp) {
            sp.giveExperiencePoints(25);
            sp.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 60 * 3, 0));
            sp.getInventory().placeItemBackInInventory(new ItemStack(Items.BOWL), net.minecraft.util.Prediction.SERVER_ONLY);
            sp.sendOverlayMessage(Component.translatable("item.newyearcountdown.tteokguk.eaten").withStyle(ChatFormatting.GOLD));
        }
        return result;
    }
}
