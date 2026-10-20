package dev.newyear.countdown.gacha;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** 가챠 머신 블록 엔티티: 손잡이를 돌리는 동안의 연출 시간과 캡슐 지급을 맡는다. */
public class GachaMachineBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity {
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

    public void use(ServerPlayer player) {
        if (!(level instanceof ServerLevel sw)) return;
        if (user != null) { // 이미 돌아가는 중
            return;
        }
        user = player.getUUID();
        start = sw.getGameTime();
        animColor = sw.getRandom().nextInt(CapsuleItem.RGB.length);
        sw.blockEvent(worldPosition, getBlockState().getBlock(), 1, animColor);
        play(sw, SoundEvents.IRON_TRAPDOOR_CLOSE, 0.7f, 1.4f);
    }

    @Override
    public boolean triggerEvent(int type, int data) {
        if (type == 1 && level != null) {
            animStart = level.getGameTime();
            animColor = data;
            return true;
        }
        return super.triggerEvent(type, data);
    }

    public static void tick(Level world, BlockPos pos, BlockState state, GachaMachineBlockEntity be) {
        if (!(world instanceof ServerLevel sw) || be.user == null) return;
        long age = sw.getGameTime() - be.start;

        if (age == 0) be.play(sw, SoundEvents.CHAIN_PLACE, 0.8f, 1.5f);                            // 동전 투입
        if (age >= 4 && age < 26 && age % 3 == 1) be.play(sw, SoundEvents.COMPARATOR_CLICK, 0.6f, 0.8f + (age - 4) * 0.035f); // 손잡이 딸깍
        if (age >= 6 && age < 26 && age % 5 == 0) be.play(sw, SoundEvents.TURTLE_EGG_CRACK, 0.35f, 1.5f); // 캡슐 달그락
        if (age == 26) be.play(sw, SoundEvents.IRON_TRAPDOOR_OPEN, 0.7f, 1.5f);                    // 덮개 열림
        if (age == 32) be.play(sw, SoundEvents.STONE_BUTTON_CLICK_ON, 0.9f, 0.7f);                // 툭
        if (age == 35) be.play(sw, SoundEvents.STONE_BUTTON_CLICK_ON, 0.5f, 1.0f);                // 통
        if (age == 38) be.play(sw, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.3f);                  // 반짝

        if (age >= TOTAL) {
            UUID id = be.user;
            be.user = null;
            ServerPlayer p = sw.getServer().getPlayerList().getPlayer(id);
            net.minecraft.world.item.ItemStack cap = CapsuleItem.of(be.animColor);
            double fx = pos.getX() + 0.5, fy = pos.getY() + 0.3, fz = pos.getZ() + 0.5;
            if (p != null && p.distanceToSqr(fx, fy, fz) < 64) {
                p.getInventory().placeItemBackInInventory(cap, net.minecraft.util.Prediction.SERVER_ONLY);
                p.sendOverlayMessage(Component.translatable("gacha.newyearcountdown.drawn"));
            } else {
                net.minecraft.world.entity.item.ItemEntity ie = new net.minecraft.world.entity.item.ItemEntity(sw, fx, fy + 0.2, fz, cap);
                sw.addFreshEntity(ie);
            }
            sw.sendParticles(ParticleTypes.END_ROD, fx, fy + 0.4, fz, 8, 0.2, 0.2, 0.2, 0.03);
            be.play(sw, SoundEvents.ITEM_PICKUP, 0.9f, 1.0f);
        }
    }

    private void play(ServerLevel sw, SoundEvent s, float vol, float pitch) {
        sw.playSound(null, worldPosition.getX() + 0.5, worldPosition.getY() + 0.8, worldPosition.getZ() + 0.5, s, SoundSource.BLOCKS, vol, pitch);
    }
}
