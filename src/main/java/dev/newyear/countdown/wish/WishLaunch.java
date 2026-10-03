package dev.newyear.countdown.wish;

import dev.newyear.countdown.ModSounds;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 소원을 받아 검증하고, 연등을 만들어 날리고, 기록을 남긴다 (서버). */
public final class WishLaunch {
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private WishLaunch() {}

    public static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c < 32 || c == '§' || c == 127) continue;
            sb.append(c);
        }
        String s = sb.toString().trim().replaceAll("\\s+", " ");
        if (s.length() > WishPackets.MAX_LEN) s = s.substring(0, WishPackets.MAX_LEN);
        return s;
    }

    public static void handle(ServerPlayerEntity player, String raw) {
        String text = sanitize(raw);
        if (text.isEmpty() || !(player.getWorld() instanceof ServerWorld sw)) return;

        long now = sw.getTime();
        Long last = LAST.get(player.getUuid());
        if (last != null && now - last < 20) return; // 연타 방지
        Hand hand = null;
        for (Hand h : Hand.values()) {
            if (player.getStackInHand(h).isOf(WishEntities.WISH_LANTERN_ITEM)) { hand = h; break; }
        }
        if (hand == null) {
            player.sendMessage(Text.translatable("wish.newyearcountdown.no_item"), true);
            return;
        }
        LAST.put(player.getUuid(), now);

        ItemStack stack = player.getStackInHand(hand);
        if (!player.isCreative()) stack.decrement(1);

        // 눈앞 손 높이에서 띄운다
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d flat = new Vec3d(look.x, 0, look.z);
        flat = flat.lengthSquared() < 1.0e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
        Vec3d at = player.getPos().add(flat.multiply(0.9)).add(0, 1.0, 0);

        WishLanternEntity lantern = WishEntities.LANTERN.create(sw);
        if (lantern == null) return;
        lantern.refreshPositionAndAngles(at.x, at.y, at.z, sw.getRandom().nextFloat() * 360f, 0f);
        lantern.setWish(text, WishLanternEntity.randomColor(sw.getRandom()));
        sw.spawnEntity(lantern);

        sw.playSound(null, at.x, at.y, at.z, ModSounds.WISH_LAUNCH, SoundCategory.PLAYERS, 1.0f, 1.0f);
        sw.spawnParticles(ParticleTypes.END_ROD, at.x, at.y + 0.2, at.z, 14, 0.25, 0.2, 0.25, 0.02);

        WishData.get(sw.getServer()).add(player.getUuid(), text, System.currentTimeMillis());
        WishPackets.sendBook(player, sw.getServer());
        player.sendMessage(Text.translatable("wish.newyearcountdown.launched"), true);
    }

    /** 플레이어가 PlayerEntity 로만 주어질 때를 위한 보조 (사용 안 함 시 제거 가능). */
    static boolean isCreative(PlayerEntity p) {
        return p.isCreative();
    }
}
