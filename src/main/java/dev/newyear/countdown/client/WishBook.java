package dev.newyear.countdown.client;

import java.util.ArrayList;
import java.util.List;

/** 이 서버(월드)에서 내가 빈 소원 기록 (서버가 저장하고 접속할 때 내려 준다). 시간순. */
public final class WishBook {
    public static final class Wish {
        public final String text;
        public final long time;

        public Wish(String text, long time) {
            this.text = text;
            this.time = time;
        }
    }

    private static List<Wish> wishes = new ArrayList<>();

    private WishBook() {}

    public static void replace(List<Wish> list) {
        wishes = new ArrayList<>(list);
    }

    public static void clear() {
        wishes = new ArrayList<>();
    }

    public static int size() {
        return wishes.size();
    }

    /** 0 이 가장 최근. */
    public static Wish newest(int i) {
        return wishes.get(wishes.size() - 1 - i);
    }
}
