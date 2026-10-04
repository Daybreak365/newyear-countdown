package dev.newyear.countdown.gacha;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;
import dev.newyear.countdown.CountdownConfig;
import dev.newyear.countdown.CountdownState;

import java.time.LocalDate;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 새해 굿즈. 종류(Kind)마다 효과가 다르다.
 *
 * - 복주머니(소모): 열면 전리품 + 행운 10분
 * - 세뱃돈 봉투(소모): 열면 금액에 비례한 경험치
 * - 2027 미니 달력: 하루 한 번 출석 체크(연속 출석일 보상) + 새해까지 D-day
 * - 폭죽 키링(소모): 꾹 눌러 불을 붙이고 던지듯 터뜨리면 앞의 몬스터에게 피해·넉백·발광
 * - 새해 샴페인(소모): 흔든 만큼 세게 거품을 분사(ChampagneSpray). 불을 끄고 몬스터를 밀어낸다
 * - 황금 배지(5분 쿨다운): 황금빛 축복 = 흡수 + 재생 + 저항
 * - 방패연(30초): 하늘이 열린 곳에서 연처럼 떠올라 천천히 내려온다
 * - 덕담 카드(소모): 다른 플레이어에게 우클릭하면 둘 다 행운 + 재생, 혼자 쓰면 행운
 * 모델 쪽 상태 전환(흔들림/열림/불꽃 등)은 클라이언트 ItemAnim 이 쿨다운·사용 상태를 보고 고른다.
 */
public class SouvenirItem extends Item {
    public enum Kind { PLAIN, POUCH, ENVELOPE, CALENDAR, FIRECRACKER, CHAMPAGNE, BADGE, KITE, CARD }

    /** 폭죽 심지가 타는 시간 / 샴페인을 최대로 흔드는 시간(틱). */
    public static final int FUSE_TICKS = 24;
    public static final int SHAKE_TICKS = 50;

    private static final int[] ENVELOPE_AMOUNT = {10000, 10000, 10000, 30000, 30000, 50000, 50000, 100000, 100000, 1000000};

    private final Kind kind;

    public SouvenirItem(Properties settings, Kind kind) {
        super(settings);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, tooltip, type);
    }

    /** 사용 직후 애니메이션을 보여줄 쿨다운(틱). ItemAnim 이 경과 시간을 계산할 때도 쓴다. */
    public int cooldownTicks() {
        return switch (kind) {
            case POUCH -> 20;
            case ENVELOPE -> 26;
            case CALENDAR -> 24;
            case BADGE -> 6000;
            case KITE -> 600;
            case CARD -> 24;
            case FIRECRACKER -> 40;
            default -> 20;
        };
    }

    // ------------------------------------------------------------------ 우클릭

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack stack = user.getItemInHand(hand);
        switch (kind) {
            case FIRECRACKER, CHAMPAGNE -> {
                user.startUsingItem(hand);
                return InteractionResult.CONSUME;
            }
            case PLAIN -> {
                return InteractionResult.PASS;
            }
            default -> {
                int cd = cooldownTicks();
                if (world instanceof ServerLevel sw && user instanceof ServerPlayer sp) {
                    switch (kind) {
                        case POUCH -> openPouch(sw, sp, stack);
                        case ENVELOPE -> openEnvelope(sw, sp, stack);
                        case CALENDAR -> checkIn(sw, sp, stack);
                        case BADGE -> blessing(sw, sp);
                        case KITE -> {
                            if (!flyKite(sw, sp)) return InteractionResult.FAIL;
                        }
                        case CARD -> sendCardToSelf(sw, sp, stack);
                        default -> { }
                    }
                } else if (kind == Kind.KITE && !world.canSeeSky(user.blockPosition().above())) {
                    return InteractionResult.FAIL;
                }
                user.getCooldowns().addCooldown(getDefaultInstance(), cd);
                return InteractionResult.SUCCESS;
            }
        }
    }

    /** 덕담 카드: 다른 플레이어에게 우클릭. */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player user, LivingEntity entity, InteractionHand hand) {
        if (kind != Kind.CARD || !(entity instanceof Player)) return InteractionResult.PASS;
        if (user.level() instanceof ServerLevel sw && user instanceof ServerPlayer me && entity instanceof ServerPlayer other) {
            for (ServerPlayer p : new ServerPlayer[]{me, other}) {
                p.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 60 * 5, 0));
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 10, 1));
                p.giveExperiencePoints(15);
                sw.sendParticles(ParticleTypes.HEART, p.getX(), p.getEyeY() + 0.2, p.getZ(), 8, 0.4, 0.3, 0.4, 0.02);
                sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY(), p.getZ(), 10, 0.4, 0.4, 0.4, 0.2);
            }
            sw.playSound(null, other.getX(), other.getY(), other.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.4f);
            other.sendSystemMessage(Component.translatable("item.newyearcountdown.greeting_card.received", me.getDisplayName()).withStyle(ChatFormatting.GOLD));
            me.sendSystemMessage(Component.translatable("item.newyearcountdown.greeting_card.sent", other.getDisplayName()).withStyle(ChatFormatting.GOLD));
            stack.consume(1, me);
            me.getCooldowns().addCooldown(getDefaultInstance(), cooldownTicks());
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 꾹 누르기 (폭죽·샴페인)

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return switch (kind) {
            case FIRECRACKER -> FUSE_TICKS;
            case CHAMPAGNE -> SHAKE_TICKS;
            default -> 0;
        };
    }

    @Override
    public void onUseTick(Level world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
        if (!(world instanceof ServerLevel sw)) return;
        int used = getUseDuration(stack, user) - remainingUseTicks;
        Vec3 eye = user.getEyePosition();
        Vec3 look = user.getViewVector(1.0f);
        if (kind == Kind.FIRECRACKER) {
            Vec3 tip = eye.add(look.scale(0.65)).add(0, -0.3, 0);
            if (used % 2 == 0) {
                sw.sendParticles(ParticleTypes.SMALL_FLAME, tip.x, tip.y, tip.z, 1, 0.02, 0.02, 0.02, 0.0);
                sw.sendParticles(ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z, 2, 0.05, 0.05, 0.05, 0.1);
            }
            if (used % 4 == 1) {
                sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.FIRE_EXTINGUISH,
                        SoundSource.PLAYERS, 0.35f, 1.6f + used * 0.03f);
            }
            if (used == 1) {
                sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.FLINTANDSTEEL_USE,
                        SoundSource.PLAYERS, 0.8f, 1.2f);
            }
            if (used >= FUSE_TICKS - 8 && used % 2 == 0) {
                sw.sendParticles(ParticleTypes.LAVA, tip.x, tip.y, tip.z, 1, 0.04, 0.04, 0.04, 0.0);
            }
        } else if (kind == Kind.CHAMPAGNE) {
            float power = Math.min(1f, used / (float) SHAKE_TICKS);
            if (used % 4 == 0) {
                sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.BOTTLE_EMPTY,
                        SoundSource.PLAYERS, 0.5f, 1.2f + power * 0.8f);
            }
            if (used % 2 == 0) {
                Vec3 top = user instanceof ServerPlayer sp ? ChampagneSpray.mouth(sp) : eye.add(look.scale(0.6));
                sw.sendParticles(ParticleTypes.BUBBLE_POP, top.x, top.y, top.z, 1 + (int) (power * 4), 0.12, 0.12, 0.12, 0.02);
                if (power > 0.5f) {
                    sw.sendParticles(ParticleTypes.SPLASH, top.x, top.y + 0.1, top.z, 2, 0.1, 0.1, 0.1, 0.1);
                }
            }
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level world, LivingEntity user) {
        if (world instanceof ServerLevel sw && user instanceof ServerPlayer sp) {
            if (kind == Kind.FIRECRACKER) {
                bang(sw, sp);
                stack.consume(1, sp);
                sp.getCooldowns().addCooldown(getDefaultInstance(), 40);
            } else if (kind == Kind.CHAMPAGNE) {
                popChampagne(sw, sp, 1.0f);
                stack.consume(1, sp);
            }
        }
        return stack;
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level world, LivingEntity user, int remainingUseTicks) {
        if (!(world instanceof ServerLevel sw) || !(user instanceof ServerPlayer sp)) return false;
        int used = getUseDuration(stack, user) - remainingUseTicks;
        if (kind == Kind.FIRECRACKER && used > 2) {
            // 다 타기 전에 손을 떼면 불씨만 꺼진다 (폭죽은 아낀다)
            sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6f, 1.4f);
            Vec3 tip = sp.getEyePosition().add(sp.getViewVector(1.0f).scale(0.65)).add(0, -0.3, 0);
            sw.sendParticles(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 5, 0.05, 0.05, 0.05, 0.01);
        } else if (kind == Kind.CHAMPAGNE && used >= 8) {
            popChampagne(sw, sp, Math.min(1f, used / (float) SHAKE_TICKS));
            stack.consume(1, sp);
        }
        return false;
    }

    // ------------------------------------------------------------------ 개별 효과

    private static void openPouch(ServerLevel sw, ServerPlayer p, ItemStack stack) {
        ItemStack loot = pouchLoot(sw.getRandom());
        Component lootName = loot.getHoverName();
        int lootCount = loot.getCount();   // offerOrDrop 이 스택을 비우므로 미리 읽어 둔다
        stack.consume(1, p);
        p.getInventory().placeItemBackInInventory(loot, net.minecraft.util.Prediction.SERVER_ONLY);
        p.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 60 * 10, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 0.9f, 1.6f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.5f);
        sw.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.GOLD_NUGGET),
                p.getX(), p.getEyeY() - 0.2, p.getZ(), 12, 0.35, 0.25, 0.35, 0.12);
        sw.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getEyeY(), p.getZ(), 6, 0.4, 0.3, 0.4, 0.0);
        p.sendOverlayMessage(Component.translatable("item.newyearcountdown.lucky_pouch.opened", lootCount, lootName).withStyle(ChatFormatting.GOLD));
    }

    private static ItemStack pouchLoot(RandomSource r) {
        int roll = r.nextInt(100);
        if (roll < 30) return new ItemStack(Items.GOLD_NUGGET, 4 + r.nextInt(6));
        if (roll < 52) return new ItemStack(Items.EMERALD, 1 + r.nextInt(3));
        if (roll < 72) return new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + r.nextInt(4));
        if (roll < 86) return new ItemStack(Items.GOLDEN_CARROT, 3 + r.nextInt(3));
        if (roll < 95) return new ItemStack(Items.GOLDEN_APPLE, 1);
        return new ItemStack(Items.DIAMOND, 1 + r.nextInt(2));
    }

    private static void openEnvelope(ServerLevel sw, ServerPlayer p, ItemStack stack) {
        int amount = ENVELOPE_AMOUNT[sw.getRandom().nextInt(ENVELOPE_AMOUNT.length)];
        boolean big = amount >= 100000;
        int xp = switch (amount) {
            case 10000 -> 15;
            case 30000 -> 40;
            case 50000 -> 80;
            case 100000 -> 160;
            default -> 600;
        };
        stack.consume(1, p);
        p.giveExperiencePoints(xp);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8f, 1.5f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, big ? 1.8f : 1.2f);
        sw.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.GOLD_NUGGET),
                p.getX(), p.getEyeY() - 0.1, p.getZ(), big ? 24 : 8, 0.3, 0.3, 0.3, 0.12);
        if (big) {
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
            sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY(), p.getZ(), 20, 0.5, 0.5, 0.5, 0.25);
        }
        p.sendOverlayMessage(Component.translatable("item.newyearcountdown.red_envelope.amount", String.format("%,d", amount), xp)
                .withStyle(big ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
    }

    /** 하루 한 번 출석 체크. 마지막 출석일/연속 일수는 이 달력 아이템의 데이터에 저장된다. */
    private static void checkIn(ServerLevel sw, ServerPlayer p, ItemStack stack) {
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.9f, 1.0f);
        long left = CountdownState.targetMs() - System.currentTimeMillis();
        Component dday;
        if (left <= 0) {
            dday = Component.translatable("item.newyearcountdown.mini_calendar.done");
        } else {
            long totalMin = left / 60000L;
            dday = Component.translatable("item.newyearcountdown.mini_calendar.dday", totalMin / (60 * 24), (totalMin / 60) % 24, totalMin % 60);
        }
        long today = LocalDate.now(CountdownConfig.get().zoneId()).toEpochDay();
        CustomData c = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag n = c == null ? new CompoundTag() : c.copyTag();
        long last = n.getLongOr("day", Long.MIN_VALUE / 2);
        int streak = n.getIntOr("streak", 0);
        if (last == today) {
            p.sendOverlayMessage(Component.translatable("item.newyearcountdown.mini_calendar.already", streak).append(Component.literal("  ")).append(dday).withStyle(ChatFormatting.AQUA));
            return;
        }
        streak = last == today - 1 ? streak + 1 : 1;
        n.putLong("day", today);
        n.putInt("streak", streak);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(n));
        int xp = 20 + 10 * Math.min(streak, 10);
        p.giveExperiencePoints(xp);
        boolean weekly = streak % 7 == 0;
        if (weekly) {   // 7일 연속 출석: 행운 + 황금 사과
            p.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 60 * 10, 0));
            p.getInventory().placeItemBackInInventory(new ItemStack(Items.GOLDEN_APPLE), net.minecraft.util.Prediction.SERVER_ONLY);
        }
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), weekly ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP,
                SoundSource.PLAYERS, 0.7f, weekly ? 1.0f : 1.5f);
        sw.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getEyeY(), p.getZ(), 10, 0.5, 0.4, 0.5, 0.0);
        p.sendOverlayMessage(Component.translatable(weekly ? "item.newyearcountdown.mini_calendar.weekly" : "item.newyearcountdown.mini_calendar.checkin", streak, xp)
                .append(Component.literal("  ")).append(dday).withStyle(ChatFormatting.GREEN));
    }

    /** 황금빛 축복: 흡수 + 재생 + 저항. */
    private static void blessing(ServerLevel sw, ServerPlayer p) {
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 20 * 120, 2));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 10, 1));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 60, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.8f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.6f, 1.3f);
        sw.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getEyeY(), p.getZ(), 24, 0.5, 0.6, 0.5, 0.05);
        sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY() - 0.1, p.getZ(), 16, 0.4, 0.5, 0.4, 0.2);
        p.sendOverlayMessage(Component.translatable("item.newyearcountdown.golden_badge.shine").withStyle(ChatFormatting.GOLD));
    }

    /** 방패연: 하늘이 열린 곳에서만. 위로 솟구쳤다가 천천히 내려온다(낙하 피해 없음). */
    private static boolean flyKite(ServerLevel sw, ServerPlayer p) {
        if (!sw.canSeeSky(p.blockPosition().above())) {
            p.sendOverlayMessage(Component.translatable("item.newyearcountdown.kite.no_sky").withStyle(ChatFormatting.RED));
            return false;
        }
        Vec3 look = p.getViewVector(1.0f);
        p.push(look.x * 0.5, 1.05, look.z * 0.5);
        p.needsSync = true;
        p.fallDistance = 0;
        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 25, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 0.7f, 1.4f);
        sw.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 16, 0.5, 0.1, 0.5, 0.05);
        sw.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 0.5, p.getZ(), 10, 0.4, 0.3, 0.4, 0.06);
        p.sendOverlayMessage(Component.translatable("item.newyearcountdown.kite.fly").withStyle(ChatFormatting.AQUA));
        return true;
    }

    /** 혼자 쓰는 덕담 카드: 행운 2분 + 약간의 경험치. 소모. */
    private static void sendCardToSelf(ServerLevel sw, ServerPlayer p, ItemStack stack) {
        stack.consume(1, p);
        p.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 120, 0));
        p.giveExperiencePoints(10);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.9f, 1.2f);
        sw.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getEyeY(), p.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
        p.sendOverlayMessage(Component.translatable("item.newyearcountdown.greeting_card.self").withStyle(ChatFormatting.GOLD));
    }

    /** 폭죽: 앞쪽 3칸 지점에서 터져 반경 4 안의 몬스터에게 피해·넉백·발광. */
    private static void bang(ServerLevel sw, ServerPlayer p) {
        Vec3 at = p.getEyePosition().add(p.getViewVector(1.0f).scale(3.0));
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.2f, 1.0f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0f, 1.4f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.8f, 1.0f);
        sw.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 40, 0.5, 0.5, 0.5, 0.15);
        sw.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, -1), at.x, at.y, at.z, 1, 0, 0, 0, 0);
        sw.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 10, 0.3, 0.3, 0.3, 0.02);
        for (int rgb : CapsuleItem.RGB) {
            sw.sendParticles(new DustParticleOptions(rgb, 1.2f), at.x, at.y, at.z, 6, 0.7, 0.7, 0.7, 0.15);
        }
        int hit = 0;
        for (Monster h : sw.getEntitiesOfClass(Monster.class, new AABB(at, at).inflate(4.0), e -> true)) {
            h.hurt(sw.damageSources().playerAttack(p), 8.0f);
            h.knockback(0.9, p.getX() - h.getX(), p.getZ() - h.getZ(), sw.damageSources().playerAttack(p), 8.0f);
            h.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 6, 0));
            hit++;
        }
        if (hit > 0) p.sendOverlayMessage(Component.translatable("item.newyearcountdown.firecracker_keychain.hit", hit).withStyle(ChatFormatting.GOLD));
    }

    private void popChampagne(ServerLevel sw, ServerPlayer p, float power) {
        Vec3 mouth = ChampagneSpray.mouth(p);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.1f, 0.7f + (1f - power) * 0.4f);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.BEEHIVE_EXIT, SoundSource.PLAYERS, 1.0f, 1.6f);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.7f);
        sw.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, -1), mouth.x, mouth.y, mouth.z, 1, 0, 0, 0, 0);
        sw.sendParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 10, 0.15, 0.15, 0.15, 0.08);
        ChampagneSpray.start(p, power);
        p.getCooldowns().addCooldown(getDefaultInstance(), ChampagneSpray.durationFor(power) + 10);
        p.sendOverlayMessage(Component.translatable(power >= 0.99f ? "item.newyearcountdown.champagne.max" : "item.newyearcountdown.champagne.pop")
                .withStyle(ChatFormatting.AQUA));
    }
}
