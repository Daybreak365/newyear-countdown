package dev.newyear.countdown.ai.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {
    private static MemoryRecord rec(String owner, MemoryRecord.Scope scope, String text, int importance) {
        MemoryRecord r = new MemoryRecord();
        r.owner = owner;
        r.scope = scope;
        r.text = text;
        r.importance = importance;
        return r;
    }

    @Test
    void retrievesRelevantKoreanMemories() {
        MemoryStore s = new MemoryStore();
        long now = 1_000_000_000L;
        s.add(rec("A", MemoryRecord.Scope.PLAYER, "플레이어가 다이아몬드 64개를 받아 갔다", 5), now);
        s.add(rec("A", MemoryRecord.Scope.PLAYER, "고양이 '나비'를 숲에 두고 떠났다", 7), now);
        s.add(rec("A", MemoryRecord.Scope.PLAYER, "빵을 하나 받았다", 2), now);
        var hits = s.search("나한테 고양이 한 마리 줘", "A", 2, null, true, now);
        assertEquals("고양이 '나비'를 숲에 두고 떠났다", hits.get(0).record().text);
    }

    @Test
    void privateMemoriesAreNotShared() {
        MemoryStore s = new MemoryStore();
        long now = 1L;
        s.add(rec("A", MemoryRecord.Scope.PLAYER, "A 의 비밀 소원: 다이아몬드", 9), now);
        s.add(rec(null, MemoryRecord.Scope.WORLD, "마을에 다이아몬드 분수가 생겼다", 6), now);
        var hits = s.search("다이아몬드", "B", 10, null, true, now);
        assertEquals(1, hits.size());
        assertEquals(MemoryRecord.Scope.WORLD, hits.get(0).record().scope);
    }

    @Test
    void deduplicates() {
        MemoryStore s = new MemoryStore();
        MemoryRecord a = s.add(rec("A", MemoryRecord.Scope.PLAYER, "빵을 받았다", 2), 1);
        MemoryRecord b = s.add(rec("A", MemoryRecord.Scope.PLAYER, "  빵을   받았다 ", 6), 2);
        assertSame(a, b);
        assertEquals(6, a.importance);
        assertEquals(1, s.all().size());
    }

    @Test
    void prunesLowValueFirst() {
        MemoryStore s = new MemoryStore();
        s.maxPerPlayer = 3;
        s.add(rec("A", MemoryRecord.Scope.PLAYER, "중요한 사건", 10), 1);
        for (int i = 0; i < 5; i++) s.add(rec("A", MemoryRecord.Scope.PLAYER, "사소한 일 " + i, 1), 2 + i);
        assertEquals(3, s.all().size());
        assertTrue(s.all().stream().anyMatch(r -> r.text.equals("중요한 사건")));
    }

    @Test
    void linksPullRelatedStory() {
        MemoryStore s = new MemoryStore();
        MemoryRecord cat = s.add(rec(null, MemoryRecord.Scope.WORLD, "A 가 고양이를 소환했다", 6), 1);
        MemoryRecord lost = rec(null, MemoryRecord.Scope.WORLD, "그 아이가 버려진 탑에서 사라졌다", 6);
        lost.links.add(cat.id);
        s.add(lost, 2);
        assertTrue(cat.links.contains(lost.id), "links are bidirectional");
        var hits = s.search("고양이", null, 1, null, true, 3);
        assertEquals(2, hits.size(), "linked memory is pulled in");
    }

    @Test
    void summarizeReplacesSources() {
        MemoryStore s = new MemoryStore();
        for (int i = 0; i < 6; i++) s.add(rec("A", MemoryRecord.Scope.PLAYER, "작은 대화 " + i, 2), i);
        List<MemoryRecord> c = s.summarizeCandidates("A", 5, 4);
        assertEquals(4, c.size());
        s.replaceWithSummary(c, "작은 대화를 여러 번 나눴다", 10);
        long active = s.all().stream().filter(r -> !r.archived).count();
        assertEquals(3, active);
    }
}
