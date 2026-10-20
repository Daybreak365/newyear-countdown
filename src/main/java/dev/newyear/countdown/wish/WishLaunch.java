package dev.newyear.countdown.wish;

import net.minecraft.world.entity.EntitySpawnReason;
import dev.newyear.countdown.ModSounds;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

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

    public static void handle(ServerPlayer player, String raw) {
        String text = sanitize(raw);
        if (text.isEmpty() || !(player.level() instanceof ServerLevel sw)) return;

        long now = sw.getGameTime();
        Long last = LAST.get(player.getUUID());
        if (last != null && now - last < 20) return; // 연타 방지
        InteractionHand hand = null;
        for (InteractionHand h : InteractionHand.values()) {
            if (player.getItemInHand(h).is(WishEntities.WISH_LANTERN_ITEM)) { hand = h; break; }
        }
        if (hand == null) {
            player.sendOverlayMessage(Component.translatable("wish.newyearcountdown.no_item"));
            return;
        }
        LAST.put(player.getUUID(), now);

        ItemStack stack = player.getItemInHand(hand);
        if (!player.isCreative()) stack.shrink(1);

        // 눈앞 손 높이에서 띄운다
        Vec3 look = player.getViewVector(1.0f);
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
        Vec3 at = player.position().add(flat.scale(0.9)).add(0, 1.0, 0);

        WishLanternEntity lantern = WishEntities.LANTERN.create(sw, EntitySpawnReason.TRIGGERED);
        if (lantern == null) return;
        lantern.snapTo(at.x, at.y, at.z, sw.getRandom().nextFloat() * 360f, 0f);
        lantern.setWish(text, WishLanternEntity.randomColor(sw.getRandom()));
        sw.addFreshEntity(lantern);

        sw.playSound(null, at.x, at.y, at.z, ModSounds.WISH_LAUNCH, SoundSource.PLAYERS, 1.0f, 1.0f);
        sw.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.2, at.z, 14, 0.25, 0.2, 0.25, 0.02);

        WishData.get(sw.getServer()).add(player.getUUID(), text, System.currentTimeMillis());
        WishPackets.sendBook(player, sw.getServer());
        player.sendOverlayMessage(Component.translatable("wish.newyearcountdown.launched"));
    }

    /** 플레이어가 PlayerEntity 로만 주어질 때를 위한 보조 (사용 안 함 시 제거 가능). */
    static boolean isCreative(Player p) {
        return p.isCreative();
    }
}
