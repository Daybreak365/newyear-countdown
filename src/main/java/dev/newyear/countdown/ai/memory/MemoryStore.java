package dev.newyear.countdown.ai.memory;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 기억 저장소와 검색. 서버 스레드에서만 사용한다 (동기화하지 않음).
 * 검색 점수 = 관련성(토큰·한글 2-gram 겹침) + 의미 유사도(임베딩이 있을 때) + 중요도 + 최근성 + 사용 빈도.
 */
public final class MemoryStore {
    private final LinkedHashMap<String, MemoryRecord> byId = new LinkedHashMap<>();
    private final Map<String, String> byHash = new HashMap<>();
    private final Map<String, Set<String>> tokenCache = new HashMap<>();
    private boolean dirty;

    public int maxPerPlayer = 400;
    public int maxWorld = 1200;
    /** 의미 유사도 가중치 (관련성 가중치 4.0 과 같은 척도). */
    public double semanticWeight = 3.5;
    /** 이 코사인 유사도 아래는 의미상 무관한 것으로 본다. */
    public double semanticFloor = 0.25;

    /** 기억 → 정규화된 임베딩 벡터 (없으면 null). 임베딩 색인이 꽂아 준다. */
    public interface Vectors {
        float[] get(MemoryRecord r);
    }

    private Vectors vectors = r -> null;

    public void setVectors(Vectors v) {
        this.vectors = v == null ? r -> null : v;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public Collection<MemoryRecord> all() {
        return byId.values();
    }

    public MemoryRecord get(String id) {
        return byId.get(id);
    }

    public void loadAll(List<MemoryRecord> records) {
        byId.clear();
        byHash.clear();
        tokenCache.clear();
        for (MemoryRecord r : records) {
            if (r == null || r.id == null || r.text == null) continue;
            if (r.tags == null) r.tags = new ArrayList<>();
            if (r.links == null) r.links = new ArrayList<>();
            if (r.sharedWith == null) r.sharedWith = new ArrayList<>();
            if (r.scope == null) r.scope = MemoryRecord.Scope.PLAYER;
            if (r.hash == null) r.hash = hash(r);
            byId.put(r.id, r);
            byHash.put(r.hash, r.id);
        }
    }

    // ---------------------------------------------------------------- 쓰기

    /**
     * 기억을 추가한다. 같은 내용이 이미 있으면 새로 만들지 않고 기존 기억의 중요도·시각을 갱신해 돌려준다.
     */
    public MemoryRecord add(MemoryRecord r, long now) {
        if (r.text == null) r.text = "";
        r.text = r.text.strip();
        if (r.text.length() > 600) r.text = r.text.substring(0, 600);
        r.importance = Math.max(1, Math.min(10, r.importance));
        if (r.tags == null) r.tags = new ArrayList<>();
        if (r.links == null) r.links = new ArrayList<>();
        if (r.sharedWith == null) r.sharedWith = new ArrayList<>();
        if (r.scope == MemoryRecord.Scope.WORLD) r.sharedWith.clear();
        r.sharedWith.remove(r.owner);
        r.hash = hash(r);
        String existing = byHash.get(r.hash);
        if (existing != null && byId.containsKey(existing)) {
            MemoryRecord e = byId.get(existing);
            e.importance = Math.max(e.importance, r.importance);
            e.lastUsed = now;
            for (String l : r.links) if (!e.links.contains(l)) e.links.add(l);
            for (String t : r.tags) if (!e.tags.contains(t)) e.tags.add(t);
            for (String u : r.sharedWith) if (!e.sharedWith.contains(u)) e.sharedWith.add(u);
            if (e.thread == null) e.thread = r.thread;
            e.archived = false;
            dirty = true;
            return e;
        }
        if (r.id == null) r.id = "m-" + UUID.randomUUID().toString().substring(0, 8);
        if (r.created == 0) r.created = now;
        r.lastUsed = now;
        byId.put(r.id, r);
        byHash.put(r.hash, r.id);
        // 양방향 연결
        for (String l : r.links) {
            MemoryRecord other = byId.get(l);
            if (other != null && !other.links.contains(r.id)) other.links.add(r.id);
        }
        dirty = true;
        prune(r, now);
        return r;
    }

    public boolean forget(String id) {
        MemoryRecord r = byId.remove(id);
        if (r == null) return false;
        byHash.remove(r.hash);
        tokenCache.remove(id);
        for (MemoryRecord o : byId.values()) o.links.remove(id);
        dirty = true;
        return true;
    }

    /** 플레이어의 사적 기억을 모두 지운다. 반환: 지운 수. */
    public int clearPlayer(String uuid) {
        List<String> ids = new ArrayList<>();
        for (MemoryRecord r : byId.values()) if (r.scope == MemoryRecord.Scope.PLAYER && uuid.equals(r.owner)) ids.add(r.id);
        ids.forEach(this::forget);
        return ids.size();
    }

    public int clearAll() {
        int n = byId.size();
        byId.clear();
        byHash.clear();
        tokenCache.clear();
        dirty = true;
        return n;
    }

    // ---------------------------------------------------------------- 공유

    public enum ShareResult { OK, ALREADY, NOT_FOUND, NOT_OWNER, NOT_PRIVATE, SELF }

    /**
     * 사적 기억을 다른 플레이어와 공유한다. by 는 공유를 요청한 사람 — 주인만 자기 기억을 공유할 수 있다
     * (by == null 이면 관리자 권한으로 검사 없이).
     */
    public ShareResult share(String id, String withUuid, String by) {
        MemoryRecord r = byId.get(id);
        if (r == null) return ShareResult.NOT_FOUND;
        if (r.scope != MemoryRecord.Scope.PLAYER) return ShareResult.NOT_PRIVATE;
        if (by != null && !by.equals(r.owner)) return ShareResult.NOT_OWNER;
        if (withUuid.equals(r.owner)) return ShareResult.SELF;
        if (r.sharedWith.contains(withUuid)) return ShareResult.ALREADY;
        r.sharedWith.add(withUuid);
        dirty = true;
        return ShareResult.OK;
    }

    /** 공유를 거둔다. withUuid == null 이면 모두에게서. 반환: 실제로 거둔 수. */
    public int unshare(String id, String withUuid, String by) {
        MemoryRecord r = byId.get(id);
        if (r == null || (by != null && !by.equals(r.owner))) return 0;
        int before = r.sharedWith.size();
        if (withUuid == null) r.sharedWith.clear();
        else r.sharedWith.remove(withUuid);
        int n = before - r.sharedWith.size();
        if (n > 0) dirty = true;
        return n;
    }

    /** 한 플레이어가 공유한 모든 기억의 공유를 거둔다 (기억 잠금). 반환: 바뀐 기억 수. */
    public int unshareAllOf(String ownerUuid) {
        int n = 0;
        for (MemoryRecord r : byId.values()) {
            if (ownerUuid.equals(r.owner) && !r.sharedWith.isEmpty()) {
                r.sharedWith.clear();
                n++;
            }
        }
        if (n > 0) dirty = true;
        return n;
    }

    /** 같은 이야기 줄기(thread)의 주인 기억을 모두 공유한다. 반환: 새로 공유된 수. */
    public int shareThread(String ownerUuid, String thread, String withUuid) {
        int n = 0;
        for (MemoryRecord r : new ArrayList<>(byId.values())) {
            if (thread.equals(r.thread) && ownerUuid.equals(r.owner) && share(r.id, withUuid, ownerUuid) == ShareResult.OK) n++;
        }
        return n;
    }

    /** 다른 사람이 viewer 에게 공유한 기억. */
    public List<MemoryRecord> sharedTo(String viewer) {
        List<MemoryRecord> out = new ArrayList<>();
        for (MemoryRecord r : byId.values()) if (r.scope == MemoryRecord.Scope.PLAYER && r.sharedWith.contains(viewer)) out.add(r);
        return out;
    }

    /** 한도를 넘으면 점수가 가장 낮은 기억부터 지운다 (요약본·연결이 많은 기억은 보호). */
    private void prune(MemoryRecord added, long now) {
        boolean player = added.scope == MemoryRecord.Scope.PLAYER;
        int limit = player ? maxPerPlayer : maxWorld;
        List<MemoryRecord> pool = new ArrayList<>();
        for (MemoryRecord r : byId.values()) {
            if (r.scope != added.scope) continue;
            if (player && !added.owner.equals(r.owner)) continue;
            pool.add(r);
        }
        if (pool.size() <= limit) return;
        pool.sort(Comparator.comparingDouble(r -> keepScore(r, now)));
        int remove = pool.size() - limit;
        for (int i = 0; i < pool.size() && remove > 0; i++) {
            if (pool.get(i) == added) continue;
            forget(pool.get(i).id);
            remove--;
        }
    }

    private static double keepScore(MemoryRecord r, long now) {
        double days = Math.max(0, (now - r.lastUsed) / 86_400_000.0);
        return r.importance * 2 + (r.summary ? 6 : 0) + r.links.size() + Math.min(5, r.uses) * 0.5 - days * 0.15 - (r.archived ? 20 : 0);
    }

    // ---------------------------------------------------------------- 검색

    /** semantic 은 질의와의 코사인 유사도 (임베딩이 없으면 NaN). */
    public record Hit(MemoryRecord record, double score, double semantic) {
        public Hit(MemoryRecord record, double score) {
            this(record, score, Double.NaN);
        }
    }

    public List<Hit> search(String query, String playerUuid, int limit, Set<String> kinds, boolean includeWorld, long now) {
        return search(query, null, playerUuid, limit, kinds, includeWorld, now, Set.of());
    }

    /**
     * @param query       검색어 (소원 문장 등)
     * @param queryVec    검색어의 임베딩 (없으면 null → 키워드 점수만)
     * @param playerUuid  지금 대화 중인 플레이어 (그 사람의 사적 기억 + 그 사람에게 공유된 기억). null 이면 공개 기억만
     * @param kinds       종류 제한 (null 이면 전부)
     * @param exclude     결과에서 뺄 기억 ID (이미 보여 준 것)
     */
    public List<Hit> search(String query, float[] queryVec, String playerUuid, int limit, Set<String> kinds, boolean includeWorld, long now, Set<String> exclude) {
        Set<String> q = tokens(query == null ? "" : query);
        List<Hit> hits = new ArrayList<>();
        for (MemoryRecord r : byId.values()) {
            if (r.archived || exclude.contains(r.id)) continue;
            if (!visible(r, playerUuid, includeWorld)) continue;
            if (kinds != null && !kinds.contains(r.kind)) continue;
            double rel = relevance(q, r);
            double sem = Double.NaN;
            double semScore = 0;
            if (queryVec != null) {
                float[] v = vectors.get(r);
                if (v != null && v.length == queryVec.length) {
                    sem = cosine(queryVec, v);
                    // floor 이하 0, floor+0.4 이상이면 1 (짧은 한국어 문장끼리의 유사도는 0.3~0.7 에 몰린다)
                    semScore = Math.max(0, Math.min(1, (sem - semanticFloor) / 0.4));
                }
            }
            double days = Math.max(0, (now - r.created) / 86_400_000.0);
            double usedDays = Math.max(0, (now - r.lastUsed) / 86_400_000.0);
            double score = rel * 4.0
                    + semScore * semanticWeight
                    + r.importance / 10.0 * 1.6
                    + Math.exp(-days / 10.0) * 0.9
                    + Math.exp(-usedDays / 3.0) * 0.3
                    + (playerUuid != null && playerUuid.equals(r.owner) ? 0.4 : 0)
                    + (r.summary ? 0.2 : 0);
            // 질의와 전혀 무관하면 (낱말도 뜻도 겹치지 않으면) 아주 중요한 기억만 남긴다
            if (!q.isEmpty() && rel == 0 && semScore == 0 && r.importance < 8) score *= 0.35;
            hits.add(new Hit(r, score, sem));
        }
        hits.sort((a, b) -> Double.compare(b.score, a.score));
        List<Hit> out = new ArrayList<>(hits.subList(0, Math.min(limit, hits.size())));
        // 같은 이야기 줄기의 기억을 하나 더 끌어온다 (서사 연결)
        Set<String> have = new HashSet<>();
        for (Hit h : out) have.add(h.record.id);
        List<Hit> extra = new ArrayList<>();
        for (Hit h : out) {
            for (String l : h.record.links) {
                MemoryRecord lr = byId.get(l);
                if (lr != null && !lr.archived && !have.contains(l) && !exclude.contains(l) && visible(lr, playerUuid, includeWorld) && extra.size() < 3) {
                    extra.add(new Hit(lr, h.score * 0.5));
                    have.add(l);
                }
            }
        }
        out.addAll(extra);
        for (Hit h : out) {
            h.record.lastUsed = now;
            h.record.uses++;
        }
        if (!out.isEmpty()) dirty = true;
        return out;
    }

    public static boolean visible(MemoryRecord r, String playerUuid, boolean includeWorld) {
        if (r.scope == MemoryRecord.Scope.WORLD) return includeWorld;
        return r.visibleTo(playerUuid);
    }

    /** 두 벡터의 코사인 유사도. */
    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / Math.sqrt(na * nb);
    }

    public List<MemoryRecord> byThread(String thread) {
        List<MemoryRecord> out = new ArrayList<>();
        for (MemoryRecord r : byId.values()) if (thread.equals(r.thread)) out.add(r);
        out.sort(Comparator.comparingLong(r -> r.created));
        return out;
    }

    public List<MemoryRecord> ofPlayer(String uuid) {
        List<MemoryRecord> out = new ArrayList<>();
        for (MemoryRecord r : byId.values()) if (uuid.equals(r.owner)) out.add(r);
        return out;
    }

    /** 요약 대상: 오래되고 중요도가 낮은 사적 기억. */
    public List<MemoryRecord> summarizeCandidates(String uuid, int threshold, int take) {
        List<MemoryRecord> mine = new ArrayList<>();
        for (MemoryRecord r : byId.values()) {
            if (r.scope == MemoryRecord.Scope.PLAYER && uuid.equals(r.owner) && !r.archived && !r.summary) mine.add(r);
        }
        if (mine.size() < threshold) return List.of();
        mine.sort(Comparator.comparingDouble(r -> r.importance * 1e13 + r.created));
        List<MemoryRecord> out = new ArrayList<>();
        for (MemoryRecord r : mine) {
            if (r.importance >= 8) continue;
            out.add(r);
            if (out.size() >= take) break;
        }
        return out;
    }

    /** 여러 기억을 요약 기억 하나로 대체한다 (원본은 보관 처리). */
    public MemoryRecord replaceWithSummary(List<MemoryRecord> sources, String summaryText, long now) {
        if (sources.isEmpty()) return null;
        MemoryRecord s = new MemoryRecord();
        MemoryRecord first = sources.get(0);
        s.scope = first.scope;
        s.owner = first.owner;
        s.ownerName = first.ownerName;
        s.kind = "summary";
        s.summary = true;
        s.text = summaryText;
        s.importance = 6;
        s.created = now;
        Set<String> tags = new HashSet<>();
        for (MemoryRecord r : sources) {
            tags.addAll(r.tags);
            r.archived = true;
            s.links.add(r.id);
        }
        s.tags.addAll(tags);
        dirty = true;
        return add(s, now);
    }

    // ---------------------------------------------------------------- 토큰

    private double relevance(Set<String> q, MemoryRecord r) {
        if (q.isEmpty()) return 0;
        Set<String> t = tokenCache.computeIfAbsent(r.id, k -> {
            Set<String> s = tokens(r.text);
            for (String tag : r.tags) s.addAll(tokens(tag));
            if (r.thread != null) s.addAll(tokens(r.thread));
            return s;
        });
        if (t.isEmpty()) return 0;
        int overlap = 0;
        for (String x : q) if (t.contains(x)) overlap++;
        return overlap / Math.sqrt((double) q.size() * Math.max(4, t.size()));
    }

    /** 단어 토큰 + 한글은 글자 2-gram (조사가 붙어도 겹치도록). */
    public static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder w = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                w.append(c);
            } else if (!w.isEmpty()) {
                addWord(out, w.toString());
                w.setLength(0);
            }
        }
        return out;
    }

    private static final Set<String> STOP = Set.of("the", "a", "an", "to", "of", "and", "is", "in", "me", "my", "i", "you",
            "줘", "주세요", "해줘", "해", "좀", "나", "내", "나한테", "그", "이", "저");

    private static void addWord(Set<String> out, String word) {
        if (STOP.contains(word)) return;
        boolean hangul = word.chars().anyMatch(ch -> ch >= 0xAC00 && ch <= 0xD7A3);
        if (!hangul) {
            if (word.length() > 1) out.add(word);
            return;
        }
        if (word.length() <= 2) {
            out.add(word);
            return;
        }
        for (int i = 0; i + 2 <= word.length(); i++) out.add(word.substring(i, i + 2));
    }

    private static String hash(MemoryRecord r) {
        String norm = (r.scope + "|" + r.owner + "|" + r.kind + "|" + r.text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(norm.hashCode());
        }
    }
}
