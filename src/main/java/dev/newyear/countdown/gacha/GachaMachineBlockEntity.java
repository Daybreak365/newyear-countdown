package dev.newyear.countdown.gacha;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.UUID;

/** 가챠 머신 블록 엔티티: 손잡이를 돌리는 동안의 연출 시간과 캡슐 지급을 맡는다. */
public class GachaMachineBlockEntity extends net.minecraft.block.entity.BlockEntity {
    /**
     * 연출 총 길이(틱) = 지급 시점. 타임라인(GachaRenderer 와 동일):
     * 0~4 투입/움찔, 4~26 손잡이 1.5바퀴 + 돔 안 캡슐 소용돌이, 26~32 배출구 덮개 열림 + 캡슐 밀려나옴,
     * 32~38 받침대에 튕기며 안착, 38~42 반짝, 42 지급.
     */
    public static final int TOTAL = 42;

    /** 클라이언트: 연출이 시작된 월드 시간. */
    public long animStart = Long.MIN_VALUE / 2;
    public int animColor;

    private UUID user;
    private long start;

    public GachaMachineBlockEntity(BlockPos pos, BlockState state) {
        super(GachaBlocks.GACHA_BE, pos, state);
    }

    public void use(ServerPlayerEntity player) {
        if (!(world instanceof ServerWorld sw)) return;
        if (user != null) { // 이미 돌아가는 중
            return;
        }
        user = player.getUuid();
        start = sw.getTime();
        animColor = sw.random.nextInt(CapsuleItem.RGB.length);
        sw.addSyncedBlockEvent(pos, getCachedState().getBlock(), 1, animColor);
        play(sw, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 0.7f, 1.4f);
    }

    @Override
    public boolean onSyncedBlockEvent(int type, int data) {
        if (type == 1 && world != null) {
            animStart = world.getTime();
            animColor = data;
            return true;
        }
        return super.onSyncedBlockEvent(type, data);
    }

    public static void tick(World world, BlockPos pos, BlockState state, GachaMachineBlockEntity be) {
        if (!(world instanceof ServerWorld sw) || be.user == null) return;
        long age = sw.getTime() - be.start;

        if (age == 0) be.play(sw, SoundEvents.BLOCK_CHAIN_PLACE, 0.8f, 1.5f);                            // 동전 투입
        if (age >= 4 && age < 26 && age % 3 == 1) be.play(sw, SoundEvents.BLOCK_COMPARATOR_CLICK, 0.6f, 0.8f + (age - 4) * 0.035f); // 손잡이 딸깍
        if (age >= 6 && age < 26 && age % 5 == 0) be.play(sw, SoundEvents.ENTITY_TURTLE_EGG_CRACK, 0.35f, 1.5f); // 캡슐 달그락
        if (age == 26) be.play(sw, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, 0.7f, 1.5f);                    // 덮개 열림
        if (age == 32) be.play(sw, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, 0.9f, 0.7f);                // 툭
        if (age == 35) be.play(sw, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, 0.5f, 1.0f);                // 통
        if (age == 38) be.play(sw, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.3f);                  // 반짝

        if (age >= TOTAL) {
            UUID id = be.user;
            be.user = null;
            ServerPlayerEntity p = sw.getServer().getPlayerManager().getPlayer(id);
            net.minecraft.item.ItemStack cap = CapsuleItem.of(be.animColor);
            double fx = pos.getX() + 0.5, fy = pos.getY() + 0.3, fz = pos.getZ() + 0.5;
            if (p != null && p.squaredDistanceTo(fx, fy, fz) < 64) {
                p.getInventory().offerOrDrop(cap);
                p.sendMessage(Text.translatable("gacha.newyearcountdown.drawn"), true);
            } else {
                net.minecraft.entity.ItemEntity ie = new net.minecraft.entity.ItemEntity(sw, fx, fy + 0.2, fz, cap);
                sw.spawnEntity(ie);
            }
            sw.spawnParticles(ParticleTypes.END_ROD, fx, fy + 0.4, fz, 8, 0.2, 0.2, 0.2, 0.03);
            be.play(sw, SoundEvents.ENTITY_ITEM_PICKUP, 0.9f, 1.0f);
        }
    }

    private void play(ServerWorld sw, SoundEvent s, float vol, float pitch) {
        sw.playSound(null, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, s, SoundCategory.BLOCKS, vol, pitch);
    }
}
