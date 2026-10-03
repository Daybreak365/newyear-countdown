package dev.newyear.countdown.omikuji;

import net.minecraft.util.Formatting;

import java.util.Random;

/** 오미쿠지 운세 종류와 확률. 문구는 lang 파일(omikuji.newyearcountdown.fortune.<key> / .msg.<key>). */
public final class Fortunes {
    public record Fortune(String key, Formatting color, int weight, float r, float g, float b) {}

    public static final Fortune[] ALL = {
            new Fortune("daekil", Formatting.GOLD, 10, 1.00f, 0.82f, 0.30f),      // 대길
            new Fortune("jungil", Formatting.YELLOW, 20, 1.00f, 0.60f, 0.25f),    // 중길
            new Fortune("sokil", Formatting.GREEN, 20, 0.55f, 0.85f, 0.50f),      // 소길
            new Fortune("gil", Formatting.WHITE, 22, 0.95f, 0.95f, 0.90f),        // 길
            new Fortune("malgil", Formatting.AQUA, 14, 0.60f, 0.70f, 0.85f),      // 말길
            new Fortune("hyung", Formatting.RED, 10, 0.45f, 0.40f, 0.55f),        // 흉
            new Fortune("daehyung", Formatting.DARK_RED, 4, 0.25f, 0.20f, 0.28f), // 대흉
    };

    private Fortunes() {}

    public static int pick(Random random) {
        int total = 0;
        for (Fortune f : ALL) total += f.weight;
        int roll = random.nextInt(total);
        for (int i = 0; i < ALL.length; i++) {
            roll -= ALL[i].weight;
            if (roll < 0) return i;
        }
        return 0;
    }

    public static Fortune get(int index) {
        return ALL[Math.floorMod(index, ALL.length)];
    }
}
