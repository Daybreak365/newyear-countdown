package dev.newyear.countdown.ai.script;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** 미리 다듬어 둔 파티클 묶음 (AI 가 빠르게 쓰는 연출 팔레트). */
public final class Bursts {
    private Bursts() {}

    public static void play(ServerLevel l, double[] p, String style) {
        double x = p[0], y = p[1], z = p[2];
        switch (style) {
            case "hearts" -> l.sendParticles(ParticleTypes.HEART, x, y + 0.5, z, 8, 0.5, 0.4, 0.5, 0.05);
            case "magic" -> {
                l.sendParticles(ParticleTypes.ENCHANT, x, y + 0.6, z, 50, 0.6, 0.6, 0.6, 0.6);
                l.sendParticles(ParticleTypes.WITCH, x, y + 0.6, z, 12, 0.4, 0.4, 0.4, 0.02);
            }
            case "snow" -> l.sendParticles(ParticleTypes.SNOWFLAKE, x, y + 1, z, 40, 0.8, 0.6, 0.8, 0.02);
            case "gold" -> {
                l.sendParticles(new DustParticleOptions(0xE8B84A, 1.2f), x, y + 0.6, z, 30, 0.5, 0.5, 0.5, 0);
                l.sendParticles(ParticleTypes.WAX_ON, x, y + 0.6, z, 12, 0.5, 0.5, 0.5, 0.5);
            }
            case "soul" -> l.sendParticles(ParticleTypes.SOUL, x, y + 0.4, z, 16, 0.4, 0.3, 0.4, 0.02);
            case "notes" -> l.sendParticles(ParticleTypes.NOTE, x, y + 1.2, z, 8, 0.6, 0.3, 0.6, 1.0);
            case "confetti" -> {
                int[] colors = {0xF06A6A, 0xF6E05A, 0x6CCB6C, 0x5AA0E8, 0xC88AF0};
                for (int c : colors) l.sendParticles(new DustParticleOptions(c, 1.0f), x, y + 1.2, z, 10, 0.8, 0.5, 0.8, 0);
                l.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.AMBIENT, 0.6f, 1.2f);
            }
            default -> {
                l.sendParticles(ParticleTypes.END_ROD, x, y + 0.6, z, 18, 0.35, 0.35, 0.35, 0.04);
                l.sendParticles(new DustParticleOptions(0xFFE6A0, 0.8f), x, y + 0.6, z, 14, 0.5, 0.5, 0.5, 0);
            }
        }
    }
}
