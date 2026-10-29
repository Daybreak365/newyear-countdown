package dev.newyear.countdown.ai.wish;

import java.util.ArrayList;
import java.util.List;

/** AI 가 만든 퀘스트. 진행 상황은 스크립트/서버 이벤트가 갱신하며 AI 를 부르지 않는다. */
public final class Quest {
    public enum State { ACTIVE, COMPLETED, FAILED, CANCELLED }

    public static final class Objective {
        public String id;
        public String text = "";
        public double progress;
        public double target = 1;
        public boolean done;
        public boolean optional;
        public boolean hidden;
    }

    public String id;
    public String wishId;
    public String owner;
    public String ownerName;
    public List<String> participants = new ArrayList<>();
    public String title = "";
    public String description = "";
    /** AI 가 제시한 조건 (계약 내용). */
    public String conditions = "";
    public String reward = "";
    public String failure = "";
    public List<Objective> objectives = new ArrayList<>();
    public State state = State.ACTIVE;
    public long created;
    /** 엔진 틱 기준 마감 (0 = 없음). */
    public long deadlineTick;
    public long startTick;
    /** 높을수록 HUD 위쪽. */
    public int priority = 5;
    public String scriptId;
    public String resultNote = "";
    /** 마감 시 자동 실패 처리 여부. */
    public boolean failOnTimeout = true;

    public Objective objective(String oid) {
        for (Objective o : objectives) if (o.id.equals(oid)) return o;
        return null;
    }

    public boolean allRequiredDone() {
        for (Objective o : objectives) if (!o.optional && !o.done) return false;
        return !objectives.isEmpty();
    }

    public boolean involves(String uuid) {
        return uuid.equals(owner) || participants.contains(uuid);
    }
}
