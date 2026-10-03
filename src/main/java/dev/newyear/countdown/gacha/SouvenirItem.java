package dev.newyear.countdown.gacha;

import dev.newyear.countdown.CountdownConfig;
import dev.newyear.countdown.CountdownState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.time.LocalDate;
import java.util.List;

/**
 * 새해 굿즈. 종류(Kind)마다 실제로 쓸모 있는 효과가 있다. (양 인형은 설치되는 블록 아이템이라 SheepPlushItem)
 *
 * - 복주머니(소모): 열면 전리품 + 행운 10분
 * - 보신각 종(30초 쿨다운): 근처 아군에게 재생·신속 축복, 몬스터는 겁먹고 둔해지며 표적을 놓친다
 * - 세뱃돈 봉투(소모): 열면 금액에 비례한 경험치
 * - 2027 미니 달력: 하루 한 번 출석 체크(연속 출석일 보상) + 새해까지 D-day
 * - 폭죽 키링(소모): 꾹 눌러 불을 붙이고 던지듯 터뜨리면 앞의 몬스터에게 피해·넉백·발광
 * - 새해 샴페인(소모): 꾹 흔들수록 세게, 쏴아아 거품이 앞으로 뿜어져(ChampagneSpray) 불을 끄고 몬스터를 밀어낸다
 * - 황금 배지(5분 쿨다운): 황금빛 축복 = 흡수 + 재생 + 저항
 * - 방패연(30초): 하늘이 열린 곳에서 연처럼 떠올라 천천히 내려온다
 * - 덕담 카드(소모): 다른 플레이어에게 우클릭하면 둘 다 행운 + 재생, 혼자 쓰면 행운
 * 모델 쪽 상태 전환(흔들림/열림/불꽃 등)은 클라이언트 ItemAnim 이 쿨다운·사용 상태를 보고 고른다.
 */
public class SouvenirItem extends Item {
    public enum Kind { PLAIN, POUCH, BELL, ENVELOPE, CALENDAR, FIRECRACKER, CHAMPAGNE, BADGE, KITE, CARD }

    /** 폭죽 심지가 타는 시간 / 샴페인을 최대로 흔드는 시간(틱). */
    public static final int FUSE_TICKS = 24;
    public static final int SHAKE_TICKS = 50;

    private static final int[] ENVELOPE_AMOUNT = {10000, 10000, 10000, 30000, 30000, 50000, 50000, 100000, 100000, 1000000};

    private final Kind kind;

    public SouvenirItem(Settings settings, Kind kind) {
        super(settings);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable(getTranslationKey() + ".desc").formatted(Formatting.GRAY));
        super.appendTooltip(stack, context, tooltip, type);
    }

    /** 사용 직후 애니메이션을 보여줄 쿨다운(틱). ItemAnim 이 경과 시간을 계산할 때도 쓴다. */
    public int cooldownTicks() {
        return switch (kind) {
            case POUCH -> 20;
            case BELL -> 600;
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
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        switch (kind) {
            case FIRECRACKER, CHAMPAGNE -> {
                user.setCurrentHand(hand);
                return TypedActionResult.consume(stack);
            }
            case PLAIN -> {
                return TypedActionResult.pass(stack);
            }
            default -> {
                int cd = cooldownTicks();
                if (world instanceof ServerWorld sw && user instanceof ServerPlayerEntity sp) {
                    switch (kind) {
                        case POUCH -> openPouch(sw, sp, stack);
                        case BELL -> ringBell(sw, sp);
                        case ENVELOPE -> openEnvelope(sw, sp, stack);
                        case CALENDAR -> checkIn(sw, sp, stack);
                        case BADGE -> blessing(sw, sp);
                        case KITE -> {
                            if (!flyKite(sw, sp)) return TypedActionResult.fail(stack);
                        }
                        case CARD -> sendCardToSelf(sw, sp, stack);
                        default -> { }
                    }
                } else if (kind == Kind.KITE && !world.isSkyVisible(user.getBlockPos().up())) {
                    return TypedActionResult.fail(stack);
                }
                user.getItemCooldownManager().set(this, cd);
                return TypedActionResult.success(stack, world.isClient);
            }
        }
    }

    /** 덕담 카드: 다른 플레이어에게 우클릭. */
    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (kind != Kind.CARD || !(entity instanceof PlayerEntity)) return ActionResult.PASS;
        if (user.getWorld() instanceof ServerWorld sw && user instanceof ServerPlayerEntity me && entity instanceof ServerPlayerEntity other) {
            for (ServerPlayerEntity p : new ServerPlayerEntity[]{me, other}) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 20 * 60 * 5, 0));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 10, 1));
                p.addExperience(15);
                sw.spawnParticles(ParticleTypes.HEART, p.getX(), p.getEyeY() + 0.2, p.getZ(), 8, 0.4, 0.3, 0.4, 0.02);
                sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY(), p.getZ(), 10, 0.4, 0.4, 0.4, 0.2);
            }
            sw.playSound(null, other.getX(), other.getY(), other.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.4f);
            other.sendMessage(Text.translatable("item.newyearcountdown.greeting_card.received", me.getDisplayName()).formatted(Formatting.GOLD), false);
            me.sendMessage(Text.translatable("item.newyearcountdown.greeting_card.sent", other.getDisplayName()).formatted(Formatting.GOLD), false);
            stack.decrementUnlessCreative(1, me);
            me.getItemCooldownManager().set(this, cooldownTicks());
        }
        return ActionResult.success(user.getWorld().isClient);
    }

    // ------------------------------------------------------------------ 꾹 누르기 (폭죽·샴페인)

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.NONE;
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        return switch (kind) {
            case FIRECRACKER -> FUSE_TICKS;
            case CHAMPAGNE -> SHAKE_TICKS;
            default -> 0;
        };
    }

    @Override
    public void usageTick(World world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
        if (!(world instanceof ServerWorld sw)) return;
        int used = getMaxUseTime(stack, user) - remainingUseTicks;
        Vec3d eye = user.getEyePos();
        Vec3d look = user.getRotationVec(1.0f);
        if (kind == Kind.FIRECRACKER) {
            Vec3d tip = eye.add(look.multiply(0.65)).add(0, -0.3, 0);
            if (used % 2 == 0) {
                sw.spawnParticles(ParticleTypes.SMALL_FLAME, tip.x, tip.y, tip.z, 1, 0.02, 0.02, 0.02, 0.0);
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z, 2, 0.05, 0.05, 0.05, 0.1);
            }
            if (used % 4 == 1) {
                sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH,
                        SoundCategory.PLAYERS, 0.35f, 1.6f + used * 0.03f);
            }
            if (used == 1) {
                sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ITEM_FLINTANDSTEEL_USE,
                        SoundCategory.PLAYERS, 0.8f, 1.2f);
            }
            if (used >= FUSE_TICKS - 8 && used % 2 == 0) {
                sw.spawnParticles(ParticleTypes.LAVA, tip.x, tip.y, tip.z, 1, 0.04, 0.04, 0.04, 0.0);
            }
        } else if (kind == Kind.CHAMPAGNE) {
            float power = Math.min(1f, used / (float) SHAKE_TICKS);
            if (used % 4 == 0) {
                sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ITEM_BOTTLE_EMPTY,
                        SoundCategory.PLAYERS, 0.5f, 1.2f + power * 0.8f);
            }
            if (used % 2 == 0) {
                Vec3d top = user instanceof ServerPlayerEntity sp ? ChampagneSpray.mouth(sp) : eye.add(look.multiply(0.6));
                sw.spawnParticles(ParticleTypes.BUBBLE_POP, top.x, top.y, top.z, 1 + (int) (power * 4), 0.12, 0.12, 0.12, 0.02);
                if (power > 0.5f) {
                    sw.spawnParticles(ParticleTypes.SPLASH, top.x, top.y + 0.1, top.z, 2, 0.1, 0.1, 0.1, 0.1);
                }
            }
        }
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (world instanceof ServerWorld sw && user instanceof ServerPlayerEntity sp) {
            if (kind == Kind.FIRECRACKER) {
                bang(sw, sp);
                stack.decrementUnlessCreative(1, sp);
                sp.getItemCooldownManager().set(this, 40);
            } else if (kind == Kind.CHAMPAGNE) {
                popChampagne(sw, sp, 1.0f);
                stack.decrementUnlessCreative(1, sp);
            }
        }
        return stack;
    }

    @Override
    public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
        if (!(world instanceof ServerWorld sw) || !(user instanceof ServerPlayerEntity sp)) return;
        int used = getMaxUseTime(stack, user) - remainingUseTicks;
        if (kind == Kind.FIRECRACKER && used > 2) {
            // 다 타기 전에 손을 떼면 불씨만 꺼진다 (폭죽은 아낀다)
            sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 1.4f);
            Vec3d tip = sp.getEyePos().add(sp.getRotationVec(1.0f).multiply(0.65)).add(0, -0.3, 0);
            sw.spawnParticles(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 5, 0.05, 0.05, 0.05, 0.01);
        } else if (kind == Kind.CHAMPAGNE && used >= 8) {
            popChampagne(sw, sp, Math.min(1f, used / (float) SHAKE_TICKS));
            stack.decrementUnlessCreative(1, sp);
        }
    }

    // ------------------------------------------------------------------ 개별 효과

    private static void openPouch(ServerWorld sw, ServerPlayerEntity p, ItemStack stack) {
        ItemStack loot = pouchLoot(sw.random);
        Text lootName = loot.getName();
        int lootCount = loot.getCount();   // offerOrDrop 이 스택을 비우므로 미리 읽어 둔다
        stack.decrementUnlessCreative(1, p);
        p.getInventory().offerOrDrop(loot);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 20 * 60 * 10, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 0.9f, 1.6f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.5f);
        sw.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.GOLD_NUGGET)),
                p.getX(), p.getEyeY() - 0.2, p.getZ(), 12, 0.35, 0.25, 0.35, 0.12);
        sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getEyeY(), p.getZ(), 6, 0.4, 0.3, 0.4, 0.0);
        p.sendMessage(Text.translatable("item.newyearcountdown.lucky_pouch.opened", lootCount, lootName).formatted(Formatting.GOLD), true);
    }

    private static ItemStack pouchLoot(Random r) {
        int roll = r.nextInt(100);
        if (roll < 30) return new ItemStack(Items.GOLD_NUGGET, 4 + r.nextInt(6));
        if (roll < 52) return new ItemStack(Items.EMERALD, 1 + r.nextInt(3));
        if (roll < 72) return new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + r.nextInt(4));
        if (roll < 86) return new ItemStack(Items.GOLDEN_CARROT, 3 + r.nextInt(3));
        if (roll < 95) return new ItemStack(Items.GOLDEN_APPLE, 1);
        return new ItemStack(Items.DIAMOND, 1 + r.nextInt(2));
    }

    /** 새해 종소리 축복: 반경 16 안의 플레이어(자신 포함)는 재생·신속, 반경 12 안의 몬스터는 겁먹는다. */
    private static void ringBell(ServerWorld sw, ServerPlayerEntity p) {
        float pitch = 0.9f + sw.random.nextFloat() * 0.3f;
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.4f, pitch);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 0.9f, pitch);
        sw.spawnParticles(ParticleTypes.NOTE, p.getX(), p.getEyeY() + 0.3, p.getZ(), 6, 0.8, 0.4, 0.8, 0.6);
        sw.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 0.2, p.getZ(), 24, 1.2, 0.1, 1.2, 0.03);
        int blessed = 0;
        for (ServerPlayerEntity o : sw.getPlayers(pl -> pl.squaredDistanceTo(p) <= 16 * 16)) {
            o.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 8, 1));
            o.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 30, 0));
            blessed++;
        }
        int scared = 0;
        Box box = p.getBoundingBox().expand(12);
        for (HostileEntity h : sw.getEntitiesByClass(HostileEntity.class, box, e -> true)) {
            h.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 8, 2));
            h.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 8, 0));
            h.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 8, 0));
            ((MobEntity) h).setTarget(null);
            scared++;
        }
        p.sendMessage(Text.translatable("item.newyearcountdown.mini_bell.rung", blessed, scared).formatted(Formatting.GOLD), true);
    }

    private static void openEnvelope(ServerWorld sw, ServerPlayerEntity p, ItemStack stack) {
        int amount = ENVELOPE_AMOUNT[sw.random.nextInt(ENVELOPE_AMOUNT.length)];
        boolean big = amount >= 100000;
        int xp = switch (amount) {
            case 10000 -> 15;
            case 30000 -> 40;
            case 50000 -> 80;
            case 100000 -> 160;
            default -> 600;
        };
        stack.decrementUnlessCreative(1, p);
        p.addExperience(xp);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8f, 1.5f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, big ? 1.8f : 1.2f);
        sw.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.GOLD_NUGGET)),
                p.getX(), p.getEyeY() - 0.1, p.getZ(), big ? 24 : 8, 0.3, 0.3, 0.3, 0.12);
        if (big) {
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
            sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY(), p.getZ(), 20, 0.5, 0.5, 0.5, 0.25);
        }
        p.sendMessage(Text.translatable("item.newyearcountdown.red_envelope.amount", String.format("%,d", amount), xp)
                .formatted(big ? Formatting.GOLD : Formatting.YELLOW), true);
    }

    /** 하루 한 번 출석 체크. 마지막 출석일/연속 일수는 이 달력 아이템의 데이터에 저장된다. */
    private static void checkIn(ServerWorld sw, ServerPlayerEntity p, ItemStack stack) {
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.9f, 1.0f);
        long left = CountdownState.targetMs() - System.currentTimeMillis();
        Text dday;
        if (left <= 0) {
            dday = Text.translatable("item.newyearcountdown.mini_calendar.done");
        } else {
            long totalMin = left / 60000L;
            dday = Text.translatable("item.newyearcountdown.mini_calendar.dday", totalMin / (60 * 24), (totalMin / 60) % 24, totalMin % 60);
        }
        long today = LocalDate.now(CountdownConfig.get().zoneId()).toEpochDay();
        NbtComponent c = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound n = c == null ? new NbtCompound() : c.copyNbt();
        long last = n.contains("day") ? n.getLong("day") : Long.MIN_VALUE / 2;
        int streak = n.getInt("streak");
        if (last == today) {
            p.sendMessage(Text.translatable("item.newyearcountdown.mini_calendar.already", streak).append(Text.literal("  ")).append(dday).formatted(Formatting.AQUA), true);
            return;
        }
        streak = last == today - 1 ? streak + 1 : 1;
        n.putLong("day", today);
        n.putInt("streak", streak);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(n));
        int xp = 20 + 10 * Math.min(streak, 10);
        p.addExperience(xp);
        boolean weekly = streak % 7 == 0;
        if (weekly) {   // 7일 연속 출석: 행운 + 황금 사과
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 20 * 60 * 10, 0));
            p.getInventory().offerOrDrop(new ItemStack(Items.GOLDEN_APPLE));
        }
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), weekly ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_PLAYER_LEVELUP,
                SoundCategory.PLAYERS, 0.7f, weekly ? 1.0f : 1.5f);
        sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getEyeY(), p.getZ(), 10, 0.5, 0.4, 0.5, 0.0);
        p.sendMessage(Text.translatable(weekly ? "item.newyearcountdown.mini_calendar.weekly" : "item.newyearcountdown.mini_calendar.checkin", streak, xp)
                .append(Text.literal("  ")).append(dday).formatted(Formatting.GREEN), true);
    }

    /** 황금빛 축복: 흡수 + 재생 + 저항. */
    private static void blessing(ServerWorld sw, ServerPlayerEntity p) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 20 * 120, 2));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 10, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.8f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.6f, 1.3f);
        sw.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getEyeY(), p.getZ(), 24, 0.5, 0.6, 0.5, 0.05);
        sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY() - 0.1, p.getZ(), 16, 0.4, 0.5, 0.4, 0.2);
        p.sendMessage(Text.translatable("item.newyearcountdown.golden_badge.shine").formatted(Formatting.GOLD), true);
    }

    /** 방패연: 하늘이 열린 곳에서만. 위로 솟구쳤다가 천천히 내려온다(낙하 피해 없음). */
    private static boolean flyKite(ServerWorld sw, ServerPlayerEntity p) {
        if (!sw.isSkyVisible(p.getBlockPos().up())) {
            p.sendMessage(Text.translatable("item.newyearcountdown.kite.no_sky").formatted(Formatting.RED), true);
            return false;
        }
        Vec3d look = p.getRotationVec(1.0f);
        p.addVelocity(look.x * 0.5, 1.05, look.z * 0.5);
        p.velocityModified = true;
        p.fallDistance = 0;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 25, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 0.7f, 1.4f);
        sw.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 16, 0.5, 0.1, 0.5, 0.05);
        sw.spawnParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 0.5, p.getZ(), 10, 0.4, 0.3, 0.4, 0.06);
        p.sendMessage(Text.translatable("item.newyearcountdown.kite.fly").formatted(Formatting.AQUA), true);
        return true;
    }

    /** 혼자 쓰는 덕담 카드: 행운 2분 + 약간의 경험치. 소모. */
    private static void sendCardToSelf(ServerWorld sw, ServerPlayerEntity p, ItemStack stack) {
        stack.decrementUnlessCreative(1, p);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 20 * 120, 0));
        p.addExperience(10);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.9f, 1.2f);
        sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getEyeY(), p.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
        p.sendMessage(Text.translatable("item.newyearcountdown.greeting_card.self").formatted(Formatting.GOLD), true);
    }

    /** 폭죽: 앞쪽 3칸 지점에서 터져 반경 4 안의 몬스터에게 피해·넉백·발광. */
    private static void bang(ServerWorld sw, ServerPlayerEntity p) {
        Vec3d at = p.getEyePos().add(p.getRotationVec(1.0f).multiply(3.0));
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.2f, 1.0f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.0f, 1.4f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.8f, 1.0f);
        sw.spawnParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 40, 0.5, 0.5, 0.5, 0.15);
        sw.spawnParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 10, 0.3, 0.3, 0.3, 0.02);
        for (int rgb : CapsuleItem.RGB) {
            Vector3f col = new Vector3f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f);
            sw.spawnParticles(new DustParticleEffect(col, 1.2f), at.x, at.y, at.z, 6, 0.7, 0.7, 0.7, 0.15);
        }
        int hit = 0;
        for (HostileEntity h : sw.getEntitiesByClass(HostileEntity.class, new Box(at, at).expand(4.0), e -> true)) {
            h.damage(sw.getDamageSources().playerAttack(p), 8.0f);
            h.takeKnockback(0.9, p.getX() - h.getX(), p.getZ() - h.getZ());
            h.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 6, 0));
            hit++;
        }
        if (hit > 0) p.sendMessage(Text.translatable("item.newyearcountdown.firecracker_keychain.hit", hit).formatted(Formatting.GOLD), true);
    }

    private void popChampagne(ServerWorld sw, ServerPlayerEntity p, float power) {
        Vec3d mouth = ChampagneSpray.mouth(p);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.1f, 0.7f + (1f - power) * 0.4f);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.BLOCK_BEEHIVE_EXIT, SoundCategory.PLAYERS, 1.0f, 1.6f);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.7f);
        sw.spawnParticles(ParticleTypes.FLASH, mouth.x, mouth.y, mouth.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 10, 0.15, 0.15, 0.15, 0.08);
        ChampagneSpray.start(p, power);
        p.getItemCooldownManager().set(this, ChampagneSpray.durationFor(power) + 10);
        p.sendMessage(Text.translatable(power >= 0.99f ? "item.newyearcountdown.champagne.max" : "item.newyearcountdown.champagne.pop")
                .formatted(Formatting.AQUA), true);
    }
}
