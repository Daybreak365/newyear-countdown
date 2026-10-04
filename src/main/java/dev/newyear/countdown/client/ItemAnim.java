package dev.newyear.countdown.client;

import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.gacha.CapsuleItem;
import dev.newyear.countdown.gacha.SouvenirItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.ItemOwner;
import org.jspecify.annotations.Nullable;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 아이템 모델 상태 전환. 아이템 모델 JSON 의 overrides 가 아래 predicate 값을 보고 "흔들림 / 열림 / 불꽃" 모델로 바꾼다.
 * 서버와 따로 통신하지 않고, 이미 동기화되는 사용 상태(isUsingItem)와 쿨다운만 읽는다.
 *
 *  - newyearcountdown:capsule : (색 * 4 + 상태 + 0.5) / 24.  상태 0 = 기본, 1·2 = 좌우로 흔들림(까는 중), 3 = 갈라져 빛이 샘
 *  - newyearcountdown:fx      : 기념품 프레임 / 3.  0 = 기본, 1·2 = 흔들림/불붙음 등 보조 프레임, 3 = 사용 후(열림·터짐·반짝)
 */
public final class ItemAnim {
    private ItemAnim() {}

    /** 캡슐 상태 (items/capsule.json 의 range_dispatch). */
    public record CapsuleProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<CapsuleProperty> MAP_CODEC = MapCodec.unit(new CapsuleProperty());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            return capsule(stack, owner == null ? null : owner.asLivingEntity());
        }

        @Override
        public MapCodec<CapsuleProperty> type() {
            return MAP_CODEC;
        }
    }

    /** 기념품 프레임 (items/<기념품>.json 의 range_dispatch). */
    public record FxProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<FxProperty> MAP_CODEC = MapCodec.unit(new FxProperty());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            if (!(stack.getItem() instanceof SouvenirItem s)) return 0f;
            return fx(s, stack, owner == null ? null : owner.asLivingEntity()) / 3f;
        }

        @Override
        public MapCodec<FxProperty> type() {
            return MAP_CODEC;
        }
    }

    public static void register() {
        RangeSelectItemModelProperties.ID_MAPPER.put(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "capsule"), CapsuleProperty.MAP_CODEC);
        RangeSelectItemModelProperties.ID_MAPPER.put(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "fx"), FxProperty.MAP_CODEC);
    }

    private static boolean using(ItemStack stack, LivingEntity entity) {
        return entity != null && entity.isUsingItem() && entity.getUseItem() == stack;
    }

    /** 사용(꾹 누르기) 경과 틱. */
    private static int usedTicks(ItemStack stack, LivingEntity entity) {
        return stack.getUseDuration(entity) - entity.getUseItemRemainingTicks();
    }

    /** 0(전부 끝남) ~ 1(방금 시작) */
    private static float cooldown(SouvenirItem item, LivingEntity entity) {
        if (entity instanceof Player p) return p.getCooldowns().getCooldownPercent(item.getDefaultInstance(), 0f);
        return 0f;
    }

    private static float capsule(ItemStack stack, LivingEntity entity) {
        int color = CapsuleItem.colorIndex(stack);
        int state = 0;
        if (using(stack, entity)) {
            int used = usedTicks(stack, entity);
            state = used >= CapsuleItem.OPEN_TICKS * 0.8f ? 3 : 1 + ((used / 2) & 1);
        }
        return (color * 4 + state + 0.5f) / 24f;
    }

    /** 쿨다운이 시작된 뒤 지난 틱. (쿨다운 중이 아니면 큰 값) */
    private static float elapsed(SouvenirItem item, LivingEntity entity) {
        float cd = cooldown(item, entity);
        if (cd <= 0f) return Float.MAX_VALUE;
        return (1f - cd) * item.cooldownTicks();
    }

    private static int fx(SouvenirItem item, ItemStack stack, LivingEntity entity) {
        int age = entity != null ? entity.tickCount : 0;
        int wiggle = 1 + ((age / 2) & 1);       // 2틱마다 번갈아 1, 2
        boolean using = using(stack, entity);
        float el = elapsed(item, entity);
        return switch (item.kind()) {
            case POUCH -> el < 8f ? wiggle : (el < 20f ? 3 : 0);       // 흔든 뒤 입이 열리며 금화가 튄다
            case ENVELOPE, CALENDAR, CARD -> el < item.cooldownTicks() ? 3 : 0;   // 열린 봉투 / 넘어가는 달력 / 펼친 카드
            case FIRECRACKER -> using ? wiggle : (el < 20f ? 3 : 0);   // 심지에 불 → 팡!
            case CHAMPAGNE -> using ? wiggle : (cooldown(item, entity) > 0f ? 3 : 0);  // 흔드는 중 → 쏴아아
            case BADGE -> el < 50f ? 3 : 0;                            // 반짝반짝
            case KITE -> el < 40f ? 3 : 0;                             // 하늘 높이
            default -> 0;
        };
    }
}
