package dev.newyear.countdown.gacha;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 2027 캡슐토이: 우클릭을 꾹 눌러 까면 랜덤 기념품이 나온다. 색은 NBT("c")로 구분한다. */
public class CapsuleItem extends Item {
    public static final int[] RGB = {0xE8453C, 0xF5A623, 0xF8D347, 0x5CC96B, 0x4DA8E8, 0xB06AE8};
    public static final int OPEN_TICKS = 24;

    public CapsuleItem(Properties settings) {
        super(settings);
    }

    public static ItemStack of(int color) {
        ItemStack s = new ItemStack(GachaBlocks.CAPSULE);
        CompoundTag n = new CompoundTag();
        n.putInt("c", Math.floorMod(color, RGB.length));
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(n));
        return s;
    }

    public static int colorIndex(ItemStack s) {
        CustomData c = s.get(DataComponents.CUSTOM_DATA);
        return c == null ? 0 : Math.floorMod(c.copyTag().getIntOr("c", 0), RGB.length);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable("item.newyearcountdown.capsule.desc").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        user.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return OPEN_TICKS;
    }

    @Override
    public void onUseTick(Level world, LivingEntity user, ItemStack stack, int remaining) {
        if (!(world instanceof ServerLevel sw)) return;
        int used = OPEN_TICKS - remaining;
        int rgb = RGB[colorIndex(stack)];
        float t = used / (float) OPEN_TICKS;
        // 1단계(~0.8): 달그락 흔들림, 점점 빨라지고 음이 올라간다 / 2단계(0.8~): 금이 가며 빛이 샌다
        if (used % 4 == 1 && t < 0.8f) {
            sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.TURTLE_EGG_CRACK,
                    SoundSource.PLAYERS, 0.6f, 1.1f + t * 0.9f);
        }
        if (used == (int) (OPEN_TICKS * 0.8f)) {
            sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.STONE_BUTTON_CLICK_ON,
                    SoundSource.PLAYERS, 0.8f, 1.6f);
            sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.PLAYERS, 0.8f, 0.8f);
        }
        Vec3 at = user.getEyePosition().add(user.getViewVector(1.0f).scale(0.55)).add(0, -0.3, 0);
        if (used % 3 == 0) {
            sw.sendParticles(new DustParticleOptions(rgb, 0.9f), at.x, at.y, at.z, 2 + (int) (t * 4), 0.18, 0.12, 0.18, 0.01);
        }
        if (t >= 0.8f && used % 2 == 0) {
            sw.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 2, 0.12, 0.12, 0.12, 0.03);
        }
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level world, LivingEntity user, int remainingUseTicks) {
        if (world instanceof ServerLevel sw && OPEN_TICKS - remainingUseTicks > 2) {
            sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.COMPARATOR_CLICK,
                    SoundSource.PLAYERS, 0.5f, 0.8f);
        }
        return false;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level world, LivingEntity user) {
        if (world.isClientSide() || !(user instanceof ServerPlayer sp) || !(world instanceof ServerLevel sw)) return stack;

        Item prize = Souvenirs.pick(world.getRandom());
        ItemStack out = new ItemStack(prize);
        Component outText = out.getDisplayName();   // offerOrDrop 이 스택을 비우므로 미리 만들어 둔다
        stack.consume(1, sp);
        sp.getInventory().placeItemBackInInventory(out, net.minecraft.util.Prediction.SERVER_ONLY);

        boolean jackpot = prize == Souvenirs.GOLD_BADGE;
        sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.1f);
        sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), jackpot ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP,
                SoundSource.PLAYERS, 0.7f, jackpot ? 1.0f : 1.4f);
        sw.sendParticles(ParticleTypes.END_ROD, sp.getX(), sp.getEyeY() - 0.2, sp.getZ(), jackpot ? 40 : 14, 0.4, 0.4, 0.4, 0.05);
        sw.sendParticles(ParticleTypes.FIREWORK, sp.getX(), sp.getEyeY() - 0.1, sp.getZ(), 16, 0.35, 0.3, 0.35, 0.1);
        for (int rgb : RGB) {
            sw.sendParticles(new DustParticleOptions(rgb, 1.1f), sp.getX(), sp.getEyeY() - 0.1, sp.getZ(), 3, 0.4, 0.4, 0.4, 0.12);
        }
        if (jackpot) {
            sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getEyeY(), sp.getZ(), 40, 0.5, 0.6, 0.5, 0.3);
            sw.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("gacha.newyearcountdown.jackpot", sp.getDisplayName(), outText).withStyle(ChatFormatting.GOLD), false);
        }
        sp.sendOverlayMessage(Component.translatable("gacha.newyearcountdown.got", outText));
        return stack;
    }
}
