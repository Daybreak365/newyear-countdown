package dev.newyear.countdown.ai.script.lang;

/**
 * 한 번의 실행(최상위 코드 실행 또는 이벤트 핸들러 한 번)에 허용되는 자원.
 * 단계 수, 할당량, 호출 깊이, 실제 시간을 모두 센다. 하나라도 넘으면 BUDGET 오류로 즉시 중단된다.
 */
public final class Budget {
    public long maxSteps;
    public long maxAlloc;
    public int maxDepth;
    public long maxNanos;

    long steps;
    long alloc;
    int depth;
    private long deadline;

    public Budget(long maxSteps, long maxAlloc, int maxDepth, long maxMillis) {
        this.maxSteps = maxSteps;
        this.maxAlloc = maxAlloc;
        this.maxDepth = maxDepth;
        this.maxNanos = maxMillis * 1_000_000L;
    }

    public static Budget defaults() {
        return new Budget(200_000, 400_000, 64, 40);
    }

    public void reset() {
        steps = 0;
        alloc = 0;
        depth = 0;
        deadline = System.nanoTime() + maxNanos;
    }

    public long steps() {
        return steps;
    }

    public long alloc() {
        return alloc;
    }

    void step() {
        if (++steps > maxSteps) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Step limit exceeded (" + maxSteps + " steps). Avoid long or infinite loops; spread work over time with every()", 0, 0);
        }
        if ((steps & 1023) == 0 && System.nanoTime() > deadline) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Time limit exceeded (" + (maxNanos / 1_000_000) + " ms per run)", 0, 0);
        }
    }

    /** 문자열 길이·배열 원소·객체 키 수 같은 대략적인 메모리 단위를 센다. */
    public void allocate(long units) {
        alloc += units;
        if (alloc > maxAlloc) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Memory limit exceeded (" + maxAlloc + " units). Keep arrays/strings small", 0, 0);
        }
    }

    void enter() {
        if (++depth > maxDepth) {
            depth = 0;
            throw new ScriptError(ScriptError.Kind.BUDGET, "Call depth limit exceeded (" + maxDepth + "). Recursion is too deep", 0, 0);
        }
    }

    void exit() {
        if (depth > 0) depth--;
    }
}
