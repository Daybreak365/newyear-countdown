package dev.newyear.countdown.wish;

import dev.newyear.countdown.ModSounds;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.dispenser.DispenserBehavior;
import net.minecraft.block.dispenser.ItemDispenserBehavior;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPointer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** 발사기에 연등을 넣으면 소원 없는 연등이 하나씩 하늘로 날아간다 (레드스톤 신호마다 한 개). */
public final class LanternDispenser {
    private LanternDispenser() {}

    public static void init() {
        DispenserBlock.registerBehavior(WishEntities.WISH_LANTERN_ITEM, new ItemDispenserBehavior() {
            @Override
            protected ItemStack dispenseSilently(BlockPointer pointer, ItemStack stack) {
                ServerWorld sw = pointer.world();
                Direction dir = pointer.state().get(DispenserBlock.FACING);
                BlockPos front = pointer.pos().offset(dir);
                double x = front.getX() + 0.5, y = front.getY() + 0.1, z = front.getZ() + 0.5;
                if (dir == Direction.DOWN) y = front.getY() - 0.8;
                if (!sw.getBlockState(front).getCollisionShape(sw, front).isEmpty()) {
                    // 앞이 막혀 있으면 위쪽으로 내보낸다
                    y = pointer.pos().getY() + 1.1; x = pointer.pos().getX() + 0.5; z = pointer.pos().getZ() + 0.5;
                }
                WishLanternEntity l = WishEntities.LANTERN.create(sw);
                if (l == null) return stack;
                l.refreshPositionAndAngles(x, y, z, sw.getRandom().nextFloat() * 360f, 0f);
                l.setWish("", WishLanternEntity.randomColor(sw.getRandom()));
                l.fastStart(50);
                l.setVelocity(dir.getOffsetX() * 0.05 + (sw.getRandom().nextDouble() - 0.5) * 0.03,
                        0.03, dir.getOffsetZ() * 0.05 + (sw.getRandom().nextDouble() - 0.5) * 0.03);
                sw.spawnEntity(l);
                sw.playSound(null, x, y, z, ModSounds.WISH_LAUNCH, SoundCategory.BLOCKS, 0.7f, 0.9f + sw.getRandom().nextFloat() * 0.3f);
                sw.spawnParticles(ParticleTypes.END_ROD, x, y + 0.3, z, 6, 0.15, 0.15, 0.15, 0.02);
                stack.decrement(1);
                return stack;
            }

            @Override
            protected void spawnParticles(BlockPointer pointer, Direction side) { }
        });
    }
}
