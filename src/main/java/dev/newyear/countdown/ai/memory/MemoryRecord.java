package dev.newyear.countdown.ai.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 기억 한 조각. AI 의 "기억"이며 실제 게임 상태가 아니다 — 참조(엔티티·스크립트·위치)는
 * 검색할 때 실제 세계 상태와 대조해 현재 상태를 함께 알려 준다.
 */
public final class MemoryRecord {
    public enum Scope {
        /** 그 플레이어와 AI 사이의 사적인 기억. 다른 플레이어와의 대화에서는 꺼내지 않는다. */
        PLAYER,
        /** 월드 전체의 공개 사건·장소·규칙. */
        WORLD
    }

    public String id;
    public Scope scope = Scope.PLAYER;
    /** 플레이어 기억이면 UUID 문자열. */
    public String owner;
    public String ownerName;
    /**
     * wish, outcome, conversation, event, place, entity, item, ability, rule, script, fact, preference, summary
     */
    public String kind = "fact";
    public String text = "";
    public List<String> tags = new ArrayList<>();
    /** 1~10. */
    public int importance = 5;
    public long created;
    public long lastUsed;
    public int uses;
    /** 관련 기억 ID (서사 연결). */
    public List<String> links = new ArrayList<>();
    /** 같은 이야기 줄기 이름 (예: "고양이 나비 이야기"). */
    public String thread;
    public String wishId;
    public String scriptId;
    public String entityUuid;
    public String dimension;
    public int[] pos;
    /** 다른 기억들을 요약해 만든 기억인지. */
    public boolean summary;
    /** 중복 방지용 정규화 해시. */
    public String hash;
    /** 요약되어 대체된 기억 (검색에서 제외). */
    public boolean archived;
}
