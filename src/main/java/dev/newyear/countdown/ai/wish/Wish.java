package dev.newyear.countdown.ai.wish;

import java.util.ArrayList;
import java.util.List;

/**
 * 소원 하나의 생명주기 기록. 소원의 상태와 그 소원이 만든 스크립트의 상태는 따로 관리한다
 * (소원은 완료되어도 영구 능력 스크립트는 계속 돌 수 있다).
 */
public final class Wish {
    public enum State {
        CREATED, ANALYZING, NEGOTIATING, AWAITING_ACCEPTANCE, AWAITING_APPROVAL, PREPARING, VALIDATING, ACTIVE,
        COMPLETED, FAILED, REJECTED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == REJECTED || this == CANCELLED;
        }

        /** 플레이어 대화가 아직 열려 있는 상태. */
        public boolean conversational() {
            return this == CREATED || this == ANALYZING || this == NEGOTIATING || this == AWAITING_ACCEPTANCE;
        }
    }

    public record Line(String role, String text, long time) {}

    public static final class Contract {
        public String id;
        public String terms = "";
        public String reward = "";
        public String penalty = "";
        public int timeLimitSeconds;
        public boolean accepted;
        public boolean declined;
        public long proposedAt;
        public int revision;
    }

    public static final class Transition {
        public State from, to;
        public String reason;
        public long time;

        public Transition() {}

        public Transition(State from, State to, String reason, long time) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.time = time;
        }
    }

    public String id;
    public String player;
    public String playerName;
    public String text = "";
    /** lantern / chat / command / autonomous / script */
    public String origin = "chat";
    public State state = State.CREATED;
    public long created;
    public long updated;
    public List<Line> conversation = new ArrayList<>();
    /** 오래된 대화 요약 (최근 대화만 원문으로 보낸다). */
    public String conversationSummary = "";
    public String summary = "";
    public String outcome = "";
    public List<String> scriptIds = new ArrayList<>();
    public List<String> questIds = new ArrayList<>();
    public List<String> memoryIds = new ArrayList<>();
    public List<String> statusIds = new ArrayList<>();
    /** 다른 플레이어 동의 (uuid). */
    public List<String> consents = new ArrayList<>();
    public Contract contract;
    public List<Transition> history = new ArrayList<>();
    public int turns;
    public int failures;
    /** AI 응답 대기 중 (재접속 시 표시용). */
    public transient boolean thinking;

    public void addLine(String role, String text, long now) {
        conversation.add(new Line(role, text, now));
        updated = now;
    }
}
