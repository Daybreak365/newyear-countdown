package dev.newyear.countdown.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 이 서버(월드)에서 내가 뽑은 오미쿠지. 서버가 저장하고 접속할 때 내려 주므로 기기·서버·맵이 달라도 섞이지 않는다.
 * 시간순 목록의 0번이 "첫 뽑기(메인)", 나머지는 "추가 뽑기"다.
 * 보관함은 시간순으로 보여 준다: 맨 왼쪽이 첫 뽑기, 오른쪽으로 넘길수록 나중에 뽑은 것.
 */
public final class OmikujiBook {
    public static final class Entry {
        public final int result;   // Fortunes.ALL 인덱스
        public final int number;   // 종이에 적힌 번호
        public final long time;    // 뽑은 시각(epoch ms)

        public Entry(int result, int number, long time) {
            this.result = result;
            this.number = number;
            this.time = time;
        }
    }

    private static List<Entry> entries = new ArrayList<>(); // 시간순

    private OmikujiBook() {}

    public static void replace(List<Entry> list) {
        entries = new ArrayList<>(list);
    }

    public static void clear() {
        entries = new ArrayList<>();
    }

    /** 새로 뽑은 것을 더하고, 시간순 인덱스(0 = 첫 뽑기)를 돌려준다. */
    public static int add(Entry e) {
        entries.add(e);
        return entries.size() - 1;
    }

    public static int size() {
        return entries.size();
    }

    public static int extraCount() {
        return Math.max(0, entries.size() - 1);
    }

    public static Entry main() {
        return entries.get(0);
    }

    /** 보관함 i 번째(시간순, 0 = 첫 뽑기). */
    public static Entry display(int i) {
        return entries.get(i);
    }

    /** 보관함 i 번째의 추가 뽑기 회차(첫 뽑기면 0). */
    public static int extraNo(int i) {
        return i;
    }
}
