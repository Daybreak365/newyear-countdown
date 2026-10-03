package dev.newyear.countdown.gacha;

import dev.newyear.countdown.CountdownState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 새해 기념품. 종류(Kind)마다 우클릭 상호작용이 다르다.
 * - 복주머니: 흔들면 덕담, 웅크리고 흔들면 행운 번호
 * - 양 인형: 꾹 눌러 소리, 양/생물에게 쓰면 쓰다듬기
 * - 미니 종: 연달아 치면 음이 올라가는 콤보
 * - 세뱃돈 봉투: 열어 보면 금액이 나온다
 * - 미니 달력: 새해까지 D-day
 * - 폭죽 키링: 꾹 눌러 심지에 불을 붙이고, 다 타면 팡!
 * - 사이다: 꾹 눌러 흔들수록 세게 뻥! (불도 끈다)
 * - 황금 배지: 반짝 연출
 * 모델 쪽 상태 전환(흔들림/열림/불꽃 등)은 클라이언트 ItemAnim 이 쿨다운·사용 상태를 보고 고른다.
 */
public class SouvenirItem extends Item {
    public enum Kind { PLAIN, POUCH, PLUSH, BELL, ENVELOPE, CALENDAR, FIRECRACKER, CIDER, BADGE }

    /** 폭죽 심지가 타는 시간 / 사이다를 최대로 흔드는 시간(틱). */
    public static final int FUSE_TICKS = 24;
    public static final int SHAKE_TICKS = 50;

    private static final float[] BELL_PITCH = {0.9f, 1.0f, 1.12f, 1.26f, 1.5f, 1.68f, 2.0f};
    private static final int[] ENVELOPE_AMOUNT = {10000, 10000, 10000, 30000, 30000, 50000, 50000, 100000, 100000, 1000000};
    private static final Map<UUID, long[]> BELL_COMBO = new ConcurrentHashMap<>(); // [마지막 틱, 콤보]

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

    // ------------------------------------------------------------------ 우클릭

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        switch (kind) {
            case FIRECRACKER, CIDER -> {
                user.setCurrentHand(hand);
                return TypedActionResult.consume(stack);
            }
            case PLAIN -> {
                return TypedActionResult.pass(stack);
            }
            default -> {
                if (world instanceof ServerWorld sw && user instanceof ServerPlayerEntity sp) {
                    switch (kind) {
                        case POUCH -> shakePouch(sw, sp);
                        case PLUSH -> squeak(sw, user.getX(), user.getEyeY() - 0.3, user.getZ(), 1.0f);
                        case BELL -> ringBell(sw, sp);
                        case ENVELOPE -> openEnvelope(sw, sp);
                        case CALENDAR -> showCalendar(sw, sp);
                        case BADGE -> shineBadge(sw, sp);
                        default -> { }
                    }
                }
                user.getItemCooldownManager().set(this, cooldownTicks());
                return TypedActionResult.success(stack, world.isClient);
            }
        }
    }

    /** 쿨다운 동안 클라이언트가 아이템 모델을 "사용 후" 모양으로 바꿔 보여준다. */
    private int cooldownTicks() {
        return switch (kind) {
            case POUCH -> 28;
            case PLUSH -> 14;
            case BELL -> 10;
            case ENVELOPE -> 26;
            case CALENDAR -> 24;
            case BADGE -> 30;
            default -> 20;
        };
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (kind != Kind.PLUSH) return ActionResult.PASS;
        if (user.getWorld() instanceof ServerWorld sw) {
            // 쓰다듬기: 대상이 양이면 메~ 하고 대답한다
            if (entity instanceof SheepEntity) {
                sw.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_SHEEP_AMBIENT,
                        SoundCategory.NEUTRAL, 1.0f, 1.0f + sw.random.nextFloat() * 0.2f);
            }
            squeak(sw, entity.getX(), entity.getY() + entity.getHeight() * 0.8, entity.getZ(), 1.4f);
            sw.spawnParticles(ParticleTypes.HEART, entity.getX(), entity.getY() + entity.getHeight() + 0.2, entity.getZ(), 5, 0.3, 0.2, 0.3, 0.02);
        }
        user.getItemCooldownManager().set(this, cooldownTicks());
        return ActionResult.success(user.getWorld().isClient);
    }

    // ------------------------------------------------------------------ 꾹 누르기 (폭죽·사이다)

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.NONE;
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        return switch (kind) {
            case FIRECRACKER -> FUSE_TICKS;
            case CIDER -> SHAKE_TICKS;
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
            if (used >= FUSE_TICKS - 8 && used % 2 == 0) { // 다 타 들어갈수록 더 요란하게
                sw.spawnParticles(ParticleTypes.LAVA, tip.x, tip.y, tip.z, 1, 0.04, 0.04, 0.04, 0.0);
            }
        } else if (kind == Kind.CIDER) {
            float power = Math.min(1f, used / (float) SHAKE_TICKS);
            if (used % 4 == 0) {
                sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ITEM_BOTTLE_EMPTY,
                        SoundCategory.PLAYERS, 0.5f, 1.2f + power * 0.8f);
            }
            if (used % 2 == 0) {
                Vec3d top = eye.add(look.multiply(0.6)).add(0, -0.15, 0);
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
                sp.getItemCooldownManager().set(this, 40);
            } else if (kind == Kind.CIDER) {
                popCider(sw, sp, 1.0f);
                sp.getItemCooldownManager().set(this, 44);
            }
        }
        return stack;
    }

    @Override
    public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
        if (!(world instanceof ServerWorld sw) || !(user instanceof ServerPlayerEntity sp)) return;
        int used = getMaxUseTime(stack, user) - remainingUseTicks;
        if (kind == Kind.FIRECRACKER && used > 2) {
            // 다 타기 전에 손을 떼면 불씨만 꺼진다
            sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 1.4f);
            Vec3d tip = sp.getEyePos().add(sp.getRotationVec(1.0f).multiply(0.65)).add(0, -0.3, 0);
            sw.spawnParticles(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 5, 0.05, 0.05, 0.05, 0.01);
        } else if (kind == Kind.CIDER && used >= 8) {
            popCider(sw, sp, Math.min(1f, used / (float) SHAKE_TICKS));
            sp.getItemCooldownManager().set(this, 44);
        }
    }

    // ------------------------------------------------------------------ 개별 연출

    private static void shakePouch(ServerWorld sw, ServerPlayerEntity p) {
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 0.9f, 1.6f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.5f + sw.random.nextFloat() * 0.4f);
        sw.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.GOLD_NUGGET)),
                p.getX(), p.getEyeY() - 0.2, p.getZ(), 10, 0.35, 0.25, 0.35, 0.12);
        sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getEyeY(), p.getZ(), 4, 0.4, 0.3, 0.4, 0.0);
        if (p.isSneaking()) {
            // 웅크리고 흔들면 행운 번호 (1~45 중 6개)
            List<Integer> nums = new ArrayList<>();
            for (int i = 1; i <= 45; i++) nums.add(i);
            Collections.shuffle(nums, new java.util.Random(sw.random.nextLong()));
            List<Integer> pick = new ArrayList<>(nums.subList(0, 6));
            Collections.sort(pick);
            StringBuilder sb = new StringBuilder();
            for (int n : pick) sb.append(String.format("%02d ", n));
            p.sendMessage(Text.translatable("item.newyearcountdown.lucky_pouch.lotto", sb.toString().trim()).formatted(Formatting.GOLD), true);
        } else {
            int i = sw.random.nextInt(6);
            p.sendMessage(Text.translatable("item.newyearcountdown.lucky_pouch.blessing." + i).formatted(Formatting.GOLD), true);
        }
    }

    private static void squeak(ServerWorld sw, double x, double y, double z, float pitchMul) {
        sw.playSound(null, x, y, z, SoundEvents.ENTITY_SHEEP_AMBIENT, SoundCategory.PLAYERS, 0.7f, (1.7f + sw.random.nextFloat() * 0.3f) * pitchMul);
        sw.spawnParticles(ParticleTypes.HEART, x, y + 0.4, z, 3, 0.25, 0.15, 0.25, 0.01);
    }

    private void ringBell(ServerWorld sw, ServerPlayerEntity p) {
        long now = sw.getTime();
        long[] st = BELL_COMBO.computeIfAbsent(p.getUuid(), k -> new long[]{Long.MIN_VALUE / 2, 0});
        st[1] = now - st[0] <= 30 ? st[1] + 1 : 0;      // 빠르게 이어 칠수록 콤보
        st[0] = now;
        int combo = (int) st[1];
        float pitch = BELL_PITCH[Math.min(combo, BELL_PITCH.length - 1)];
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.9f, pitch);
        // 음표 파티클 (NOTE 의 x 속도가 색)
        sw.spawnParticles(ParticleTypes.NOTE, p.getX(), p.getEyeY() + 0.2, p.getZ(), 1, 0.3, 0.2, 0.3, combo / 24.0);
        if (combo >= 6) {
            st[1] = 0;
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.9f, 1.0f);
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.5f);
            sw.spawnParticles(ParticleTypes.FIREWORK, p.getX(), p.getEyeY(), p.getZ(), 24, 0.6, 0.5, 0.6, 0.08);
            sw.spawnParticles(ParticleTypes.NOTE, p.getX(), p.getEyeY() + 0.3, p.getZ(), 8, 0.6, 0.4, 0.6, 0.5);
            p.sendMessage(Text.translatable("item.newyearcountdown.mini_bell.combo").formatted(Formatting.YELLOW), true);
        } else if (combo >= 2) {
            p.sendMessage(Text.translatable("item.newyearcountdown.mini_bell.count", combo + 1).formatted(Formatting.GOLD), true);
        }
    }

    private static void openEnvelope(ServerWorld sw, ServerPlayerEntity p) {
        int amount = ENVELOPE_AMOUNT[sw.random.nextInt(ENVELOPE_AMOUNT.length)];
        boolean big = amount >= 100000;
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8f, 1.5f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, big ? 1.8f : 1.2f);
        sw.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.GOLD_NUGGET)),
                p.getX(), p.getEyeY() - 0.1, p.getZ(), big ? 24 : 8, 0.3, 0.3, 0.3, 0.12);
        if (big) {
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
            sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY(), p.getZ(), 20, 0.5, 0.5, 0.5, 0.25);
        }
        p.sendMessage(Text.translatable("item.newyearcountdown.red_envelope.amount", String.format("%,d", amount))
                .formatted(big ? Formatting.GOLD : Formatting.YELLOW), true);
    }

    private static void showCalendar(ServerWorld sw, ServerPlayerEntity p) {
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.9f, 1.0f);
        long left = CountdownState.targetMs() - System.currentTimeMillis();
        if (left <= 0) {
            p.sendMessage(Text.translatable("item.newyearcountdown.mini_calendar.done").formatted(Formatting.GOLD), true);
            return;
        }
        long totalMin = left / 60000L;
        long days = totalMin / (60 * 24);
        long hours = (totalMin / 60) % 24;
        long mins = totalMin % 60;
        p.sendMessage(Text.translatable("item.newyearcountdown.mini_calendar.dday", days, hours, mins).formatted(Formatting.AQUA), true);
    }

    private static void shineBadge(ServerWorld sw, ServerPlayerEntity p) {
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.8f);
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.6f, 1.3f);
        sw.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getEyeY(), p.getZ(), 18, 0.5, 0.4, 0.5, 0.04);
        sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getEyeY() - 0.1, p.getZ(), 10, 0.4, 0.4, 0.4, 0.15);
        p.sendMessage(Text.translatable("item.newyearcountdown.golden_badge.shine").formatted(Formatting.GOLD), true);
    }

    private static void bang(ServerWorld sw, ServerPlayerEntity p) {
        Vec3d at = p.getEyePos().add(p.getRotationVec(1.0f).multiply(0.9)).add(0, -0.3, 0);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.0f, 1.0f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 0.8f, 1.4f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.8f, 1.0f);
        sw.spawnParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 30, 0.35, 0.35, 0.35, 0.12);
        sw.spawnParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.02);
        // 색종이
        for (int rgb : CapsuleItem.RGB) {
            Vector3f col = new Vector3f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f);
            sw.spawnParticles(new DustParticleEffect(col, 1.2f), at.x, at.y, at.z, 6, 0.5, 0.5, 0.5, 0.15);
        }
    }

    private static void popCider(ServerWorld sw, ServerPlayerEntity p, float power) {
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d mouth = eye.add(look.multiply(0.7)).add(0, -0.2, 0);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 0.9f, 0.7f + (1f - power) * 0.4f);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.BLOCK_BEEHIVE_EXIT, SoundCategory.PLAYERS, 1.0f, 1.6f);
        sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.7f);
        // 거품 분수: 바라보는 방향으로 뿜는다. 세게 흔들수록 멀리, 많이
        float reach = 2.0f + 4.5f * power;
        int steps = (int) (reach * 2);
        boolean extinguished = false;
        for (int i = 0; i < steps; i++) {
            double t = 0.5 + i * 0.5;
            Vec3d at = mouth.add(look.multiply(t)).add(0, -0.04 * t * t * (1.3 - power), 0);
            double spread = 0.05 + t * 0.045;
            sw.spawnParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 3, spread, spread, spread, 0.02);
            sw.spawnParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 1, spread, spread, spread, 0.01);
            BlockPos bp = BlockPos.ofFloored(at);
            if (sw.getBlockState(bp).isOf(net.minecraft.block.Blocks.FIRE)) { // 불을 꺼 준다
                sw.removeBlock(bp, false);
                extinguished = true;
            }
        }
        if (extinguished) {
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
        sw.spawnParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 6 + (int) (power * 10), 0.15, 0.15, 0.15, 0.06);
        p.sendMessage(Text.translatable(power >= 0.99f ? "item.newyearcountdown.sparkling_cider.max" : "item.newyearcountdown.sparkling_cider.pop")
                .formatted(Formatting.AQUA), true);
    }
}
