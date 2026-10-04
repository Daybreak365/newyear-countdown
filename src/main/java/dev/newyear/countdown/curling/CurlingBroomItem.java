package dev.newyear.countdown.curling;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 컬링 브룸: 꾹 누르고 있으면 앞쪽(3칸 안) 움직이는 스톤 앞의 얼음을 쓸어 덜 느려지고 덜 휘게 한다. */
public class CurlingBroomItem extends Item {
    public CurlingBroomItem(Properties props) {
        super(props);
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
        return ItemUseAnimation.BRUSH;
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (!(level instanceof ServerLevel sw)) return;
        Vec3 look = user.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z).normalize();
        boolean any = false;
        for (CurlingStoneEntity s : sw.getEntitiesOfClass(CurlingStoneEntity.class, user.getBoundingBox().inflate(3.0, 1.5, 3.0), CurlingStoneEntity::isMoving)) {
            Vec3 to = new Vec3(s.getX() - user.getX(), 0, s.getZ() - user.getZ());
            if (to.lengthSqr() > 9 || to.normalize().dot(dir) < 0.2) continue;
            s.sweep();
            any = true;
        }
        if (any && level.getGameTime() % 10 == 0 && user instanceof net.minecraft.server.level.ServerPlayer sp) {
            sp.sendOverlayMessage(Component.translatable("curling.newyearcountdown.sweep").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        }
        if (level.getGameTime() % 4 == 0) {
            sw.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, any ? 0.8f : 0.4f, any ? 1.3f : 1.0f);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable("item.newyearcountdown.curling_broom.desc").withStyle(ChatFormatting.GRAY));
    }
}
