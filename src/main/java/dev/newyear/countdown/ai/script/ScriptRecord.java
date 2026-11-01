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
    /** Engine-managed capabilities, checkpoints and declarative client state. Not script-writable. */
    public JsonObject runtime = new JsonObject();
    public List<String> permissions = new ArrayList<>();
    public int errors;
    public String lastError = "";
    public long runs;
    public long steps;
    public List<String> entities = new ArrayList<>();
    /** 스크립트를 멈춰도 남겨 둘 엔티티 (세계에 남는 결과물). */
    public List<String> keptEntities = new ArrayList<>();
    public List<BlockChange> blocks = new ArrayList<>();
    /** 되돌릴 때 그 사이 다른 누가 바꿔서 건드리지 않은 칸 (관리자가 rollback force 로 처리할 수 있다). */
    public List<BlockChange> blockConflicts = new ArrayList<>();
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
    /**
     * 재시작 때 이어 돌릴 것: 대기 중인 after()/every() 와 실행 중에 등록한 on() 을 클로저째 저장한 것,
     * 그리고 최상위 코드가 만든 타이머의 남은 시간. {@link Continuations} 가 쓰고 읽는다.
     */
    public JsonObject continuations;
    /** 마지막으로 잰 스크립트 힙 크기 (바이트, 추정). */
    public long heapBytes;
    /** 이 스크립트의 엔티티 AI 목표 (재시작·청크 재로딩 때 다시 붙인다). */
    public List<GoalSpec> goals = new ArrayList<>();
    /** 디스플레이 엔티티로 조립한 모델. */
    public List<ModelSpec> models = new ArrayList<>();
    /** 검증(모의 실행) 때 계산한 블록 변경 미리보기 (승인 대기 화면·월드 미리보기용). */
    public List<BlockChange> preview = new ArrayList<>();
    /** 진행 중인 큰 세계 변경 (world.sphere/box/replace/clear/terraform) — 틱마다 조금씩 적용, 재시작해도 이어진다. */
    public List<EditJob> edits = new ArrayList<>();

    /** 점진적 세계 변경 작업 하나. ops 는 [x, y, z, 팔레트 번호] 를 이어 붙인 배열, next 는 다음에 적용할 칸. */
    public static final class EditJob {
        public String id;
        public String kind;
        public String dimension;
        public List<String> palette = new ArrayList<>();
        public int[] ops = new int[0];
        public int next;
        /** 이 블록일 때만 바꾼다 (replace): 블록 id, "natural", 또는 null (조건 없음). */
        public String match;
        public boolean temporary;
        public int placed, skipped;
        /** 이 칸 수마다 반짝이를 하나 (연출). 0 이면 없음. */
        public int sparkleEvery = 6;

        public int total() {
            return ops.length / 4;
        }
    }

    /** 엔티티에 붙인 AI 목표 하나. kind: guard / flee / attack / wander / patrol / look / script. */
    public static final class GoalSpec {
        public int id;
        public String entity;
        public String kind;
        public int priority = 2;
        /** "player:<uuid>" / "entity:<uuid>" / "type:minecraft:zombie" / "hostile" / "any_player". */
        public String target;
        public double speed = 1.0;
        public double radius = 8;
        public double[] anchor;
        public List<double[]> points = new ArrayList<>();
        /** script 종류: 스크립트 콜백 번호 (실행 중에만 유효). */
        public int callback = -1;
        public int interval = 10;
    }

    /** 조립 모델: 부품(디스플레이 엔티티)마다 기준점에서의 변환. */
    public static final class ModelSpec {
        public String id;
        public String dimension;
        public double[] origin;
        public float yaw;
        public float scale = 1f;
        /** 따라다닐 대상 ("player:<uuid>" / "entity:<uuid>"), 없으면 null. */
        public String follow;
        public double[] followOffset;
        /** 따라다닐 때 대상이 보는 방향으로 모델도 돈다. */
        public boolean followTurn = true;
        public List<Part> parts = new ArrayList<>();

        public static final class Part {
            public String entity;
            public String kind;
            public String block;
            public String item;
            public String text;
            /** 기준점 기준 위치 (블록 단위), 크기, 회전 (도). */
            public float x, y, z;
            public float sx = 1, sy = 1, sz = 1;
            public float rx, ry, rz;
        }
    }
}
