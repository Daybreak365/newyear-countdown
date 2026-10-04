package dev.newyear.countdown.curling;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 컬링 스톤(빨강/노랑). 우클릭을 꾹 눌러 힘을 모았다가 놓으면 바라보는 쪽으로 미끄러뜨린다.
 * 놓을 때 웅크리고 있으면 반시계 방향으로 돌려 왼쪽으로, 아니면 시계 방향으로 돌려 오른쪽으로 휜다.
 */
public class CurlingStoneItem extends Item {
    /** 힘이 가득 차는 시간(틱). */
    public static final int FULL = 40;
    private final int team;

    public CurlingStoneItem(Properties props, int team) {
        super(props);
        this.team = team;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return 72000;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BOW;
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (!(user instanceof ServerPlayer sp) || level.getGameTime() % 2 != 0) return;
        int used = getUseDuration(stack, user) - remaining;
        float power = Math.min(1f, used / (float) FULL);
        int bars = Math.round(power * 10);
        Component bar = Component.literal("▮".repeat(bars)).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("▮".repeat(10 - bars)).withStyle(ChatFormatting.DARK_GRAY));
        Component turn = Component.translatable(sp.isShiftKeyDown() ? "curling.newyearcountdown.spin.ccw" : "curling.newyearcountdown.spin.cw");
        sp.sendOverlayMessage(Component.translatable("curling.newyearcountdown.charge", bar, turn));
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (!(level instanceof ServerLevel sw) || !(user instanceof ServerPlayer sp)) return false;
        int used = getUseDuration(stack, user) - remaining;
        if (used < 4) return false;
        float power = Math.min(1f, used / (float) FULL);
        Vec3 look = sp.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z);
        if (dir.lengthSqr() < 1.0e-4) return false;
        dir = dir.normalize();
        CurlingStoneEntity stone = Curling.STONE.create(sw, EntitySpawnReason.TRIGGERED);
        if (stone == null) return false;
        stone.setup(team, sp.isShiftKeyDown() ? -1f : 1f);
        stone.snapTo(sp.getX() + dir.x * 0.9, sp.getY(), sp.getZ() + dir.z * 0.9, sp.getYRot(), 0f);
        stone.setDeltaMovement(dir.scale(0.1 + 0.6 * power));
        sw.addFreshEntity(stone);
        sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 0.6f, 1.4f);
        stack.consume(1, sp);
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable("item.newyearcountdown.curling_stone.desc").withStyle(ChatFormatting.GRAY));
    }
}
