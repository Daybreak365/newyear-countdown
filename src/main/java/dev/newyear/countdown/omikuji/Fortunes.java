package dev.newyear.countdown.omikuji;

import net.minecraft.util.Formatting;

import java.util.Random;

/**
 * 오미쿠지 운세 등급과 확률. 문구는 lang 파일(omikuji.newyearcountdown.fortune/name/kanji/msg/line.*.<key>).
 * 배열 번호는 저장된 기록에 그대로 쓰이므로 순서를 바꾸지 않는다(새 등급은 뒤에 추가). 좋고 나쁨은 tier 로 판단한다.
 *
 * 등급(좋은 순): 대길 > 중길 > 소길 > 말길 > 평 > 소흉 > 중흉 > 흉 > 대흉
 */
public final class Fortunes {
    /**
     * @param tier  2 대길, 1 길 계열, 0 평, -1 흉 계열, -2 대흉
     * @param kanji 운세 이름 아래 한자 표기가 있는지
     * @param paper 운세 종이에 쓰는 글자색(ARGB)
     */
    public record Fortune(String key, Formatting color, int weight, float r, float g, float b, int tier, boolean kanji, int paper) {}

    public static final Fortune[] ALL = {
            new Fortune("daekil", Formatting.GOLD, 8, 1.00f, 0.82f, 0.30f, 2, true, 0xFFC8962A),           // 0 대길
            new Fortune("jungil", Formatting.YELLOW, 14, 1.00f, 0.60f, 0.25f, 1, true, 0xFFD2691E),        // 1 중길
            new Fortune("sokil", Formatting.GREEN, 16, 0.55f, 0.85f, 0.50f, 1, true, 0xFF3E8E41),          // 2 소길
            new Fortune("pyeongta", Formatting.WHITE, 16, 0.95f, 0.95f, 0.90f, 0, true, 0xFF5A5A5A),       // 3 평 (구 '길')
            new Fortune("malgil", Formatting.AQUA, 14, 0.60f, 0.70f, 0.85f, 1, true, 0xFF3B6EA5),          // 4 말길
            new Fortune("eom", Formatting.RED, 9, 0.45f, 0.40f, 0.55f, -1, true, 0xFFB03030),              // 5 흉
            new Fortune("jwejwe", Formatting.DARK_RED, 5, 0.25f, 0.20f, 0.28f, -2, true, 0xFF5A1010),      // 6 대흉
            new Fortune("sohyung", Formatting.LIGHT_PURPLE, 10, 0.65f, 0.50f, 0.75f, -1, true, 0xFF8A5AA0), // 7 소흉
            new Fortune("junghyung", Formatting.DARK_PURPLE, 8, 0.55f, 0.40f, 0.50f, -1, true, 0xFF7A3A5A), // 8 중흉
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
