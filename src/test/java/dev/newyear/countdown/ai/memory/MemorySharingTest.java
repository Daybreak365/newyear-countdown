package dev.newyear.countdown.ai.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemorySharingTest {
    private static MemoryRecord rec(String owner, String text, String thread) {
        MemoryRecord r = new MemoryRecord();
        r.owner = owner;
        r.scope = MemoryRecord.Scope.PLAYER;
        r.text = text;
        r.thread = thread;
        r.importance = 6;
        return r;
    }

    @Test
    void sharedMemoryIsVisibleOnlyToChosenPlayers() {
        MemoryStore s = new MemoryStore();
        MemoryRecord secret = s.add(rec("A", "A 와 새벽별만 아는 비밀: 숲속 보물 상자", null), 1);
        assertTrue(s.search("보물", "B", 5, null, true, 2).isEmpty(), "B must not see A's private memory");

        assertEquals(MemoryStore.ShareResult.NOT_OWNER, s.share(secret.id, "B", "C"), "only the owner may share");
        assertEquals(MemoryStore.ShareResult.OK, s.share(secret.id, "B", "A"));
        assertEquals(MemoryStore.ShareResult.ALREADY, s.share(secret.id, "B", "A"));
        assertEquals(1, s.search("보물", "B", 5, null, true, 3).size());
        assertTrue(s.search("보물", "C", 5, null, true, 3).isEmpty(), "C was not invited");
        assertEquals(List.of(secret), s.sharedTo("B"));

        assertEquals(1, s.unshare(secret.id, "B", "A"));
        assertTrue(s.search("보물", "B", 5, null, true, 4).isEmpty());
    }

    @Test
    void worldMemoriesCannotBeShared() {
        MemoryStore s = new MemoryStore();
        MemoryRecord w = rec(null, "마을 광장에 분수가 생겼다", null);
        w.scope = MemoryRecord.Scope.WORLD;
        w = s.add(w, 1);
        assertEquals(MemoryStore.ShareResult.NOT_PRIVATE, s.share(w.id, "B", null));
        assertEquals(MemoryStore.ShareResult.SELF, s.share(s.add(rec("A", "혼자 본 일", null), 1).id, "A", "A"));
    }

    @Test
    void threadSharingAndLockRevokeEverything() {
        MemoryStore s = new MemoryStore();
        s.add(rec("A", "고양이 나비를 만났다", "나비 이야기"), 1);
        s.add(rec("A", "나비가 새끼를 낳았다", "나비 이야기"), 2);
        s.add(rec("A", "다른 이야기", "다른 줄기"), 3);
        assertEquals(2, s.shareThread("A", "나비 이야기", "B"));
        assertEquals(2, s.sharedTo("B").size());
        assertEquals(2, s.unshareAllOf("A"));
        assertTrue(s.sharedTo("B").isEmpty());
    }

    @Test
    void semanticScoreFindsMeaningWithoutSharedWords() {
        MemoryStore s = new MemoryStore();
        MemoryRecord cat = s.add(rec("A", "작은 털뭉치 친구 나비를 숲에 데려다 놓았다", null), 1);
        MemoryRecord dia = s.add(rec("A", "다이아몬드 곡괭이를 받았다", null), 1);
        // 가짜 임베딩: 축 0 = 동물, 축 1 = 광물
        s.setVectors(r -> r == cat ? new float[]{1, 0, 0} : r == dia ? new float[]{0, 1, 0} : null);
        float[] query = {0.95f, 0.05f, 0.3f};   // "고양이 한 마리 줘" (낱말은 하나도 안 겹친다)
        var hits = s.search("고양이 한 마리 줘", query, "A", 2, null, true, 10, java.util.Set.of());
        assertSame(cat, hits.get(0).record());
        assertTrue(hits.get(0).semantic() > 0.9);
        // 키워드만으로는 둘 다 무관 → 점수 차이가 거의 없다
        var plain = s.search("고양이 한 마리 줘", "A", 2, null, true, 10);
        assertTrue(Math.abs(plain.get(0).score() - plain.get(1).score()) < 0.5);
    }

    @Test
    void excludeSkipsAlreadyShownMemories() {
        MemoryStore s = new MemoryStore();
        MemoryRecord a = s.add(rec("A", "빵을 받았다", null), 1);
        MemoryRecord b = s.add(rec("A", "빵을 또 받았다", null), 1);
        var hits = s.search("빵", null, "A", 5, null, true, 2, java.util.Set.of(a.id));
        assertEquals(1, hits.size());
        assertSame(b, hits.get(0).record());
    }

    @Test
    void vectorCodecRoundTripKeepsDirection() {
        float[] v = {0.6f, -0.8f, 0f, 0f};
        float[] back = SemanticIndex.decode(SemanticIndex.encode(v));
        assertTrue(MemoryStore.cosine(v, back) > 0.999);
    }

    @Test
    void indexDropsStaleVectorsWhenTextChanges() {
        MemoryStore s = new MemoryStore();
        SemanticIndex idx = new SemanticIndex();
        idx.configure("m", 3);
        idx.attach(s);
        MemoryRecord r = s.add(rec("A", "첫 내용", null), 1);
        idx.put(r.id, r.hash, new float[]{1, 0, 0});
        assertNotNull(idx.vectorOf(r));
        r.hash = "changed";
        assertNull(idx.vectorOf(r), "a vector for different text must not be used");
        assertEquals(1, idx.missing(s.all(), 10).size());
        idx.configure("other-model", 3);
        assertEquals(0, idx.size(), "changing the model discards vectors");
    }
}
