package dev.newyear.countdown.gacha;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/** 새해 기념품: 설명 한 줄이 붙고, 일부는 우클릭하면 작은 연출(팡!)이 나온다. */
public class SouvenirItem extends Item {
    private final boolean pops;

    public SouvenirItem(Settings settings, boolean pops) {
        super(settings);
        this.pops = pops;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable(getTranslationKey() + ".desc").formatted(Formatting.GRAY));
        super.appendTooltip(stack, context, tooltip, type);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!pops) return TypedActionResult.pass(stack);
        if (!world.isClient && world instanceof ServerWorld sw) {
            boolean bell = getTranslationKey().contains("bell");
            sw.playSound(null, user.getX(), user.getY(), user.getZ(),
                    bell ? SoundEvents.BLOCK_BELL_USE : SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,
                    SoundCategory.PLAYERS, bell ? 0.8f : 0.6f, bell ? 1.6f : 1.2f + world.random.nextFloat() * 0.3f);
            sw.spawnParticles(ParticleTypes.FIREWORK, user.getX(), user.getEyeY(), user.getZ(), 18, 0.5, 0.4, 0.5, 0.06);
        }
        user.getItemCooldownManager().set(this, 30);
        return TypedActionResult.success(stack, world.isClient);
    }
}
