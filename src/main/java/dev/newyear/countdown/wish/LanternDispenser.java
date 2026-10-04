package dev.newyear.countdown.wish;

import net.minecraft.world.entity.EntitySpawnReason;
import dev.newyear.countdown.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;

/** 발사기에 연등을 넣으면 소원 없는 연등이 하나씩 하늘로 날아간다 (레드스톤 신호마다 한 개). */
public final class LanternDispenser {
    private LanternDispenser() {}

    public static void init() {
        DispenserBlock.registerBehavior(WishEntities.WISH_LANTERN_ITEM, new DefaultDispenseItemBehavior() {
            @Override
            protected ItemStack execute(BlockSource pointer, ItemStack stack) {
                ServerLevel sw = pointer.level();
                Direction dir = pointer.state().getValue(DispenserBlock.FACING);
                BlockPos front = pointer.pos().relative(dir);
                double x = front.getX() + 0.5, y = front.getY() + 0.1, z = front.getZ() + 0.5;
                if (dir == Direction.DOWN) y = front.getY() - 0.8;
                if (!sw.getBlockState(front).getCollisionShape(sw, front).isEmpty()) {
                    // 앞이 막혀 있으면 위쪽으로 내보낸다
                    y = pointer.pos().getY() + 1.1; x = pointer.pos().getX() + 0.5; z = pointer.pos().getZ() + 0.5;
                }
                WishLanternEntity l = WishEntities.LANTERN.create(sw, EntitySpawnReason.TRIGGERED);
                if (l == null) return stack;
                l.snapTo(x, y, z, sw.getRandom().nextFloat() * 360f, 0f);
                l.setWish("", WishLanternEntity.randomColor(sw.getRandom()));
                l.fastStart(50);
                l.setDeltaMovement(dir.getStepX() * 0.05 + (sw.getRandom().nextDouble() - 0.5) * 0.03,
                        0.03, dir.getStepZ() * 0.05 + (sw.getRandom().nextDouble() - 0.5) * 0.03);
                sw.addFreshEntity(l);
                sw.playSound(null, x, y, z, ModSounds.WISH_LAUNCH, SoundSource.BLOCKS, 0.7f, 0.9f + sw.getRandom().nextFloat() * 0.3f);
                sw.sendParticles(ParticleTypes.END_ROD, x, y + 0.3, z, 6, 0.15, 0.15, 0.15, 0.02);
                stack.shrink(1);
                return stack;
            }

            @Override
            protected void playAnimation(BlockSource pointer, Direction side) { }
        });
    }
}
