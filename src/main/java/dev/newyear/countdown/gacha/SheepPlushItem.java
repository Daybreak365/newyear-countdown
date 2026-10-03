package dev.newyear.countdown.gacha;

import net.minecraft.block.Block;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2027 양 인형 (설치 가능). 블록을 향해 우클릭하면 놓이고, 허공에서는 꾹 눌러 "꼬옥 안기"를 한다.
 * - 안기(1.5초): 재생 + 포만감 + 해로운 효과 해제. 2분마다 한 번.
 * - 다른 플레이어를 우클릭: 둘 다 잠깐 재생 (서로 안아 주기). 양에게 우클릭: 털이 다시 자란다.
 */
public class SheepPlushItem extends BlockItem {
    public static final int HUG_TICKS = 30;
    private static final long HUG_COOLDOWN = 20 * 120;
    private static final Map<UUID, Long> READY_AT = new ConcurrentHashMap<>();

    public SheepPlushItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public String getTranslationKey() {
        return "item.newyearcountdown.sheep_plush";
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.newyearcountdown.sheep_plush.desc").formatted(Formatting.GRAY));
        super.appendTooltip(stack, context, tooltip, type);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        long left = READY_AT.getOrDefault(user.getUuid(), 0L) - world.getTime();
        if (left > 0) {
            if (!world.isClient) {
                user.sendMessage(Text.translatable("item.newyearcountdown.sheep_plush.resting", (left + 19) / 20).formatted(Formatting.GRAY), true);
            }
            return TypedActionResult.fail(stack);
        }
        user.setCurrentHand(hand);
        return TypedActionResult.consume(stack);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.NONE;
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        return HUG_TICKS;
    }

    @Override
    public void usageTick(World world, LivingEntity user, ItemStack stack, int remaining) {
        if (!(world instanceof ServerWorld sw)) return;
        int used = HUG_TICKS - remaining;
        if (used % 6 == 0) {
            sw.spawnParticles(ParticleTypes.HEART, user.getX(), user.getEyeY() + 0.1, user.getZ(), 1, 0.3, 0.2, 0.3, 0.0);
            sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.PLAYERS, 0.6f, 0.8f + used * 0.02f);
        }
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (world instanceof ServerWorld sw && user instanceof ServerPlayerEntity sp) {
            READY_AT.put(sp.getUuid(), world.getTime() + HUG_COOLDOWN);
            for (StatusEffectInstance fx : new ArrayList<>(sp.getStatusEffects())) {   // 해로운 효과 해제
                if (fx.getEffectType().value().getCategory() == StatusEffectCategory.HARMFUL) sp.removeStatusEffect(fx.getEffectType());
            }
            sp.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 10, 1));
            sp.getHungerManager().add(4, 0.6f);
            sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ENTITY_SHEEP_AMBIENT, SoundCategory.PLAYERS, 0.8f, 1.9f);
            sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.4f, 1.8f);
            sw.spawnParticles(ParticleTypes.HEART, sp.getX(), sp.getEyeY(), sp.getZ(), 10, 0.5, 0.4, 0.5, 0.02);
            sp.sendMessage(Text.translatable("item.newyearcountdown.sheep_plush.hugged").formatted(Formatting.LIGHT_PURPLE), true);
        }
        return stack;
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (!(user.getWorld() instanceof ServerWorld sw)) return ActionResult.success(true);
        sw.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_SHEEP_AMBIENT, SoundCategory.NEUTRAL, 0.9f, 1.5f);
        sw.spawnParticles(ParticleTypes.HEART, entity.getX(), entity.getY() + entity.getHeight() + 0.2, entity.getZ(), 6, 0.3, 0.2, 0.3, 0.02);
        if (entity instanceof SheepEntity sheep && sheep.isSheared()) {
            sheep.setSheared(false);   // 쓰다듬어 주면 털이 다시 폭신해진다
            user.sendMessage(Text.translatable("item.newyearcountdown.sheep_plush.regrow"), true);
        } else if (entity instanceof ServerPlayerEntity other && user instanceof ServerPlayerEntity me) {
            long left = READY_AT.getOrDefault(me.getUuid(), 0L) - sw.getTime();
            if (left > 0) {
                me.sendMessage(Text.translatable("item.newyearcountdown.sheep_plush.resting", (left + 19) / 20).formatted(Formatting.GRAY), true);
            } else {
                READY_AT.put(me.getUuid(), sw.getTime() + HUG_COOLDOWN / 2);
                me.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 6, 1));
                other.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 6, 1));
                other.sendMessage(Text.translatable("item.newyearcountdown.sheep_plush.hugged_by", me.getDisplayName()).formatted(Formatting.LIGHT_PURPLE), true);
            }
        }
        return ActionResult.success(false);
    }
}
