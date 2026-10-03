package dev.newyear.countdown.gacha;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.List;

/** 2027 캡슐토이: 우클릭을 꾹 눌러 까면 랜덤 기념품이 나온다. 색은 NBT("c")로 구분한다. */
public class CapsuleItem extends Item {
    public static final int[] RGB = {0xE8453C, 0xF5A623, 0xF8D347, 0x5CC96B, 0x4DA8E8, 0xB06AE8};
    public static final int OPEN_TICKS = 24;

    public CapsuleItem(Settings settings) {
        super(settings);
    }

    public static ItemStack of(int color) {
        ItemStack s = new ItemStack(GachaBlocks.CAPSULE);
        NbtCompound n = new NbtCompound();
        n.putInt("c", Math.floorMod(color, RGB.length));
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(n));
        return s;
    }

    public static int colorIndex(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c == null ? 0 : Math.floorMod(c.copyNbt().getInt("c"), RGB.length);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.newyearcountdown.capsule.desc").formatted(Formatting.GRAY));
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        user.setCurrentHand(hand);
        return TypedActionResult.consume(user.getStackInHand(hand));
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.NONE;
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        return OPEN_TICKS;
    }

    @Override
    public void usageTick(World world, LivingEntity user, ItemStack stack, int remaining) {
        if (!(world instanceof ServerWorld sw)) return;
        int used = OPEN_TICKS - remaining;
        if (used % 6 == 1) {
            sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENTITY_TURTLE_EGG_CRACK,
                    SoundCategory.PLAYERS, 0.7f, 1.2f + used * 0.02f);
        }
        if (used % 3 == 0) {
            int rgb = RGB[colorIndex(stack)];
            Vector3f col = new Vector3f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f);
            sw.spawnParticles(new DustParticleEffect(col, 0.9f), user.getX(), user.getEyeY() - 0.35, user.getZ(), 3, 0.25, 0.15, 0.25, 0.01);
        }
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (world.isClient || !(user instanceof ServerPlayerEntity sp) || !(world instanceof ServerWorld sw)) return stack;

        Item prize = Souvenirs.pick(world.random);
        ItemStack out = new ItemStack(prize);
        stack.decrementUnlessCreative(1, sp);
        sp.getInventory().offerOrDrop(out);

        boolean jackpot = prize == Souvenirs.GOLD_BADGE;
        sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.1f);
        sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), jackpot ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_PLAYER_LEVELUP,
                SoundCategory.PLAYERS, 0.7f, jackpot ? 1.0f : 1.4f);
        sw.spawnParticles(ParticleTypes.END_ROD, sp.getX(), sp.getEyeY() - 0.2, sp.getZ(), jackpot ? 40 : 14, 0.4, 0.4, 0.4, 0.05);
        if (jackpot) {
            sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getEyeY(), sp.getZ(), 40, 0.5, 0.6, 0.5, 0.3);
            sw.getServer().getPlayerManager().broadcast(
                    Text.translatable("gacha.newyearcountdown.jackpot", sp.getDisplayName(), out.toHoverableText()).formatted(Formatting.GOLD), false);
        }
        sp.sendMessage(Text.translatable("gacha.newyearcountdown.got", out.toHoverableText()), true);
        return stack;
    }
}
