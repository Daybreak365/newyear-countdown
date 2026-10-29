package dev.newyear.countdown.ai.script;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** AI 가 작성한 스크립트의 영속 기록 (관리·복구용 메타데이터 + 코드 + 상태). */
public final class ScriptRecord {
    public enum State {
        /** 검증 대기·초안. 실행되지 않는다. */
        DRAFT,
        /** 관리자 승인 대기. */
        PENDING_APPROVAL,
        ACTIVE,
        /** 킬 스위치나 관리자가 잠시 멈춤. 다시 켤 수 있다. */
        SUSPENDED,
        STOPPED,
        /** 지속 시간이 끝남. */
        EXPIRED,
        /** 오류·자원 초과로 자동 중단. */
        FAULTED;

        public boolean live() {
            return this == ACTIVE;
        }

        public boolean ended() {
            return this == STOPPED || this == EXPIRED || this == FAULTED;
        }
    }

    public static final class Version {
        public int version;
        public String code;
        public long time;
        public String reason;
    }

    public static final class BlockChange {
        public String dimension;
        public int x, y, z;
        /** 바꾸기 전 블록 상태 (문자열 표현). */
        public String before;
        public String after;
        public boolean temporary;
    }

    public static final class NamedTimer {
        public String name;
        public long fireTick;
        public JsonObject data;
    }

    public static final class Behaviour {
        public String entity;
        /** follow / orbit / stay / wander_near */
        public String kind;
        public String target;
        public double speed = 1.0;
        public double distance = 3.0;
        public double radius = 3.0;
        public String dimension;
        public double[] anchor;
    }

    public String id;
    public String name = "";
    public String description = "";
    public String code = "";
    public int version = 1;
    public List<Version> history = new ArrayList<>();
    public String owner;
    public String ownerName;
    public String wishId;
    /** 이 스크립트를 만든 AI 요청(턴) ID. */
    public String requestId;
    public long created;
    public State state = State.DRAFT;
    public String stateReason = "";
    public String dimension = "minecraft:overworld";
    /** 기준 위치 (월드 변경 범위 제한의 중심). */
    public double[] anchor;
    /** -1 이면 무기한. 엔진 틱. */
    public long durationTicks = -1;
    public long startedTick;
    public long expiresTick;
    public List<String> subscriptions = new ArrayList<>();
    public List<String> apiCalls = new ArrayList<>();
    public JsonObject stateJson = new JsonObject();
    public List<String> permissions = new ArrayList<>();
    public int errors;
    public String lastError = "";
    public long runs;
    public long steps;
    public List<String> entities = new ArrayList<>();
    /** 스크립트를 멈춰도 남겨 둘 엔티티 (세계에 남는 결과물). */
    public List<String> keptEntities = new ArrayList<>();
    public List<BlockChange> blocks = new ArrayList<>();
    public List<NamedTimer> timers = new ArrayList<>();
    public List<Behaviour> behaviours = new ArrayList<>();
    /** 자율 이벤트로 생긴 스크립트. */
    public boolean autonomous;
    public String approvedBy;
    /** 플레이어에게 보이는 설명 (HUD 능력 이름 등). */
    public String abilityLabel = "";
    /**
     * true 면 소원이 끝나도 계속 도는 능력·세계 규칙. false 면 소원(계약·퀘스트)을 위한 보조 스크립트로,
     * 소원이 완료·실패·취소되면 함께 정리된다.
     */
    public boolean lasting;
    /** run_script 처럼 결과물을 남기는 것이 목적인 스크립트: 소환한 엔티티를 기본으로 남긴다. */
    public boolean keepEntitiesDefault;
    /** 복구 중 주인이 접속해 있지 않아 실패: 주인이 들어오면 다시 시작한다. */
    public boolean waitingForOwner;
}
